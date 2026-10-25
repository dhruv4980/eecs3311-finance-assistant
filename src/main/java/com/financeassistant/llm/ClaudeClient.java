package com.financeassistant.llm;

import com.financeassistant.llm.AnthropicApi.AnthropicApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter: implements the simple LLMClient interface on top of Anthropic's
 * Messages API. It builds the request payload, calls the API and turns the
 * structured reply back into plain text. Changing the model, the API version
 * or even the provider only touches this class.
 */
public class ClaudeClient implements LLMClient {
    private final AnthropicApi api;
    private final String model;
    private final int maxTokens;

    public ClaudeClient(AnthropicApi api, String model, int maxTokens) {
        this.api = api;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    @Override
    public String generate(String systemPrompt, String prompt) throws LLMException {
        Map<String, Object> response;
        try {
            response = api.createMessage(toPayload(systemPrompt, prompt));
        } catch (AnthropicApiException e) {
            throw translate(e);
        }
        return extractText(response);
    }

    /** Converts our (system, prompt) call into Anthropic's request format. */
    Map<String, Object> toPayload(String systemPrompt, String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", maxTokens);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            payload.put("system", systemPrompt);
        }
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        return payload;
    }

    /** Joins the text blocks of Anthropic's {"content":[{"type":"text","text":...}]} reply. */
    String extractText(Map<String, Object> response) throws LLMException {
        Object content = response.get("content");
        if (!(content instanceof List<?> blocks)) {
            throw new LLMException("Claude returned an unexpected response (no content).");
        }
        List<String> parts = new ArrayList<>();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> map && "text".equals(map.get("type")) && map.get("text") != null) {
                parts.add(String.valueOf(map.get("text")));
            }
        }
        if (parts.isEmpty()) {
            throw new LLMException("Claude returned an empty response.");
        }
        return String.join("", parts).strip();
    }

    private LLMException translate(AnthropicApiException e) {
        String reason = switch (e.getStatus()) {
            case 0 -> "Claude could not be reached (" + e.getMessage() + ")";
            case 401, 403 -> "Claude rejected the API key - check LLM_API_KEY in your .env file";
            case 404 -> "Claude model '" + model + "' was not found - check LLM_MODEL in your .env file";
            case 429 -> "Claude rate limit reached - try again in a moment";
            default -> e.getStatus() >= 500
                    ? "Claude is temporarily unavailable (HTTP " + e.getStatus() + ")"
                    : "Claude request failed (HTTP " + e.getStatus() + "): " + e.getMessage();
        };
        return new LLMException(reason, e);
    }

    @Override
    public String describe() {
        return "Claude (" + model + ")";
    }
}
