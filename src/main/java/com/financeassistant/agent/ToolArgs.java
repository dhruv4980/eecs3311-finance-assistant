package com.financeassistant.agent;

import com.financeassistant.budget.BudgetService;
import java.util.Map;

/** Helpers for reading the loosely-typed arguments the LLM sends. */
final class ToolArgs {
    private ToolArgs() {
    }

    static String text(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() || text.equalsIgnoreCase("null") ? null : text;
    }

    /** Optional month argument; validated, null when absent. */
    static String month(Map<String, Object> args, String name) {
        String month = text(args, name);
        return month == null ? null : BudgetService.validateMonth(month);
    }

    static int integer(Map<String, Object> args, String name, int defaultValue, int min, int max) {
        Object value = args == null ? null : args.get(name);
        int result = defaultValue;
        if (value instanceof Number n) {
            result = n.intValue();
        } else if (value != null) {
            try {
                result = Integer.parseInt(String.valueOf(value).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a whole number");
            }
        }
        return Math.max(min, Math.min(max, result));
    }
}
