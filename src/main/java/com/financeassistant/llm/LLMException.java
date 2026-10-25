package com.financeassistant.llm;

/** The LLM could not produce an answer (no key, timeout, rate limit, bad reply...). */
public class LLMException extends Exception {
    public LLMException(String message) {
        super(message);
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
    }
}
