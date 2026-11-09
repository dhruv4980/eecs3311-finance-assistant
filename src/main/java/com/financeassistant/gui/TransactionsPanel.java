package com.financeassistant.gui;

import com.financeassistant.categorization.CategorizationResult;
import com.financeassistant.categorization.CategorySummary;
import com.financeassistant.categorization.RecategorizeResult;
import com.financeassistant.categorization.RuleConflictException;
import com.financeassistant.controller.AgentController;
import com.financeassistant.model.CategoryRule;
import com.financeassistant.model.Transaction;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/** F02 + F03: transactions with their categories, spending by category, and fixing a category. */
class TransactionsPanel extends JPanel implements Refreshable {
    static final String ALL = "All";

    private final AgentController controller;
    private final JComboBox<String> month = new JComboBox<>();
    private final JComboBox<String> category = new JComboBox<>();
    private final DefaultTableModel rows = Ui.model("ID", "Date", "Amount", "Description", "Category", "Set by");
    private final DefaultTableModel summary = Ui.model("Category", "Spent", "Share", "Count");
    private final JTable table = Ui.table(rows);
    private final JLabel count = new JLabel();
    private boolean loading;

    TransactionsPanel(AgentController controller) {
        super(new BorderLayout());
        this.controller = controller;

        JButton edit = new JButton("Edit category...");
        edit.addActionListener(e -> editSelected());
        JButton ai = new JButton("Categorize uncategorized with AI");
        ai.addActionListener(e -> runAi());
        JButton rules = new JButton("Rules...");
        rules.addActionListener(e -> showRules());
        month.addActionListener(e -> reload());
        category.addActionListener(e -> reload());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editSelected();
                }
            }
        });
        Ui.widths(table, 50, 90, 80, 300, 110);

        add(Ui.row("Month:", month, "Category:", category, edit, ai, rules, count), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                Ui.titled(table, "Transactions (double-click to change a category)"),
                Ui.titled(Ui.table(summary), "Spending by category"));
        split.setResizeWeight(0.72);
        add(split, BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        loading = true;
        List<String> months = new ArrayList<>(List.of(ALL));
        months.addAll(controller.getMonths());
        Ui.fill(month, months, controller.getMonths().isEmpty() ? ALL : controller.getDefaultMonth());
        List<String> categories = new ArrayList<>(List.of(ALL));
        categories.addAll(controller.getAllowedCategories());
        Ui.fill(category, categories, ALL);
        loading = false;
        reload();
    }

    private void reload() {
        if (loading) {
            return;
        }
        String m = filter(month);
        List<Transaction> list = controller.getTransactions(m, filter(category));
        rows.setRowCount(0);
        for (Transaction t : list) {
            rows.addRow(new Object[]{t.getId(), t.getDate(), Ui.money(t.getAmount()), t.getDescription(), t.getCategory(),
                    t.getCategorySource().name().toLowerCase(Locale.ROOT)});
        }
        count.setText(list.size() + " transaction(s)");
        summary.setRowCount(0);
        for (CategorySummary s : controller.getCategorySummaries(m)) {
            summary.addRow(new Object[]{s.category(), Ui.money(s.total()), String.format(Locale.ROOT, "%.1f%%", s.percent()), s.count()});
        }
    }

    private static String filter(JComboBox<String> box) {
        String value = Ui.selected(box);
        return value == null || value.equals(ALL) ? null : value;
    }

    void editSelected() {
        int row = Ui.selectedRow(table);
        if (row < 0) {
            Ui.error(this, "Select a transaction first.");
            return;
        }
        int id = (Integer) rows.getValueAt(row, 0);
        String description = String.valueOf(rows.getValueAt(row, 3));
        JComboBox<String> choice = new JComboBox<>(controller.getAllowedCategories().toArray(new String[0]));
        choice.setSelectedItem(rows.getValueAt(row, 4));
        JCheckBox similar = new JCheckBox("Apply to similar transactions (save a rule)");
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel(description));
        form.add(choice);
        form.add(similar);
        if (JOptionPane.showConfirmDialog(this, form, "Edit category", JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) {
            return; // cancelled: nothing changes
        }
        recategorize(id, Ui.selected(choice), similar.isSelected());
    }

    private void recategorize(int id, String newCategory, boolean similar) {
        RecategorizeResult result;
        try {
            result = controller.recategorize(id, newCategory, similar, false);
        } catch (RuleConflictException e) {
            int answer = JOptionPane.showConfirmDialog(this, e.getMessage()
                            + "\n\nYes = replace the rule, No = keep the existing rule and only change this transaction",
                    "Rule conflict", JOptionPane.YES_NO_CANCEL_OPTION);
            if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
                return;
            }
            try {
                result = controller.recategorize(id, newCategory, answer == JOptionPane.YES_OPTION, true);
            } catch (RuleConflictException impossible) {
                Ui.error(this, impossible.getMessage());
                return;
            }
        } catch (IllegalArgumentException e) {
            Ui.error(this, e.getMessage());
            return;
        }
        reload();
        if (result.rule() != null) {
            Ui.info(this, "Saved rule " + result.rule() + ".\n" + result.similarUpdated() + " similar transaction(s) updated.");
        }
    }

    private void runAi() {
        Ui.background(this, controller::categorizeTransactions, (CategorizationResult r) -> {
            reload();
            Ui.info(this, r.summary());
        });
    }

    private void showRules() {
        List<CategoryRule> rules = controller.getRules();
        if (rules.isEmpty()) {
            Ui.info(this, "No rules yet. Edit a transaction's category and tick \"Apply to similar transactions\".");
            return;
        }
        JList<CategoryRule> list = new JList<>(rules.toArray(new CategoryRule[0]));
        Object[] options = {"Delete selected rule", "Close"};
        int answer = JOptionPane.showOptionDialog(this, new JScrollPane(list), "Category rules", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[1]);
        if (answer == 0 && list.getSelectedValue() != null) {
            controller.deleteRule(list.getSelectedValue().getId());
            showRules();
        }
    }
}
