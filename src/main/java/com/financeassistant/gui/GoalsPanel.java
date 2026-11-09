package com.financeassistant.gui;

import com.financeassistant.controller.AgentController;
import com.financeassistant.goal.Goal;
import com.financeassistant.goal.GoalProgress;
import java.awt.BorderLayout;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

/** F08: create savings goals, add contributions, see progress and the AI's advice. */
class GoalsPanel extends JPanel implements Refreshable {
    private final AgentController controller;
    private final DefaultTableModel rows = Ui.model("ID", "Goal", "Saved", "Target", "Done", "Deadline", "Per month", "Status");
    private final JTable table = Ui.table(rows);
    private final JTextField name = new JTextField(12);
    private final JTextField target = new JTextField(7);
    private final JTextField deadline = new JTextField(LocalDate.now().plusMonths(6).toString(), 9);
    private final JTextArea message = Ui.textArea(4);

    GoalsPanel(AgentController controller) {
        super(new BorderLayout());
        this.controller = controller;
        JButton create = new JButton("Create goal");
        create.addActionListener(e -> create());
        JButton contribute = new JButton("Contribute...");
        contribute.addActionListener(e -> contribute());
        JButton delete = new JButton("Delete");
        delete.addActionListener(e -> delete());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showMessage();
            }
        });
        Ui.widths(table, 40, 180, 80, 80, 60, 90, 80);

        add(Ui.row("Name:", name, "Target $:", target, "Deadline (YYYY-MM-DD):", deadline, create, contribute, delete),
                BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.titled(table, "Savings goals"),
                Ui.titled(message, "Progress (select a goal)"));
        split.setResizeWeight(0.7);
        add(split, BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        Integer selected = selectedId();
        rows.setRowCount(0);
        for (Goal g : controller.getGoals()) {
            GoalProgress p = controller.getGoalProgress(g);
            String status = g.isCompleted() ? "completed" : p.estimatedCompletion() == null ? "not started"
                    : p.onTrack() ? "on track" : "behind";
            rows.addRow(new Object[]{g.getId(), g.getName(), Ui.money(g.getContributed()), Ui.money(g.getTargetAmount()),
                    String.format(Locale.ROOT, "%.0f%%", p.percent()), g.getDeadline(),
                    g.showsRequiredContribution() ? Ui.money(p.requiredMonthly()) : "-", status});
        }
        for (int i = 0; selected != null && i < rows.getRowCount(); i++) {
            if (selected.equals(rows.getValueAt(i, 0))) {
                int view = table.convertRowIndexToView(i);
                table.setRowSelectionInterval(view, view);
            }
        }
    }

    private void create() {
        try {
            LocalDate date;
            try {
                date = LocalDate.parse(deadline.getText().strip());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Dates look like 2027-04-30.");
            }
            controller.createGoal(name.getText(), Ui.amount(target.getText()), date);
            name.setText("");
            target.setText("");
            refresh();
        } catch (IllegalArgumentException e) {
            Ui.error(this, e.getMessage());
        }
    }

    private void contribute() {
        Integer id = selectedId();
        if (id == null) {
            Ui.error(this, "Select a goal first.");
            return;
        }
        String text = JOptionPane.showInputDialog(this, "Amount to add ($):");
        if (text == null) {
            return;
        }
        try {
            controller.addContribution(id, Ui.amount(text));
            refresh();
            showMessage();
        } catch (IllegalArgumentException | IllegalStateException e) {
            Ui.error(this, e.getMessage()); // e.g. the goal is already completed
        }
    }

    private void delete() {
        Integer id = selectedId();
        if (id != null && Ui.confirm(this, "Delete this goal and its contributions?")) {
            controller.deleteGoal(id);
            message.setText("");
            refresh();
        }
    }

    /** The message comes from the goal's state: AI coaching while in progress, congratulations once done. */
    private void showMessage() {
        Integer id = selectedId();
        if (id == null) {
            return;
        }
        message.setText("Thinking...");
        Ui.background(this, () -> {
            Goal g = controller.getGoal(id);
            GoalProgress p = controller.getGoalProgress(g);
            String facts = g.showsRequiredContribution()
                    ? String.format(Locale.ROOT, "Needed: $%.2f a month for %d month(s). Saving pace: $%.2f a month. Estimated finish: %s%n%n",
                    p.requiredMonthly(), p.monthsLeft(), p.averageMonthly(),
                    p.estimatedCompletion() == null ? "-" : p.estimatedCompletion())
                    : "";
            return facts + controller.getGoalMessage(g);
        }, message::setText);
    }

    private Integer selectedId() {
        int row = Ui.selectedRow(table);
        return row < 0 ? null : (Integer) rows.getValueAt(row, 0);
    }
}
