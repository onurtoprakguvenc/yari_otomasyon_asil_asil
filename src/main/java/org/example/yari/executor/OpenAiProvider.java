package org.example.yari.executor;

import com.google.gson.*;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * AI provider implementation for the OpenAI Chat Completions API.
 * Uses official REST API; no web-scraping or browser automation.
 */
public class OpenAiProvider implements AiProvider {

    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final String apiKey;
    private final String endpoint;
    private final OkHttpClient client;

    public OpenAiProvider(String apiKey, String endpoint) {
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
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_MEDIA))
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (response.code() == 429) {
                long retryAfter = parseRetryAfter(response);
                throw new RateLimitException(
                        "OpenAI rate limit exceeded: " + responseBody, retryAfter);
            }

            if (!response.isSuccessful()) {
                throw new ProviderException(
                        "OpenAI API error (" + response.code() + "): " + responseBody,
                        response.code());
            }

            return extractContent(responseBody);

        } catch (IOException e) {
            throw new ProviderException("Network error calling OpenAI API: " + e.getMessage(), e);
        }
    }

    private String extractContent(String responseBody) throws ProviderException {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices.isEmpty()) {
                throw new ProviderException("No choices in OpenAI response", -1);
            }
            return choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            throw new ProviderException("Failed to parse OpenAI response: " + e.getMessage(), e);
        }
    }

    private long parseRetryAfter(Response response) {
        String retryHeader = response.header("retry-after");
        if (retryHeader != null) {
            try {
                return Long.parseLong(retryHeader) * 1000L;
            } catch (NumberFormatException ignored) {}
        }
        return 5000L;
    }
}
