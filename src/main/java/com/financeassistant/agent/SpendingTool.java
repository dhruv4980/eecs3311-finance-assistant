package com.financeassistant.agent;

import com.financeassistant.categorization.CategorizationService;
import com.financeassistant.categorization.CategorySummary;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.model.Transaction;
import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Income, spending and the per-category breakdown for a month (or all time). */
public class SpendingTool implements Tool {
    static final String TRANSFERS = "Transfers";

    private final CategorizationService categorizationService;
    private final DatabaseManager db = DatabaseManager.getInstance();
    private final Gson gson = new Gson();

    public SpendingTool(CategorizationService categorizationService) {
        this.categorizationService = categorizationService;
    }

    @Override
    public String getName() {
        return "get_spending";
    }

    @Override
    public String getDescription() {
        return "Total income, total spending and spending per category (with % of spending) for a month or all time.";
    }

    @Override
    public String getParameters() {
        return "month (optional, YYYY-MM; omit for all time)";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String month = ToolArgs.month(args, "month");
        double income = 0;
        int count = 0;
        for (Transaction t : db.find(Transaction.class, x -> month == null || month.equals(x.getMonth()))) {
            count++;
            if (!t.isExpense() && !TRANSFERS.equals(t.getCategory())) {
                income += t.getAmount();
            }
        }
        List<CategorySummary> categories = categorizationService.getCategorySummaries(month);
        double spending = categories.stream().mapToDouble(CategorySummary::total).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", month == null ? "all time" : month);
        result.put("transactionCount", count);
        result.put("totalIncome", round(income));
        result.put("totalSpending", round(spending));
        result.put("net", round(income - spending));
        result.put("spendingByCategory", categories);
        result.put("note", "Income excludes the Transfers category (e.g. credit-card payments).");
        return gson.toJson(result);
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
