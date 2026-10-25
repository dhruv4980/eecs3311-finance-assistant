package com.financeassistant.categorization;

import com.financeassistant.model.CategoryRule;

/** Thrown when a new rule would contradict an existing one; the user decides whether to replace it. */
public class RuleConflictException extends Exception {
    private final CategoryRule existing;
    private final String requestedCategory;

    public RuleConflictException(CategoryRule existing, String requestedCategory) {
        super("A rule already sends \"" + existing.getPattern() + "\" to " + existing.getCategory()
                + ". Replace it with " + requestedCategory + "?");
        this.existing = existing;
        this.requestedCategory = requestedCategory;
    }

    public CategoryRule getExisting() {
        return existing;
    }

    public String getRequestedCategory() {
        return requestedCategory;
    }
}
