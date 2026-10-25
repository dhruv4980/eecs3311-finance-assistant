package com.financeassistant.categorization;

import com.financeassistant.model.CategoryRule;
import com.financeassistant.model.Transaction;

/** Result of a manual correction: the updated transaction, the rule saved (or null) and how many similar ones changed. */
public record RecategorizeResult(Transaction transaction, CategoryRule rule, int similarUpdated) {
}
