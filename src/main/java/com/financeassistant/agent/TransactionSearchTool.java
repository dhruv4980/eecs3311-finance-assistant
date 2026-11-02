package com.financeassistant.agent;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.model.Transaction;
import com.google.gson.Gson;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Finds individual transactions by month, category or merchant text, newest or largest first. */
public class TransactionSearchTool implements Tool {
    private final DatabaseManager db = DatabaseManager.getInstance();
    private final Gson gson = new Gson();

    @Override
    public String getName() {
        return "search_transactions";
    }

    @Override
    public String getDescription() {
        return "List individual transactions, filtered by month, category and/or text in the description. "
                + "Also returns the count and total of all matches (not just the ones listed).";
    }

    @Override
    public String getParameters() {
        return "month (optional, YYYY-MM), category (optional), text (optional, e.g. a merchant name), "
                + "sort (optional: \"date\" newest first [default] or \"amount\" largest spending first), limit (optional, default 15, max 50)";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String month = ToolArgs.month(args, "month");
        String category = ToolArgs.text(args, "category");
        String text = ToolArgs.text(args, "text");
        String sort = ToolArgs.text(args, "sort");
        int limit = ToolArgs.integer(args, "limit", 15, 1, 50);
        String needle = text == null ? null : text.toUpperCase(Locale.ROOT);

        List<Transaction> matches = db.find(Transaction.class, t ->
                (month == null || month.equals(t.getMonth()))
                        && (category == null || category.equalsIgnoreCase(t.getCategory()))
                        && (needle == null || t.getDescription().toUpperCase(Locale.ROOT).contains(needle)));
        Comparator<Transaction> order = "amount".equalsIgnoreCase(sort)
                ? Comparator.comparingDouble(Transaction::getAmount)
                : Comparator.comparing(Transaction::getDate).reversed();
        double total = matches.stream().mapToDouble(Transaction::getAmount).sum();

        Stream<Map<String, Object>> rows = matches.stream().sorted(order).limit(limit).map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("date", t.getDate().toString());
            row.put("amount", t.getAmount());
            row.put("description", t.getDescription());
            row.put("category", t.getCategory());
            return row;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchCount", matches.size());
        result.put("matchTotal", SpendingTool.round(total));
        result.put("amountSign", "negative = money spent, positive = money received");
        result.put("transactions", rows.toList());
        return gson.toJson(result);
    }
}
