package com.financeassistant.report;

/** Spending in one category this month compared with the month before. */
public record CategoryChange(String category, double thisMonth, double lastMonth, double change) {
}
