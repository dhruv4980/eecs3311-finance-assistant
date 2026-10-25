package com.financeassistant.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses "word word --name value --flag" style command arguments. */
final class Args {
    final List<String> positional = new ArrayList<>();
    private final Map<String, String> options = new HashMap<>();
    private final Set<String> flags = new HashSet<>();

    Args(List<String> tokens, Set<String> flagNames) {
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.startsWith("--")) {
                String name = token.substring(2);
                int eq = name.indexOf('=');
                if (eq > 0) {
                    options.put(name.substring(0, eq), name.substring(eq + 1));
                } else if (flagNames.contains(name) || i + 1 >= tokens.size() || tokens.get(i + 1).startsWith("--")) {
                    flags.add(name);
                } else {
                    options.put(name, tokens.get(++i));
                }
            } else {
                positional.add(token);
            }
        }
    }

    String get(String name) {
        return options.get(name);
    }

    String require(String name, String example) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + name + " (for example --" + name + " " + example + ")");
        }
        return value;
    }

    boolean has(String flag) {
        return flags.contains(flag);
    }

    int requireInt(String name) {
        String value = require(name, "12");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a whole number, not '" + value + "'");
        }
    }

    double requireAmount(String name) {
        String value = require(name, "200");
        try {
            return Double.parseDouble(value.replace("$", "").replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be a number, not '" + value + "'");
        }
    }

    /** Splits a typed line into tokens, keeping "quoted text" together. */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean hasToken = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
                hasToken = true;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
            } else {
                current.append(c);
                hasToken = true;
            }
        }
        if (hasToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
