package com.financeassistant.gui;

import com.financeassistant.budget.AlertObserver;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.time.LocalTime;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Concrete observer (F05): attached to BudgetService through the controller,
 * it shows each budget alert at the bottom of the window as soon as a
 * category reaches 80% or goes over its budget - from any tab.
 */
public class GuiAlertPanel extends JPanel implements AlertObserver {
    private final DefaultListModel<String> messages = new DefaultListModel<>();
    private final JLabel title = new JLabel();
    private int unread;

    public GuiAlertPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        JList<String> list = new JList<>(messages);
        list.setVisibleRowCount(3);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(100, 70));
        JButton clear = new JButton("Mark as read");
        clear.addActionListener(e -> markRead());
        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.WEST);
        top.add(clear, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        updateTitle();
    }

    /** Called by BudgetService (possibly from a background thread), so the UI update is moved to the Swing thread. */
    @Override
    public void onAlert(String message) {
        SwingUtilities.invokeLater(() -> {
            messages.add(0, LocalTime.now().withNano(0) + "  " + message);
            unread++;
            updateTitle();
        });
    }

    public int getUnreadCount() {
        return unread;
    }

    private void markRead() {
        unread = 0;
        updateTitle();
    }

    private void updateTitle() {
        title.setText(unread == 0 ? "Budget alerts" : "Budget alerts - " + unread + " new");
        title.setForeground(unread == 0 ? Color.DARK_GRAY : new Color(180, 40, 30));
    }
}
