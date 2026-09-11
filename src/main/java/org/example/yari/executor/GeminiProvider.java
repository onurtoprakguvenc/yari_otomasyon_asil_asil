package org.example.yari.executor;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * AI provider implementation for the Google Gemini (Generative Language) API.
 *
 * <p>Uses the official {@code generativelanguage.googleapis.com} REST endpoint
 * via the standard {@link java.net.http.HttpClient} — no external Google SDK
 * dependency required.</p>
 *
 * <p>Endpoint pattern:
 * {@code https://generativelanguage.googleapis.com/v1beta/models/{modelId}:generateContent?key={apiKey}}</p>
 *
 * <p>Rate-limit errors (HTTP 429 / {@code RESOURCE_EXHAUSTED}) are intercepted
 * and surfaced as {@link RateLimitException} so the {@code TaskStateMachine}
 * can apply exponential backoff.</p>
 */
public class GeminiProvider implements AiProvider {

    private static final String DEFAULT_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/";

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    /**
     * Constructs a GeminiProvider.
     *
     * @param apiKey   Google AI Studio API key
     * @param baseUrl  base URL override (may be null or blank for the default)
     */
    public GeminiProvider(String apiKey, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Gemini API key must not be blank. "
                    + "Obtain a free key from https://aistudio.google.com/apikey");
        }
        this.apiKey = apiKey;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String execute(String prompt, String modelId) throws RateLimitException, ProviderException {

        // ---- Build request JSON ------------------------------------------------
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", prompt);

        JsonArray partsArray = new JsonArray();
        partsArray.add(textPart);

        JsonObject contentObj = new JsonObject();
        contentObj.addProperty("role", "user");
        contentObj.add("parts", partsArray);

        JsonArray contentsArray = new JsonArray();
        contentsArray.add(contentObj);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("temperature", 0.2);

        JsonObject requestBody = new JsonObject();
        requestBody.add("contents", contentsArray);
        requestBody.add("generationConfig", generationConfig);

        // ---- Build endpoint URI ------------------------------------------------
        String normalizedBase = this.baseUrl.endsWith("/") ? this.baseUrl : this.baseUrl + "/";
        String effectiveModel = (modelId == null || modelId.isBlank()) ? "gemini-2.5-flash" : modelId;
        String url = normalizedBase + "models/" + effectiveModel + ":generateContent?key=" + apiKey;

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Malformed Gemini endpoint URL: " + url, e);
        }

        // ---- Send request ------------------------------------------------------
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException("Network error calling Gemini API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Gemini API call interrupted", e);
        }

        int statusCode = httpResponse.statusCode();
        String body = httpResponse.body() != null ? httpResponse.body() : "";

        // ---- Handle rate limiting (HTTP 429 / RESOURCE_EXHAUSTED) ---------------
        if (statusCode == 429) {
            long retryAfterMs = parseRetryAfter(httpResponse);
            throw new RateLimitException(
                    "Gemini rate limit exceeded (HTTP 429): " + truncate(body, 300),
                    retryAfterMs);
        }

        // Google may also signal quota exhaustion as 200 with an error body,
        // or as 503 with RESOURCE_EXHAUSTED in the JSON error status.
        if (statusCode == 503 && body.contains("RESOURCE_EXHAUSTED")) {
            long retryAfterMs = parseRetryAfter(httpResponse);
            throw new RateLimitException(
                    "Gemini resource exhausted (HTTP 503): " + truncate(body, 300),
                    retryAfterMs);
        }

        // ---- Handle other errors -----------------------------------------------
        if (statusCode < 200 || statusCode >= 300) {
            String detail = extractErrorMessage(body);
            throw new ProviderException(
                    String.format("Gemini API error (HTTP %d): %s", statusCode, detail),
                    statusCode);
        }

        // ---- Extract text from successful response ------------------------------
        return extractCandidateText(body);
    }

    // ---------- Response parsing helpers ------------------------------------------

    /**
     * Strictly extracts text from {@code candidates[0].content.parts[0].text}.
     *
     * @throws ProviderException if the expected path is missing or malformed
     */
    private String extractCandidateText(String responseBody) throws ProviderException {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

            // Check for inline error responses even on 200
            if (root.has("error")) {
                JsonObject error = root.getAsJsonObject("error");
                String msg = error.has("message") ? error.get("message").getAsString() : "unknown";
                int code = error.has("code") ? error.get("code").getAsInt() : -1;
                throw new ProviderException("Gemini returned an error: " + msg, code);
            }

            if (!root.has("candidates")) {
                // Some responses may have "promptFeedback" with a block reason
                if (root.has("promptFeedback")) {
                    JsonObject feedback = root.getAsJsonObject("promptFeedback");
                    String blockReason = feedback.has("blockReason")
                            ? feedback.get("blockReason").getAsString()
                            : "UNKNOWN";
                    throw new ProviderException(
                            "Gemini blocked the prompt (reason: " + blockReason + ")", -1);
                }
                throw new ProviderException(
                        "Gemini response missing 'candidates' field: " + truncate(responseBody, 500), -1);
            }

            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates.isEmpty()) {
                throw new ProviderException("Gemini returned zero candidates", -1);
            }

            JsonObject firstCandidate = candidates.get(0).getAsJsonObject();

            // Check finish reason for safety blocks
            if (firstCandidate.has("finishReason")) {
                String finishReason = firstCandidate.get("finishReason").getAsString();
                if ("SAFETY".equals(finishReason) || "RECITATION".equals(finishReason)) {
                    throw new ProviderException(
                            "Gemini candidate blocked (finishReason: " + finishReason + ")", -1);
                }
            }

            if (!firstCandidate.has("content")) {
                throw new ProviderException(
                        "Gemini candidate[0] missing 'content' field", -1);
            }

            JsonObject content = firstCandidate.getAsJsonObject("content");
            if (!content.has("parts")) {
                throw new ProviderException(
                        "Gemini candidate[0].content missing 'parts' field", -1);
            }

            JsonArray parts = content.getAsJsonArray("parts");
            if (parts.isEmpty()) {
                throw new ProviderException(
                        "Gemini candidate[0].content.parts is empty", -1);
            }

            // Concatenate all text parts (multi-part responses are possible)
            StringBuilder textBuilder = new StringBuilder();
            for (JsonElement partEl : parts) {
                JsonObject part = partEl.getAsJsonObject();
                if (part.has("text")) {
                    textBuilder.append(part.get("text").getAsString());
                }
            }

            String result = textBuilder.toString();
            if (result.isEmpty()) {
                throw new ProviderException(
                        "Gemini candidate[0].content.parts[0] contains no 'text' field", -1);
            }

            return result;

        } catch (JsonSyntaxException e) {
            throw new ProviderException(
                    "Failed to parse Gemini JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * Attempts to extract a human-readable error message from a Gemini error response body.
     */
    private String extractErrorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("error")) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("message")) {
                    return error.get("message").getAsString();
                }
                if (error.has("status")) {
                    return error.get("status").getAsString();
                }
            }
        } catch (Exception ignored) {
            // Not valid JSON or unexpected structure — fall through
        }
        return truncate(body, 300);
    }

    /**
     * Parses the {@code Retry-After} header. Falls back to 10 seconds if absent or invalid,
     * since Gemini free-tier rate limits can be aggressive.
     */
    private long parseRetryAfter(HttpResponse<String> response) {
        var retryHeader = response.headers().firstValue("retry-after");
        if (retryHeader.isPresent()) {
            try {
                return Long.parseLong(retryHeader.get()) * 1000L;
            } catch (NumberFormatException ignored) {}
        }
        // Gemini free-tier default: 10 seconds — slightly more conservative than
        // paid-tier providers to respect the generous but narrow quota window.
        return 10_000L;
    }

    /**
     * Truncates a string to the given max length, appending "…" if truncated.
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }
}
