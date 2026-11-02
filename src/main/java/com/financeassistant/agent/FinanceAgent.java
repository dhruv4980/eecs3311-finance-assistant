package com.financeassistant.agent;

import com.financeassistant.llm.LLMClient;
import com.financeassistant.llm.LLMException;
import com.financeassistant.model.ChatMessage;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * F07 Natural chat with the agent.
 *
 * <p>A tool-using agent loop: on each step the LLM sees the question, the
 * recent conversation and the results of the tools it has called so far,
 * and replies either with a tool call or with the final answer. The loop
 * stops at the answer or after {@code maxSteps} steps. The LLM decides what
 * to look up and how to explain it; the tools do every calculation.
 */
public class FinanceAgent {
    private static final Type ARGS_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static final int MAX_RESULT_CHARS = 6000;

    private final LLMClient llm;
    private final ConversationMemory memory;
    private final int maxSteps;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Gson gson = new Gson();
    private Supplier<String> context = () -> "";

    public FinanceAgent(LLMClient llm, ConversationMemory memory, int maxSteps) {
        this.llm = llm;
        this.memory = memory;
        this.maxSteps = maxSteps;
    }

    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /** Extra facts added to every prompt, e.g. which months have data. */
    public void setContext(Supplier<String> context) {
        this.context = context;
    }

    public List<Tool> getTools() {
        return List.copyOf(tools.values());
    }

    /** Answers a question, calling tools as needed. Throws if the LLM cannot be reached. */
    public AgentAnswer run(String question) throws LLMException {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Please type a question.");
        }
        List<ChatMessage> history = memory.recent();
        List<AgentAnswer.ToolCall> calls = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();

        for (int step = 0; step < maxSteps; step++) {
            String reply = llm.generate(systemPrompt(), userPrompt(history, question, scratchpad));
            Decision decision = parseDecision(reply);
            if (decision.answer != null) {
                return finish(question, decision.answer, calls, AgentAnswer.Status.ANSWERED);
            }
            String result = executeTool(decision.tool, decision.args);
            String argsJson = gson.toJson(decision.args);
            calls.add(new AgentAnswer.ToolCall(decision.tool, argsJson, preview(result)));
            scratchpad.append("Step ").append(step + 1).append(": you called ").append(decision.tool)
                    .append(' ').append(argsJson).append("\nResult: ").append(result).append("\n\n");
        }
        return finish(question, stepLimitReached(calls), calls, AgentAnswer.Status.STEP_LIMIT_REACHED);
    }

    /** Runs a tool by name. Problems are returned as text so the LLM can correct itself. */
    String executeTool(String name, Map<String, Object> args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "Error: there is no tool called \"" + name + "\". Available: " + String.join(", ", tools.keySet());
        }
        try {
            String result = tool.execute(args == null ? Map.of() : args);
            return result.length() > MAX_RESULT_CHARS
                    ? result.substring(0, MAX_RESULT_CHARS) + "... (truncated - narrow the search)" : result;
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String stepLimitReached(List<AgentAnswer.ToolCall> calls) {
        return "I couldn't finish working that out (I stopped after " + calls.size()
                + " lookups). Try asking a narrower question, for example about one month or one category.";
    }

    private AgentAnswer finish(String question, String answer, List<AgentAnswer.ToolCall> calls,
                               AgentAnswer.Status status) {
        memory.add(ChatMessage.Role.USER, question);
        memory.add(ChatMessage.Role.ASSISTANT, answer);
        return new AgentAnswer(answer, calls, status);
    }

    String systemPrompt() {
        StringBuilder sb = new StringBuilder("""
                You are a personal finance assistant inside a budgeting app. You help the user understand
                their spending, budgets, subscriptions and savings goals, and suggest realistic ways to save.

                You cannot see the user's data directly. Use the tools below to look it up.
                Rules:
                - Reply with exactly ONE JSON object per turn and nothing else:
                    {"tool": "<tool name>", "args": { ... }}   to call a tool, or
                    {"answer": "<your reply to the user>"}     when you can answer.
                - Only state numbers that appear in tool results. Never guess or invent figures.
                - If the data needed is not available, say so plainly.
                - Keep answers short (2-6 sentences), friendly and specific. Use $ amounts with 2 decimals.
                - You are not a licensed financial advisor; give practical budgeting suggestions, not investment advice.

                Tools:
                """);
        for (Tool tool : tools.values()) {
            sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription())
                    .append("\n    args: ").append(tool.getParameters()).append('\n');
        }
        return sb.toString();
    }

    private String userPrompt(List<ChatMessage> history, String question, CharSequence scratchpad) {
        StringBuilder sb = new StringBuilder();
        sb.append("Today is ").append(LocalDate.now()).append(". ").append(context.get()).append("\n\n");
        if (!history.isEmpty()) {
            sb.append("Earlier conversation:\n");
            for (ChatMessage m : history) {
                sb.append(m.getRole() == ChatMessage.Role.USER ? "User: " : "Assistant: ").append(m.getText()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("User question: ").append(question).append("\n\n");
        if (scratchpad.length() > 0) {
            sb.append("Tool results so far:\n").append(scratchpad);
        }
        sb.append("Reply with one JSON object: a tool call or your answer.");
        return sb.toString();
    }

    /** A reply is a tool call, an answer, or (if the model ignored the format) plain text treated as the answer. */
    Decision parseDecision(String reply) {
        String text = reply == null ? "" : reply.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonObject json = JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
                JsonElement answer = json.get("answer");
                if (answer != null && answer.isJsonPrimitive()) {
                    return new Decision(null, null, answer.getAsString());
                }
                JsonElement tool = json.get("tool");
                if (tool != null && tool.isJsonPrimitive()) {
                    Map<String, Object> args = json.has("args") && json.get("args").isJsonObject()
                            ? gson.fromJson(json.get("args"), ARGS_TYPE) : Map.of();
                    return new Decision(tool.getAsString(), args, null);
                }
            } catch (JsonParseException | IllegalStateException e) {
                // fall through: not a structured reply
            }
        }
        return new Decision(null, null, text.isEmpty() ? "Sorry, I don't have an answer for that." : text);
    }

    private static String preview(String result) {
        return result.length() <= 200 ? result : result.substring(0, 200) + "...";
    }

    record Decision(String tool, Map<String, Object> args, String answer) {
    }
}
