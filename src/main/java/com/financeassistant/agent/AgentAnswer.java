package com.financeassistant.agent;

import java.util.List;

/**
 * What the agent replied, plus the tool calls it made to get there (shown in
 * the UI so the user can see where the numbers came from).
 */
public record AgentAnswer(String text, List<ToolCall> toolCalls, Status status) {

    public enum Status { ANSWERED, STEP_LIMIT_REACHED, UNAVAILABLE }

    public record ToolCall(String tool, String args, String resultPreview) {
    }

    public static AgentAnswer unavailable(String reason) {
        return new AgentAnswer("The AI assistant is unavailable right now: " + reason, List.of(), Status.UNAVAILABLE);
    }
}
