package com.financeassistant.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.financeassistant.config.Config;
import com.financeassistant.llm.AnthropicApi.AnthropicApiException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ClaudeClientTest {

    /** Stands in for the real HTTP API so tests never call the network. */
    static class FakeApi extends AnthropicApi {
        Map<String, Object> lastPayload;
        Map<String, Object> reply;
        AnthropicApiException failure;

        FakeApi() {
            super("test-key", Duration.ofSeconds(1));
        }

        @Override
        public Map<String, Object> createMessage(Map<String, Object> payload) throws AnthropicApiException {
            lastPayload = payload;
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }

    @Test
    public void translatesPromptIntoAnthropicPayloadAndBack() throws Exception {
        FakeApi api = new FakeApi();
        api.reply = Map.of("content", List.of(
                Map.of("type", "text", "text", "Hello "),
                Map.of("type", "text", "text", "there")));
        ClaudeClient client = new ClaudeClient(api, "claude-haiku-5-5", 500);

        assertEquals("Hello there", client.generate("Be brief.", "Hi"));
        assertEquals("claude-haiku-5-5", api.lastPayload.get("model"));
        assertEquals(500, api.lastPayload.get("max_tokens"));
        assertEquals("Be brief.", api.lastPayload.get("system"));
        assertEquals(List.of(Map.of("role", "user", "content", "Hi")), api.lastPayload.get("messages"));
    }

    @Test
    public void omitsSystemWhenNotGiven() throws Exception {
        FakeApi api = new FakeApi();
        api.reply = Map.of("content", List.of(Map.of("type", "text", "text", "ok")));
        new ClaudeClient(api, "m", 10).generate("Hi");
        assertFalse(api.lastPayload.containsKey("system"));
    }

    @Test
    public void httpErrorsBecomeReadableLLMExceptions() {
        assertFailure(401, "API key");
        assertFailure(404, "LLM_MODEL");
        assertFailure(429, "rate limit");
        assertFailure(529, "temporarily unavailable");
        assertFailure(0, "could not be reached");
    }

    @Test
    public void emptyReplyIsAnError() {
        FakeApi api = new FakeApi();
        api.reply = Map.of("content", List.of());
        try {
            new ClaudeClient(api, "m", 10).generate("Hi");
            fail();
        } catch (LLMException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
    }

    @Test
    public void noKeyGivesUnavailableClient() {
        LLMClient client = LLMClients.fromConfig(new Config(null, Map.of()));
        assertTrue(client instanceof UnavailableLLMClient);
        try {
            client.generate("Hi");
            fail();
        } catch (LLMException e) {
            assertTrue(e.getMessage().contains("LLM_API_KEY"));
        }
        assertTrue(LLMClients.fromConfig(new Config(null, Map.of("LLM_API_KEY", "k"))) instanceof ClaudeClient);
    }

    private void assertFailure(int status, String expected) {
        FakeApi api = new FakeApi();
        api.failure = new AnthropicApiException(status, "boom", null);
        try {
            new ClaudeClient(api, "m", 10).generate("Hi");
            fail();
        } catch (LLMException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }
}
