package com.financeassistant.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;

public class ConfigTest {

    private Path writeEnv(String content) throws Exception {
        Path file = Files.createTempFile("test", ".env");
        Files.writeString(file, content);
        file.toFile().deleteOnExit();
        return file;
    }

    @Test
    public void readsValuesAndIgnoresComments() throws Exception {
        Path env = writeEnv("# comment\nLLM_MODEL=claude-haiku-5-5\n\nDB_PATH=\"data/x.json\"\nexport A=1\n");
        Config config = new Config(env, Map.of());
        assertEquals("claude-haiku-5-5", config.get("LLM_MODEL", null));
        assertEquals("data/x.json", config.get("DB_PATH", null));
        assertEquals("1", config.get("A", null));
        assertEquals("fallback", config.get("MISSING", "fallback"));
    }

    @Test
    public void environmentOverridesFile() throws Exception {
        Path env = writeEnv("LLM_MODEL=from-file\n");
        Config config = new Config(env, Map.of("LLM_MODEL", "from-env"));
        assertEquals("from-env", config.get("LLM_MODEL", null));
    }

    @Test
    public void placeholderKeyIsNotARealKey() throws Exception {
        assertFalse(new Config(writeEnv("LLM_API_KEY=YOUR_LOCAL_API_KEY_HERE\n"), Map.of()).hasApiKey());
        assertFalse(new Config(null, Map.of()).hasApiKey());
        assertTrue(new Config(writeEnv("LLM_API_KEY=sk-ant-test\n"), Map.of()).hasApiKey());
    }

    @Test
    public void badIntegerFallsBackToDefault() throws Exception {
        Config config = new Config(writeEnv("LLM_TIMEOUT_SECONDS=abc\n"), Map.of());
        assertEquals(30, config.getInt("LLM_TIMEOUT_SECONDS", 30));
    }
}
