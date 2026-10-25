package com.financeassistant.budget;

/** How one category is doing against its budget this month. percentUsed is 0-100+. */
public record BudgetStatus(String category, double limit, double spent, double remaining,
                           double percentUsed, Level level) {

    public enum Level { OK, WARNING, EXCEEDED }
}
