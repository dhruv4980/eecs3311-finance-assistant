package com.financeassistant.report;

/** A charge much larger than the user's usual spending in that category, with the reason it was flagged. */
public record UnusualTransaction(int id, String date, String description, String category, double amount,
                                 double usualAmount, String reason) {
}
