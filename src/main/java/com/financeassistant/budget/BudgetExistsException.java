package com.financeassistant.budget;

import com.financeassistant.model.Budget;

/** A budget already exists for that month and category; the user chooses to overwrite or cancel. */
public class BudgetExistsException extends Exception {
    private final Budget existing;

    public BudgetExistsException(Budget existing) {
        super(String.format("A %s budget of $%.2f already exists for %s. Overwrite it?",
                existing.getCategory(), existing.getAmount(), existing.getMonth()));
        this.existing = existing;
    }

    public Budget getExisting() {
        return existing;
    }
}
