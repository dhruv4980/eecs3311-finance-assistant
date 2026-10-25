package com.financeassistant.llm;

import com.financeassistant.config.Config;
import java.time.Duration;

/** Builds the LLMClient described by the configuration. */
public final class LLMClients {
    public static final String DEFAULT_MODEL = "claude-haiku-5-5";

    private LLMClients() {
    }

    public static LLMClient fromConfig(Config config) {
        if (!config.hasApiKey()) {
            return new UnavailableLLMClient("no LLM_API_KEY set - copy .env.example to .env and add your key");
        }
        Duration timeout = Duration.ofSeconds(config.getInt("LLM_TIMEOUT_SECONDS", 30));
        AnthropicApi api = new AnthropicApi(config.get("LLM_API_KEY", null), timeout);
        return new ClaudeClient(api, config.get("LLM_MODEL", DEFAULT_MODEL), config.getInt("LLM_MAX_TOKENS", 4096));
    }
}
