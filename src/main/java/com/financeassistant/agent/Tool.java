package com.financeassistant.agent;

import java.util.Map;

/**
 * Something the agent can do to look at the user's data. The LLM chooses
 * which tool to call and with what arguments; the tool itself is ordinary
 * deterministic code, so every number the agent reports comes from here.
 */
public interface Tool {
    /** Name the LLM uses to call the tool, e.g. "get_spending". */
    String getName();

    /** One-sentence explanation shown to the LLM. */
    String getDescription();

    /** The arguments, described for the LLM, e.g. "month (optional, YYYY-MM)". */
    String getParameters();

    /**
     * Runs the tool. Returns the result as compact JSON text for the LLM.
     *
     * @throws IllegalArgumentException with a readable message for bad arguments
     */
    String execute(Map<String, Object> args);
}
