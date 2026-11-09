package com.financeassistant.gui;

import com.financeassistant.agent.AgentAnswer;
import com.financeassistant.controller.AgentController;
import com.financeassistant.model.ChatMessage;
import java.awt.BorderLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/** F07: ask the agent about your money. It looks things up with its tools, then answers. */
class ChatPanel extends JPanel implements Refreshable {
    private final AgentController controller;
    private final JTextArea transcript = Ui.textArea(20);
    private final JTextField input = new JTextField(60);
    private final JButton send = new JButton("Send");
    private boolean loaded;

    ChatPanel(AgentController controller) {
        super(new BorderLayout());
        this.controller = controller;
        send.addActionListener(e -> ask());
        input.addActionListener(e -> ask()); // Enter sends
        JButton clear = new JButton("Clear memory");
        clear.addActionListener(e -> {
            controller.clearChat();
            transcript.setText("");
        });
        add(Ui.titled(transcript, "Conversation - e.g. \"Where did most of my money go in September?\""), BorderLayout.CENTER);
        add(Ui.row("Ask:", input, send, clear), BorderLayout.SOUTH);
    }

    @Override
    public void refresh() {
        if (loaded) {
            return;
        }
        loaded = true;
        for (ChatMessage m : controller.getChatHistory()) {
            transcript.append((m.getRole() == ChatMessage.Role.USER ? "You: " : "Assistant: ") + m.getText() + "\n\n");
        }
        input.requestFocusInWindow();
    }

    void ask() {
        String question = input.getText().strip();
        if (question.isEmpty()) {
            return;
        }
        input.setText("");
        send.setEnabled(false);
        transcript.append("You: " + question + "\n");
        Ui.background(this, () -> controller.askQuestion(question), (AgentAnswer answer) -> {
            for (AgentAnswer.ToolCall call : answer.toolCalls()) {
                transcript.append("    (looked up " + call.tool() + " " + call.args() + ")\n");
            }
            transcript.append("Assistant: " + answer.text() + "\n\n");
            transcript.setCaretPosition(transcript.getDocument().getLength());
            send.setEnabled(true);
        });
    }

    void setQuestion(String text) {
        input.setText(text);
    }
}
