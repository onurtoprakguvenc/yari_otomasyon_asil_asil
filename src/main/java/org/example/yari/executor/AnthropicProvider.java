package org.example.yari.executor;

import com.google.gson.*;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * AI provider implementation for the Anthropic Messages API.
 * Uses official REST API; no web-scraping or browser automation.
 */
public class AnthropicProvider implements AiProvider {

    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String endpoint;
    private final OkHttpClient client;

    public AnthropicProvider(String apiKey, String endpoint) {
        this.apiKey = apiKey;
        this.endpoint = (endpoint == null || endpoint.isBlank()) ? DEFAULT_ENDPOINT : endpoint;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String execute(String prompt, String modelId) throws RateLimitException, ProviderException {
        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("max_tokens", 4096);

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);
        body.add("messages", messages);

        Request request = new Request.Builder()
                .url(endpoint)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (response.code() == 429) {
                long retryAfter = parseRetryAfter(response);
                throw new RateLimitException(
                        "Anthropic rate limit exceeded: " + responseBody, retryAfter);
            }

            if (!response.isSuccessful()) {
                throw new ProviderException(
                        "Anthropic API error (" + response.code() + "): " + responseBody,
                        response.code());
            }

            return extractTextContent(responseBody);

        } catch (IOException e) {
            throw new ProviderException("Network error calling Anthropic API: " + e.getMessage(), e);
        }
    }

    private String extractTextContent(String responseBody) throws ProviderException {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray content = json.getAsJsonArray("content");
            StringBuilder text = new StringBuilder();
            for (JsonElement el : content) {
                JsonObject block = el.getAsJsonObject();
                if ("text".equals(block.get("type").getAsString())) {
                    text.append(block.get("text").getAsString());
                }
            }
            return text.toString();
        } catch (Exception e) {
            throw new ProviderException("Failed to parse Anthropic response: " + e.getMessage(), e);
        }
    }

    private long parseRetryAfter(Response response) {
        String retryHeader = response.header("retry-after");
        if (retryHeader != null) {
            try {
                return Long.parseLong(retryHeader) * 1000L;
            } catch (NumberFormatException ignored) {}
        }
        return 5000L; // default 5 seconds
    }
}
