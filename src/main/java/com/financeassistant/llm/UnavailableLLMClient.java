package com.financeassistant.llm;

/**
 * Used when no API key is configured. Every call fails with a clear message,
 * so AI features report "AI unavailable" and deterministic features keep working.
 */
public class UnavailableLLMClient implements LLMClient {
    private final String reason;

    public UnavailableLLMClient(String reason) {
        this.reason = reason;
    }

    @Override
    public String generate(String systemPrompt, String prompt) throws LLMException {
        throw new LLMException(reason);
    }

    @Override
    public String describe() {
        return "AI unavailable (" + reason + ")";
    }
}
