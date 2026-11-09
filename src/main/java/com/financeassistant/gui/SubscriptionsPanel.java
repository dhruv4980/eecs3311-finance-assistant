package com.financeassistant.gui;

import com.financeassistant.controller.AgentController;
import com.financeassistant.model.Subscription;
import com.financeassistant.subscription.SubscriptionScan;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.table.DefaultTableModel;

/** F06: recurring charges found in the transactions, with AI service types and overlap advice. */
class SubscriptionsPanel extends JPanel implements Refreshable {
    private final AgentController controller;
    private final DefaultTableModel rows = Ui.model("ID", "Name", "Type", "Amount", "Every", "Per month", "Next", "Notes");
    private final JTable table = Ui.table(rows);
    private final JTextArea advice = Ui.textArea(4);
    private final JLabel total = new JLabel();

    SubscriptionsPanel(AgentController controller) {
        super(new BorderLayout());
        this.controller = controller;
        JButton scan = new JButton("Scan for subscriptions");
        scan.addActionListener(e -> scan());
        JButton confirm = new JButton("Confirm");
        confirm.addActionListener(e -> withSelected(id -> controller.confirmSubscription(id)));
        JButton rename = new JButton("Rename...");
        rename.addActionListener(e -> withSelected(id -> {
            String name = JOptionPane.showInputDialog(this, "New name:");
            if (name != null) {
                controller.renameSubscription(id, name);
            }
        }));
        JButton not = new JButton("Not a subscription");
        not.addActionListener(e -> withSelected(id -> controller.markNotSubscription(id)));
        Ui.widths(table, 40, 200, 140, 70, 70, 80, 90);

        add(Ui.row(scan, confirm, rename, not, total), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.titled(table, "Recurring charges"),
                Ui.titled(advice, "Overlapping services"));
        split.setResizeWeight(0.75);
        add(split, BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        show(controller.getSubscriptions());
        if (rows.getRowCount() == 0) {
            advice.setText("Click \"Scan for subscriptions\" to look for repeating charges in your transactions.");
        }
    }

    /** Detection, AI labels and the overlap message all run in the background. */
    void scan() {
        advice.setText("Scanning...");
        Ui.background(this, () -> {
            SubscriptionScan scan = controller.detectSubscriptions();
            String overlaps = scan.subscriptions().isEmpty() ? "" : controller.getOverlapMessage();
            return new Object[]{scan, overlaps};
        }, result -> {
            SubscriptionScan scan = (SubscriptionScan) result[0];
            show(scan.subscriptions());
            String text = scan.message() != null ? scan.message() : (String) result[1];
            if (scan.aiError() != null) {
                text += "\n(Service types not labelled - AI unavailable: " + scan.aiError() + ")";
            }
            advice.setText(text);
        });
    }

    private void show(List<Subscription> subscriptions) {
        rows.setRowCount(0);
        double monthly = 0;
        for (Subscription s : subscriptions) {
            String notes = (s.isActive() ? "" : "stopped? ")
                    + (s.isPriceIncreased() ? String.format(Locale.ROOT, "price up from $%.2f ", s.getPreviousAmount()) : "")
                    + (s.getStatus() == Subscription.Status.CONFIRMED ? "confirmed" : "");
            rows.addRow(new Object[]{s.getId(), s.getName(), s.getServiceType() == null ? "?" : s.getServiceType(),
                    Ui.money(s.getAmount()), s.getFrequency().name().toLowerCase(Locale.ROOT), Ui.money(s.getMonthlyCost()),
                    s.isActive() ? s.getNextExpected() : "-", notes.strip()});
            if (s.isActive()) {
                monthly += s.getMonthlyCost();
            }
        }
        total.setText(subscriptions.isEmpty() ? "" : String.format(Locale.ROOT, "Active recurring charges: about $%.2f a month", monthly));
    }

    private void withSelected(java.util.function.IntConsumer action) {
        int row = Ui.selectedRow(table);
        if (row < 0) {
            Ui.error(this, "Select a subscription first.");
            return;
        }
        try {
            action.accept((Integer) rows.getValueAt(row, 0));
        } catch (IllegalArgumentException e) {
            Ui.error(this, e.getMessage());
        }
        show(controller.getSubscriptions());
    }
}
