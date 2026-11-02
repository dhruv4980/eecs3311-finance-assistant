package com.financeassistant.export;

import com.financeassistant.budget.BudgetService;
import com.financeassistant.budget.BudgetStatus;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.model.Transaction;
import com.financeassistant.report.CategoryChange;
import com.financeassistant.report.MonthlyReport;
import com.financeassistant.report.UnusualTransaction;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * F10 Report export (deterministic). Builds the chosen report as an
 * ExportDocument, then hands it to the ReportExporter strategy for the chosen
 * format. Report types: monthly (the F09 report), transactions, budget.
 */
public class ExportService {
    public static final List<String> REPORT_TYPES = List.of("monthly", "transactions", "budget");

    private final Map<String, ReportExporter> exporters = new LinkedHashMap<>();
    private final BudgetService budgetService;
    private final Function<String, MonthlyReport> monthlyReports;
    private final DatabaseManager db = DatabaseManager.getInstance();

    /**
     * @param monthlyReports gives the monthly report for a month (the saved one, or a newly generated one)
     */
    public ExportService(BudgetService budgetService, Function<String, MonthlyReport> monthlyReports) {
        this.budgetService = budgetService;
        this.monthlyReports = monthlyReports;
        register(new PdfExporter());
        register(new CsvExporter());
        register(new MarkdownExporter());
    }

    public final void register(ReportExporter exporter) {
        exporters.put(exporter.getFormat(), exporter);
    }

    public List<String> getFormats() {
        return List.copyOf(exporters.keySet());
    }

    /**
     * Writes the report and returns where it went. The extension is added if missing.
     *
     * @throws IllegalArgumentException  for an unknown type/format, a bad month or a folder that does not exist
     * @throws ExportFileExistsException if the file exists and overwrite is false
     */
    public Path exportReport(String type, String month, String format, Path path, boolean overwrite)
            throws ExportFileExistsException, IOException {
        ReportExporter exporter = exporters.get(format == null ? "" : format.strip().toLowerCase(Locale.ROOT).replace(".", ""));
        if (exporter == null) {
            throw new IllegalArgumentException("Unknown format '" + format + "'. Choose " + String.join(", ", getFormats()) + ".");
        }
        if (path != null && Files.isDirectory(path)) {
            // a folder was chosen: save inside it with a default name, e.g. monthly-2026-09.pdf
            path = path.resolve((type == null ? "report" : type.strip().toLowerCase(Locale.ROOT)) + "-" + month);
        }
        Path target = withExtension(path, exporter.getExtension());
        Path folder = target.toAbsolutePath().getParent();
        if (folder == null || !Files.isDirectory(folder)) {
            throw new IllegalArgumentException("The folder " + folder + " does not exist. Choose another location.");
        }
        if (Files.isDirectory(target)) {
            throw new IllegalArgumentException(target + " is a folder - add a file name.");
        }
        if (Files.exists(target) && !overwrite) {
            throw new ExportFileExistsException(target);
        }
        ExportDocument document = buildDocument(type, BudgetService.validateMonth(month));

        // write next to the target first so a failed export never leaves a half-written file behind
        // (not Files.createTempFile: that makes the file private to its owner)
        Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                exporter.export(document, out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target.toAbsolutePath();
    }

    ExportDocument buildDocument(String type, String month) {
        String t = type == null ? "" : type.strip().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "monthly" -> monthlyDocument(monthlyReports.apply(month));
            case "transactions" -> transactionsDocument(month);
            case "budget" -> budgetDocument(month);
            default -> throw new IllegalArgumentException("Unknown report type '" + type + "'. Choose "
                    + String.join(", ", REPORT_TYPES) + ".");
        };
    }

    private ExportDocument monthlyDocument(MonthlyReport r) {
        ExportDocument doc = new ExportDocument("Monthly report - " + r.getMonth(), generated());
        doc.addSection("Summary")
                .paragraph(money("Income", r.getTotalIncome()) + "   " + money("Spending", r.getTotalSpending())
                        + "   " + money("Net", r.getNet()) + "   Transactions: " + r.getTransactionCount())
                .paragraph(r.getSummary() + (r.isAiSummary() ? " (Written by AI from the figures in this report.)" : ""));
        ExportDocument.Section categories = doc.addSection("Spending by category").table("Category", "Amount", "Share", "Charges");
        r.getCategoryBreakdown().forEach(c -> categories.row(c.category(), amount(c.total()),
                String.format(Locale.ROOT, "%.1f%%", c.percent()), String.valueOf(c.count())));
        if (!r.getChangesFromLastMonth().isEmpty()) {
            ExportDocument.Section changes = doc.addSection("Change from last month").table("Category", "Last month", "This month", "Change");
            for (CategoryChange c : r.getChangesFromLastMonth()) {
                changes.row(c.category(), amount(c.lastMonth()), amount(c.thisMonth()), String.format(Locale.ROOT, "%+.2f", c.change()));
            }
        }
        if (!r.getBudgetStatus().isEmpty()) {
            addBudgetTable(doc.addSection("Budgets"), r.getBudgetStatus());
        }
        if (!r.getUnusualTransactions().isEmpty()) {
            ExportDocument.Section unusual = doc.addSection("Unusual charges").table("Date", "Description", "Amount", "Why");
            for (UnusualTransaction u : r.getUnusualTransactions()) {
                unusual.row(u.date(), u.description(), amount(u.amount()), u.reason());
            }
        }
        if (!r.getSubscriptionChanges().isEmpty()) {
            ExportDocument.Section subs = doc.addSection("Subscription changes");
            r.getSubscriptionChanges().forEach(subs::paragraph);
        }
        return doc;
    }

    private ExportDocument transactionsDocument(String month) {
        List<Transaction> list = db.find(Transaction.class, t -> month.equals(t.getMonth())).stream()
                .sorted(Comparator.comparing(Transaction::getDate).thenComparing(Transaction::getId)).toList();
        if (list.isEmpty()) {
            throw new IllegalArgumentException("There are no transactions in " + month + " to export.");
        }
        ExportDocument doc = new ExportDocument("Transactions - " + month, generated() + " - " + list.size() + " transactions");
        ExportDocument.Section section = doc.addSection("Transactions").table("Date", "Description", "Category", "Amount");
        list.forEach(t -> section.row(t.getDate().toString(), t.getDescription(), t.getCategory(), amount(t.getAmount())));
        return doc;
    }

    private ExportDocument budgetDocument(String month) {
        List<BudgetStatus> status = budgetService.getStatus(month);
        if (status.isEmpty()) {
            throw new IllegalArgumentException("No budgets are set for " + month + ".");
        }
        ExportDocument doc = new ExportDocument("Budget status - " + month, generated());
        addBudgetTable(doc.addSection("Budgets"), status);
        return doc;
    }

    private static void addBudgetTable(ExportDocument.Section section, List<BudgetStatus> status) {
        section.table("Category", "Budget", "Spent", "Remaining", "Used", "Status");
        status.forEach(b -> section.row(b.category(), amount(b.limit()), amount(b.spent()), amount(b.remaining()),
                String.format(Locale.ROOT, "%.1f%%", b.percentUsed()), b.level().name()));
    }

    private static Path withExtension(Path path, String extension) {
        if (path == null || path.getFileName() == null) {
            throw new IllegalArgumentException("Choose where to save the file.");
        }
        String name = path.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(extension) ? path : path.resolveSibling(name + extension);
    }

    private static String generated() {
        return "AI Personal Finance Assistant - generated " + LocalDate.now();
    }

    private static String amount(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String money(String label, double value) {
        return String.format(Locale.ROOT, "%s: $%.2f", label, value);
    }
}
