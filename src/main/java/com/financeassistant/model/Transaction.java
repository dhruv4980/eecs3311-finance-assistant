package com.financeassistant.model;

import java.time.LocalDate;
import java.util.Locale;

/**
 * One row of a bank statement. Spending is stored as a negative amount and
 * income (deposits, refunds) as a positive amount.
 */
public class Transaction implements Entity {
    private int id;
    private int accountId;
    private LocalDate date;
    private double amount;
    private String description;
    private String category = Category.UNCATEGORIZED;
    private CategorySource categorySource = CategorySource.NONE;

    public Transaction() {
    }

    public Transaction(LocalDate date, double amount, String description) {
        this.date = date;
        this.amount = amount;
        this.description = description == null ? null : description.strip();
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void setId(int id) {
        this.id = id;
    }

    public int getAccountId() {
        return accountId;
    }

    public void setAccountId(int accountId) {
        this.accountId = accountId;
    }

    public LocalDate getDate() {
        return date;
    }

    public double getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public CategorySource getCategorySource() {
        return categorySource;
    }

    public void setCategory(String category, CategorySource source) {
        this.category = category;
        this.categorySource = source;
    }

    public boolean isExpense() {
        return amount < 0;
    }

    public boolean isUncategorized() {
        return category == null || Category.UNCATEGORIZED.equals(category);
    }

    /** Month in YYYY-MM form, used for budgets and reports. */
    public String getMonth() {
        return date == null ? null : date.toString().substring(0, 7);
    }

    /**
     * Key used to detect duplicates: same account, date, amount (to the cent)
     * and description (ignoring case and spacing).
     */
    public String duplicateKey() {
        String desc = description == null ? "" : description.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return accountId + "|" + date + "|" + Math.round(amount * 100) + "|" + desc;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "#%d %s %10.2f %-30s [%s]", id, date, amount, description, category);
    }
}
