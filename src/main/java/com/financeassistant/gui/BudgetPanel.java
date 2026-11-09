package com.financeassistant.gui;

import com.financeassistant.budget.BudgetExistsException;
import com.financeassistant.budget.BudgetStatus;
import com.financeassistant.controller.AgentController;
import com.financeassistant.model.Category;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** F04 + F05: set monthly budgets per category and see how each is doing. */
class BudgetPanel extends JPanel implements Refreshable {
    private final AgentController controller;
    private final JComboBox<String> month = new JComboBox<>();
    private final JComboBox<String> category = new JComboBox<>();
    private final JTextField amount = new JTextField(8);
    private final DefaultTableModel rows = Ui.model("Category", "Budget", "Spent", "Left", "Used", "Status");
    private final JTable table = Ui.table(rows);
    private final JLabel empty = new JLabel();
    private boolean loading;

    BudgetPanel(AgentController controller) {
        super(new BorderLayout());
        this.controller = controller;
        month.setEditable(true); // budgets can be set for future months too
        month.addActionListener(e -> reload());
        JButton save = new JButton("Save budget");
        save.addActionListener(e -> save());
        JButton delete = new JButton("Delete selected");
        delete.addActionListener(e -> deleteSelected());
        table.setDefaultRenderer(Object.class, new LevelRenderer());

        add(Ui.row("Month (YYYY-MM):", month, "Category:", category, "Amount $:", amount, save, delete, empty), BorderLayout.NORTH);
        add(Ui.titled(table, "Budget status - amber at 80%, red when over"), BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        loading = true;
        List<String> months = new ArrayList<>(controller.getMonths());
        String next = YearMonth.now().plusMonths(1).toString();
        if (!months.contains(YearMonth.now().toString())) {
            months.add(0, YearMonth.now().toString());
        }
        if (!months.contains(next)) {
            months.add(0, next);
        }
        Ui.fill(month, months, controller.getDefaultMonth());
        List<String> spending = controller.getAllowedCategories().stream()
                .filter(c -> !c.equals(Category.INCOME) && !c.equals(Category.UNCATEGORIZED)).toList();
        Ui.fill(category, spending, "Dining");
        loading = false;
        reload();
    }

    private void reload() {
        if (loading) {
            return;
        }
        rows.setRowCount(0);
        String m = Ui.selected(month);
        List<BudgetStatus> status;
        try {
            status = controller.getBudgetStatus(m);
        } catch (IllegalArgumentException e) {
            empty.setText(e.getMessage());
            return;
        }
        for (BudgetStatus s : status) {
            rows.addRow(new Object[]{s.category(), Ui.money(s.limit()), Ui.money(s.spent()), Ui.money(s.remaining()),
                    String.format(Locale.ROOT, "%.1f%%", s.percentUsed()), s.level().name()});
        }
        empty.setText(status.isEmpty() ? "No budgets for " + m + " yet - add one." : "");
    }

    private void save() {
        String m = Ui.selected(month);
        try {
            double value = Ui.amount(amount.getText());
            try {
                controller.setBudget(m, Ui.selected(category), value, false);
            } catch (BudgetExistsException e) {
                if (!Ui.confirm(this, e.getMessage())) {
                    return;
                }
                controller.setBudget(m, Ui.selected(category), value, true);
            }
            amount.setText("");
            reload();
        } catch (IllegalArgumentException e) {
            Ui.error(this, e.getMessage());
        } catch (BudgetExistsException impossible) {
            Ui.error(this, impossible.getMessage());
        }
    }

    private void deleteSelected() {
        int row = Ui.selectedRow(table);
        if (row < 0) {
            Ui.error(this, "Select a budget first.");
            return;
        }
        String cat = String.valueOf(rows.getValueAt(row, 0));
        if (Ui.confirm(this, "Delete the " + cat + " budget for " + Ui.selected(month) + "?")) {
            controller.deleteBudget(Ui.selected(month), cat);
            reload();
        }
    }

    /** Colours a row by its status: amber for WARNING, red for EXCEEDED. */
    private static final class LevelRenderer extends DefaultTableCellRenderer {
        private static final Color WARNING = new Color(255, 235, 190);
        private static final Color EXCEEDED = new Color(255, 205, 200);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                Object level = table.getValueAt(row, 5);
                c.setBackground("EXCEEDED".equals(level) ? EXCEEDED : "WARNING".equals(level) ? WARNING : Color.WHITE);
            }
            return c;
        }
    }
}
