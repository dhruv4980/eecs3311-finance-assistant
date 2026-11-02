package com.financeassistant.model;

import java.time.LocalDateTime;

/** One line of the conversation with the agent, kept so the agent remembers earlier questions. */
public class ChatMessage implements Entity {
    public enum Role { USER, ASSISTANT }

    private int id;
    private Role role;
    private String text;
    private String timestamp;

    public ChatMessage() {
    }

    public ChatMessage(Role role, String text) {
        this.role = role;
        this.text = text;
        this.timestamp = LocalDateTime.now().withNano(0).toString();
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void setId(int id) {
        this.id = id;
    }

    public Role getRole() {
        return role;
    }

    public String getText() {
        return text;
    }

    public String getTimestamp() {
        return timestamp;
    }
}
