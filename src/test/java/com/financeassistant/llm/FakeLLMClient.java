package com.financeassistant.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Test double: answers prompts with a function and records every prompt it was sent. */
public class FakeLLMClient implements LLMClient {
    public final List<String> prompts = new ArrayList<>();
    private final Function<String, String> responder;

    public FakeLLMClient(Function<String, String> responder) {
        this.responder = responder;
    }

    /** A client that always fails, like Claude being down. */
    public static FakeLLMClient failing(String message) {
        return new FakeLLMClient(p -> {
            throw new RuntimeException(message);
        });
    }

    @Override
    public String generate(String systemPrompt, String prompt) throws LLMException {
        prompts.add(prompt);
        try {
            return responder.apply(prompt);
        } catch (RuntimeException e) {
            throw new LLMException(e.getMessage());
        }
    }

    @Override
    public String describe() {
        return "fake";
    }
}
