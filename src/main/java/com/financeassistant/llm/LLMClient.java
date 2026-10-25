package com.financeassistant.llm;

/**
 * Target interface of the Adapter pattern: what the rest of the system needs
 * from an LLM - send a prompt, get text back. Services depend only on this,
 * never on the Anthropic request/response format.
 */
public interface LLMClient {

    /** Sends a system instruction and a prompt; returns the model's text reply. */
    String generate(String systemPrompt, String prompt) throws LLMException;

    default String generate(String prompt) throws LLMException {
        return generate(null, prompt);
    }

    /** Short description for logs and the UI, e.g. "Claude (claude-haiku-5-5)". */
    String describe();
}
