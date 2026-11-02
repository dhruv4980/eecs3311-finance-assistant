package com.financeassistant.report;

import com.financeassistant.budget.BudgetService;
import com.financeassistant.budget.BudgetStatus;
import com.financeassistant.categorization.CategorizationService;
import com.financeassistant.categorization.CategorySummary;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.LLMClient;
import com.financeassistant.llm.LLMException;
import com.financeassistant.model.Category;
import com.financeassistant.model.Subscription;
import com.financeassistant.model.Transaction;
import com.financeassistant.subscription.SubscriptionService;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * F09 Monthly report generation (hybrid): the figures are computed here,
 * then Claude turns them into a plain-language summary with saving advice.
 * If Claude is unavailable a template summary is used, so a report is
 * always produced.
 */
public class ReportService {
    static final String TRANSFERS = "Transfers";
    /** A charge is unusual if it is at least this many times the category's typical charge... */
    static final double UNUSUAL_MULTIPLE = 3.0;
    /** ...and at least this many dollars above it (so $3 vs $1 coffees are not flagged). */
    static final double UNUSUAL_MIN_EXTRA = 40;
    static final int UNUSUAL_MIN_HISTORY = 5;

    private static final String SYSTEM_PROMPT = """
            You write a short monthly money summary for a personal budgeting app.
            Use ONLY the figures provided - never invent or estimate numbers.
            Write 4-7 sentences in plain language: how the month went overall, the biggest spending areas,
            notable changes from last month, any budgets exceeded or unusual charges, and 1-2 specific,
            realistic ways to save next month. Use $ amounts with 2 decimals. No headings, no bullet points.""";

    private final DatabaseManager db = DatabaseManager.getInstance();
    private final LLMClient llm;
    private final CategorizationService categorizationService;
    private final BudgetService budgetService;
    private final SubscriptionService subscriptionService;

    public ReportService(LLMClient llm, CategorizationService categorizationService, BudgetService budgetService,
                         SubscriptionService subscriptionService) {
        this.llm = llm;
        this.categorizationService = categorizationService;
        this.budgetService = budgetService;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Builds, saves and returns the report for a month (YYYY-MM). Replaces an
     * earlier report for the same month.
     *
     * @throws IllegalArgumentException if the month has no transactions
     */
    public MonthlyReport generateMonthlyReport(String month) {
        String m = BudgetService.validateMonth(month);
        List<Transaction> transactions = db.find(Transaction.class, t -> m.equals(t.getMonth()));
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("There are no transactions in " + m + " - import a statement for that month first.");
        }
        double income = transactions.stream()
                .filter(t -> !t.isExpense() && !TRANSFERS.equals(t.getCategory()))
                .mapToDouble(Transaction::getAmount).sum();
        List<CategorySummary> breakdown = categorizationService.getCategorySummaries(m);
        double spending = breakdown.stream().mapToDouble(CategorySummary::total).sum();

        MonthlyReport report = new MonthlyReport(m, LocalDateTime.now().withNano(0).toString(), transactions.size(),
                round(income), round(spending), breakdown, compareWithPreviousMonth(m, breakdown),
                budgetService.getStatus(m), findUnusualTransactions(m), subscriptionChanges(m));
        try {
            report.setSummary(writeSummary(report), true);
        } catch (LLMException e) {
            report.setSummary(templateSummary(report), false);
        }
        for (MonthlyReport old : db.find(MonthlyReport.class, r -> m.equals(r.getMonth()))) {
            db.delete(MonthlyReport.class, old.getId());
        }
        return db.save(report);
    }

    /** The saved report for a month, if one was generated. */
    public MonthlyReport getReport(String month) {
        String m = BudgetService.validateMonth(month);
        return db.find(MonthlyReport.class, r -> m.equals(r.getMonth())).stream().findFirst().orElse(null);
    }

    public List<MonthlyReport> getReports() {
        return db.findAll(MonthlyReport.class).stream()
                .sorted(Comparator.comparing(MonthlyReport::getMonth).reversed()).toList();
    }

    // ------------------------------------------------------------ facts

    /**
     * Charges in the month that are far above what the user usually spends in
     * that category: at least 3x the typical (median) charge and $40 more.
     * Needs some history in the category to judge what "usual" is.
     */
    public List<UnusualTransaction> findUnusualTransactions(String month) {
        Map<String, List<Transaction>> byCategory = new LinkedHashMap<>();
        for (Transaction t : db.find(Transaction.class, x -> x.isExpense() && !TRANSFERS.equals(x.getCategory())
                && !Category.UNCATEGORIZED.equals(x.getCategory()))) {
            byCategory.computeIfAbsent(t.getCategory(), k -> new ArrayList<>()).add(t);
        }
        List<UnusualTransaction> unusual = new ArrayList<>();
        for (List<Transaction> charges : byCategory.values()) {
            for (Transaction t : charges) {
                if (!month.equals(t.getMonth())) {
                    continue;
                }
                List<Double> others = charges.stream().filter(o -> o.getId() != t.getId()).map(o -> -o.getAmount()).toList();
                if (others.size() < UNUSUAL_MIN_HISTORY) {
                    continue;
                }
                double usual = median(others);
                double amount = -t.getAmount();
                if (amount >= usual * UNUSUAL_MULTIPLE && amount - usual >= UNUSUAL_MIN_EXTRA) {
                    unusual.add(new UnusualTransaction(t.getId(), t.getDate().toString(), t.getDescription(),
                            t.getCategory(), round(amount), round(usual), String.format(Locale.ROOT,
                            "%.1fx your typical %s charge of $%.2f", amount / usual, t.getCategory(), usual)));
                }
            }
        }
        unusual.sort(Comparator.comparingDouble(UnusualTransaction::amount).reversed());
        return unusual;
    }

    private List<CategoryChange> compareWithPreviousMonth(String month, List<CategorySummary> current) {
        String previous = YearMonth.parse(month).minusMonths(1).toString();
        Map<String, Double> before = new LinkedHashMap<>();
        categorizationService.getCategorySummaries(previous).forEach(s -> before.put(s.category(), s.total()));
        if (before.isEmpty()) {
            return List.of();
        }
        Map<String, Double> now = new LinkedHashMap<>();
        current.forEach(s -> now.put(s.category(), s.total()));
        Set<String> categories = new TreeSet<>(now.keySet());
        categories.addAll(before.keySet());
        List<CategoryChange> changes = new ArrayList<>();
        for (String c : categories) {
            double a = now.getOrDefault(c, 0.0);
            double b = before.getOrDefault(c, 0.0);
            changes.add(new CategoryChange(c, a, b, round(a - b)));
        }
        changes.sort(Comparator.comparingDouble((CategoryChange c) -> Math.abs(c.change())).reversed());
        return changes;
    }

    /** Subscriptions that started, rose in price or were last charged this month. */
    private List<String> subscriptionChanges(String month) {
        List<String> notes = new ArrayList<>();
        for (Subscription s : subscriptionService.getSubscriptions()) {
            if (s.getFirstDate() != null && month.equals(s.getFirstDate().toString().substring(0, 7))) {
                notes.add(String.format(Locale.ROOT, "New: %s at $%.2f", s.getName(), s.getAmount()));
            }
            if (s.isPriceIncreased() && s.getLastDate() != null && month.equals(s.getLastDate().toString().substring(0, 7))) {
                notes.add(String.format(Locale.ROOT, "Price increase: %s went from $%.2f to $%.2f",
                        s.getName(), s.getPreviousAmount(), s.getAmount()));
            }
        }
        return notes;
    }

    // ------------------------------------------------------------ summary text

    String writeSummary(MonthlyReport r) throws LLMException {
        return llm.generate(SYSTEM_PROMPT, facts(r));
    }

    /** The figures handed to Claude, as plain text. */
    static String facts(MonthlyReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Month: %s. Income: $%.2f. Spending: $%.2f. Net: $%.2f. Transactions: %d.%n",
                r.getMonth(), r.getTotalIncome(), r.getTotalSpending(), r.getNet(), r.getTransactionCount()));
        sb.append("Spending by category:\n");
        r.getCategoryBreakdown().forEach(c -> sb.append(String.format(Locale.ROOT, "- %s: $%.2f (%.1f%%, %d charges)%n",
                c.category(), c.total(), c.percent(), c.count())));
        if (!r.getChangesFromLastMonth().isEmpty()) {
            sb.append("Biggest changes from last month:\n");
            r.getChangesFromLastMonth().stream().limit(4).forEach(c -> sb.append(String.format(Locale.ROOT,
                    "- %s: $%.2f now vs $%.2f last month (%+.2f)%n", c.category(), c.thisMonth(), c.lastMonth(), c.change())));
        }
        if (!r.getBudgetStatus().isEmpty()) {
            sb.append("Budgets:\n");
            r.getBudgetStatus().forEach(b -> sb.append(String.format(Locale.ROOT, "- %s: spent $%.2f of $%.2f (%.0f%%, %s)%n",
                    b.category(), b.spent(), b.limit(), b.percentUsed(), b.level())));
        }
        if (!r.getUnusualTransactions().isEmpty()) {
            sb.append("Unusual charges:\n");
            r.getUnusualTransactions().forEach(u -> sb.append(String.format(Locale.ROOT, "- %s %s $%.2f: %s%n",
                    u.date(), u.description(), u.amount(), u.reason())));
        }
        if (!r.getSubscriptionChanges().isEmpty()) {
            sb.append("Subscription changes:\n");
            r.getSubscriptionChanges().forEach(s -> sb.append("- ").append(s).append('\n'));
        }
        return sb.toString();
    }

    /** Plain summary used when Claude is unavailable. */
    static String templateSummary(MonthlyReport r) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "In %s you received $%.2f and spent $%.2f, so you %s $%.2f.", r.getMonth(), r.getTotalIncome(),
                r.getTotalSpending(), r.getNet() >= 0 ? "kept" : "overspent by", Math.abs(r.getNet())));
        if (!r.getCategoryBreakdown().isEmpty()) {
            CategorySummary top = r.getCategoryBreakdown().get(0);
            sb.append(String.format(Locale.ROOT, " Your biggest spending area was %s at $%.2f (%.0f%% of spending).",
                    top.category(), top.total(), top.percent()));
        }
        long over = r.getBudgetStatus().stream().filter(b -> b.level() == BudgetStatus.Level.EXCEEDED).count();
        if (over > 0) {
            sb.append(" You went over ").append(over).append(over == 1 ? " budget." : " budgets.");
        }
        if (!r.getUnusualTransactions().isEmpty()) {
            sb.append(" ").append(r.getUnusualTransactions().size()).append(" unusual charge(s) were flagged - check they are expected.");
        }
        sb.append(" (AI summary unavailable; this is an automatic summary.)");
        return sb.toString();
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
