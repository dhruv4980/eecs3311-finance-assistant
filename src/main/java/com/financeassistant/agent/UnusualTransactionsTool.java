package com.financeassistant.agent;

import com.financeassistant.report.ReportService;
import com.financeassistant.report.UnusualTransaction;
import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Charges in a month that are far above the user's usual spending in that category. */
public class UnusualTransactionsTool implements Tool {
    private final ReportService reportService;
    private final Gson gson = new Gson();

    public UnusualTransactionsTool(ReportService reportService) {
        this.reportService = reportService;
    }

    @Override
    public String getName() {
        return "find_unusual_transactions";
    }

    @Override
    public String getDescription() {
        return "Charges in a month that are much larger than the user's typical charge in the same category, with the reason.";
    }

    @Override
    public String getParameters() {
        return "month (required, YYYY-MM)";
    }

    @Override
    public String execute(Map<String, Object> args) {
        String month = ToolArgs.month(args, "month");
        if (month == null) {
            throw new IllegalArgumentException("month is required");
        }
        List<UnusualTransaction> unusual = reportService.findUnusualTransactions(month);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month);
        result.put("unusual", unusual);
        if (unusual.isEmpty()) {
            result.put("note", "Nothing unusual found (or not enough history in a category to judge).");
        }
        return gson.toJson(result);
    }
}
