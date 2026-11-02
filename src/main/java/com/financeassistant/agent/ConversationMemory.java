package com.financeassistant.agent;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.model.ChatMessage;
import java.util.Comparator;
import java.util.List;

/**
 * The agent's memory of the conversation. Messages are stored in the
 * database, so a follow-up question ("what about last month?") works even
 * across app restarts; only the most recent messages are sent to the LLM.
 */
public class ConversationMemory {
    private final DatabaseManager db = DatabaseManager.getInstance();
    private final int window;

    public ConversationMemory(int window) {
        this.window = window;
    }

    public void add(ChatMessage.Role role, String text) {
        db.save(new ChatMessage(role, text));
    }

    /** All stored messages, oldest first. */
    public List<ChatMessage> getHistory() {
        return db.findAll(ChatMessage.class).stream().sorted(Comparator.comparingInt(ChatMessage::getId)).toList();
    }

    /** The last few messages, oldest first - what the LLM sees. */
    public List<ChatMessage> recent() {
        List<ChatMessage> all = getHistory();
        return all.subList(Math.max(0, all.size() - window), all.size());
    }

    public void clear() {
        for (ChatMessage m : db.findAll(ChatMessage.class)) {
            db.delete(ChatMessage.class, m.getId());
        }
    }
}
