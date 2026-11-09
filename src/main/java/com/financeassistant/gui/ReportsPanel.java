package com.financeassistant.gui;

import com.financeassistant.budget.BudgetStatus;
import com.financeassistant.categorization.CategorySummary;
import com.financeassistant.controller.AgentController;
import com.financeassistant.export.ExportFileExistsException;
import com.financeassistant.report.CategoryChange;
import com.financeassistant.report.MonthlyReport;
import com.financeassistant.report.UnusualTransaction;
import java.awt.BorderLayout;
import java.awt.Font;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextArea;

/** F09 + F10: generate a month's report (AI summary) and export reports to a file. */
class ReportsPanel extends JPanel implements Refreshable {
    private final AgentController controller;
    private final JComboBox<String> month = new JComboBox<>();
    private final JComboBox<String> type = new JComboBox<>(new String[]{"monthly", "transactions", "budget"});
    private final JComboBox<String> format = new JComboBox<>();
    private final JTextArea report = Ui.textArea(25);

    ReportsPanel(AgentController controller) {
        super(new BorderLayout());
        this.controller = controller;
        controller.getExportFormats().forEach(format::addItem);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        report.setLineWrap(false);
        JButton generate = new JButton("Generate report");
        generate.addActionListener(e -> generate());
        JButton export = new JButton("Export...");
        export.addActionListener(e -> export());
        add(Ui.row("Month:", month, generate, "   Export:", type, format, export), BorderLayout.NORTH);
        add(Ui.titled(report, "Monthly report"), BorderLayout.CENTER);
    }

    @Override
    public void refresh() {
        Ui.fill(month, controller.getMonths(), controller.getDefaultMonth());
    }

    void generate() {
        String m = Ui.selected(month);
        if (m == null) {
            Ui.error(this, "Import a statement first - there are no months with data.");
            return;
        }
        report.setText("Generating the " + m + " report (the AI writes the summary)...");
        Ui.background(this, () -> controller.generateMonthlyReport(m), (MonthlyReport r) -> {
            report.setText(format(r));
            report.setCaretPosition(0);
        });
    }

    private void export() {
        String m = Ui.selected(month);
        String t = Ui.selected(type);
        String f = Ui.selected(format);
        if (m == null) {
            Ui.error(this, "There is no data to export yet.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(t + "-" + m + "." + f));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        Ui.background(this, () -> {
            try {
                return controller.exportReport(t, m, f, target, false);
            } catch (ExportFileExistsException e) {
                return null; // ask on the Swing thread below
            }
        }, written -> {
            if (written != null) {
                Ui.info(this, "Saved to " + written);
            } else if (Ui.confirm(this, target.getFileName() + " already exists. Overwrite it?")) {
                Ui.background(this, () -> controller.exportReport(t, m, f, target, true),
                        path -> Ui.info(this, "Saved to " + path));
            }
        });
    }

    /** Lays the report out as plain text. */
    static String format(MonthlyReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Monthly report - ").append(r.getMonth()).append("\n\n");
        sb.append(String.format(Locale.ROOT, "Income   $%10.2f%nSpending $%10.2f%nNet      $%10.2f   (%d transactions)%n%n",
                r.getTotalIncome(), r.getTotalSpending(), r.getNet(), r.getTransactionCount()));
        sb.append(r.isAiSummary() ? "Summary (written by AI from the figures below):\n" : "Summary:\n");
        sb.append(r.getSummary()).append("\n\n");
        sb.append("Spending by category\n");
        for (CategorySummary c : r.getCategoryBreakdown()) {
            sb.append(String.format(Locale.ROOT, "  %-15s %10.2f  %5.1f%%%n", c.category(), c.total(), c.percent()));
        }
        section(sb, "Change from last month", r.getChangesFromLastMonth(), c -> String.format(Locale.ROOT,
                "  %-15s %+10.2f  (%.2f -> %.2f)", c.category(), c.change(), c.lastMonth(), c.thisMonth()));
        section(sb, "Budgets", r.getBudgetStatus(), b -> String.format(Locale.ROOT, "  %-15s %8.2f of %8.2f  %5.1f%% %s",
                b.category(), b.spent(), b.limit(), b.percentUsed(), b.level() == BudgetStatus.Level.OK ? "" : b.level().name()));
        section(sb, "Unusual charges", r.getUnusualTransactions(), u -> String.format(Locale.ROOT, "  %s  %-32s %9.2f  %s",
                u.date(), u.description(), u.amount(), u.reason()));
        section(sb, "Subscription changes", r.getSubscriptionChanges(), s -> "  " + s);
        return sb.toString();
    }

    private static <T> void section(StringBuilder sb, String title, List<T> items, java.util.function.Function<T, String> line) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append('\n');
        items.forEach(i -> sb.append(line.apply(i)).append('\n'));
    }
}
