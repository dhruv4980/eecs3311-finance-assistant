package com.financeassistant.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads application settings from a local .env file, with real environment
 * variables taking priority. Secrets such as LLM_API_KEY live only in the
 * git-ignored .env file, never in source code.
 */
public final class Config {

    private final Map<String, String> values = new HashMap<>();
    private final Map<String, String> environment;

    /** Loads ".env" from the current working directory (if present). */
    public static Config load() {
        return new Config(Path.of(".env"), System.getenv());
    }

    public Config(Path envFile, Map<String, String> environment) {
        this.environment = environment;
        if (envFile != null && Files.isRegularFile(envFile)) {
            try {
                parse(Files.readAllLines(envFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + envFile + ": " + e.getMessage(), e);
            }
        }
    }

    private void parse(List<String> lines) {
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
    }

    /** Returns the setting, preferring an environment variable over the .env file. */
    public String get(String key, String defaultValue) {
        String env = environment.get(key);
        if (env != null && !env.isBlank()) {
            return env;
        }
        String file = values.get(key);
        return (file == null || file.isBlank()) ? defaultValue : file;
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** True when a real API key (not the placeholder from .env.example) is configured. */
    public boolean hasApiKey() {
        String key = get("LLM_API_KEY", null);
        return key != null && !key.equals("YOUR_LOCAL_API_KEY_HERE");
    }
}
