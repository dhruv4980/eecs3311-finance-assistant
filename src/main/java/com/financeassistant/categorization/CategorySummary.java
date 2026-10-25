package com.financeassistant.categorization;

/** Spending in one category: total spent (a positive number), how many transactions, share of all spending. */
public record CategorySummary(String category, double total, int count, double percent) {
}
