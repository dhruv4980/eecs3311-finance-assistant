package com.financeassistant.llm;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * Adaptee: Anthropic's Messages API as it really is - a JSON request payload
 * in, a JSON response out, over HTTPS. ClaudeClient adapts this to LLMClient.
 */
public class AnthropicApi {
    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String API_VERSION = "2023-06-01";
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public AnthropicApi(String apiKey, Duration timeout) {
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /** POST /v1/messages. Returns the parsed JSON body, or throws with the HTTP status. */
    public Map<String, Object> createMessage(Map<String, Object> payload) throws AnthropicApiException {
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(timeout)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AnthropicApiException(0, "timed out after " + timeout.toSeconds() + "s", e);
        } catch (IOException e) {
            throw new AnthropicApiException(0, "network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnthropicApiException(0, "interrupted", e);
        }
        Map<String, Object> body;
        try {
            body = gson.fromJson(response.body(), MAP_TYPE);
        } catch (RuntimeException e) {
            body = null;
        }
        if (response.statusCode() != 200) {
            throw new AnthropicApiException(response.statusCode(), errorMessage(body, response.body()), null);
        }
        if (body == null) {
            throw new AnthropicApiException(200, "response was not JSON", null);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static String errorMessage(Map<String, Object> body, String raw) {
        if (body != null && body.get("error") instanceof Map<?, ?> error && error.get("message") != null) {
            return String.valueOf(((Map<String, Object>) error).get("message"));
        }
        return raw == null ? "" : raw.substring(0, Math.min(200, raw.length()));
    }

    /** HTTP-level failure. status is 0 when no response was received. */
    public static class AnthropicApiException extends Exception {
        private final int status;

        public AnthropicApiException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
