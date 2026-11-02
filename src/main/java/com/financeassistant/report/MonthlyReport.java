package com.financeassistant.report;

import com.financeassistant.budget.BudgetStatus;
import com.financeassistant.categorization.CategorySummary;
import com.financeassistant.model.Entity;
import java.util.List;

/**
 * F09 monthly report. All figures are calculated by code; only
 * {@code summary} is written by Claude (or by a template when AI is unavailable).
 */
public class MonthlyReport implements Entity {
    private int id;
    private String month;
    private String generatedAt;
    private int transactionCount;
    private double totalIncome;
    private double totalSpending;
    private double net;
    private List<CategorySummary> categoryBreakdown;
    private List<CategoryChange> changesFromLastMonth;
    private List<BudgetStatus> budgetStatus;
    private List<UnusualTransaction> unusualTransactions;
    private List<String> subscriptionChanges;
    private String summary;
    private boolean aiSummary;

    public MonthlyReport() {
    }

    MonthlyReport(String month, String generatedAt, int transactionCount, double totalIncome, double totalSpending,
                  List<CategorySummary> categoryBreakdown, List<CategoryChange> changesFromLastMonth,
                  List<BudgetStatus> budgetStatus, List<UnusualTransaction> unusualTransactions,
                  List<String> subscriptionChanges) {
        this.month = month;
        this.generatedAt = generatedAt;
        this.transactionCount = transactionCount;
        this.totalIncome = totalIncome;
        this.totalSpending = totalSpending;
        this.net = Math.round((totalIncome - totalSpending) * 100) / 100.0;
        this.categoryBreakdown = categoryBreakdown;
        this.changesFromLastMonth = changesFromLastMonth;
        this.budgetStatus = budgetStatus;
        this.unusualTransactions = unusualTransactions;
        this.subscriptionChanges = subscriptionChanges;
    }

    void setSummary(String summary, boolean aiSummary) {
        this.summary = summary;
        this.aiSummary = aiSummary;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void setId(int id) {
        this.id = id;
    }

    public String getMonth() {
        return month;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public int getTransactionCount() {
        return transactionCount;
    }

    public double getTotalIncome() {
        return totalIncome;
    }

    public double getTotalSpending() {
        return totalSpending;
    }

    public double getNet() {
        return net;
    }

    public List<CategorySummary> getCategoryBreakdown() {
        return categoryBreakdown;
    }

    public List<CategoryChange> getChangesFromLastMonth() {
        return changesFromLastMonth;
    }

    public List<BudgetStatus> getBudgetStatus() {
        return budgetStatus;
    }

    public List<UnusualTransaction> getUnusualTransactions() {
        return unusualTransactions;
    }

    public List<String> getSubscriptionChanges() {
        return subscriptionChanges;
    }

    public String getSummary() {
        return summary;
    }

    public boolean isAiSummary() {
        return aiSummary;
    }
}
