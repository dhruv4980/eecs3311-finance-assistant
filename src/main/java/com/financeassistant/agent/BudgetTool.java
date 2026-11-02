package com.financeassistant.agent;

import com.financeassistant.budget.BudgetService;
import com.financeassistant.budget.BudgetStatus;
import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** How each category is doing against its budget for a month. */
public class BudgetTool implements Tool {
    private final BudgetService budgetService;
    private final Gson gson = new Gson();

    public BudgetTool(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @Override
    public String getName() {
        return "get_budget_status";
    }

    @Override
    public String getDescription() {
        return "Budgets for a month: limit, amount spent, amount remaining, % used and level (OK, WARNING at 80%, EXCEEDED).";
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
        List<BudgetStatus> status = budgetService.getStatus(month);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month);
        result.put("budgets", status);
        if (status.isEmpty()) {
            result.put("note", "The user has not set any budgets for this month.");
        }
        return gson.toJson(result);
    }
}
