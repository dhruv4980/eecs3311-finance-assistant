package com.financeassistant.model;

import java.util.List;

/** A spending/income category such as Groceries or Rent. */
public class Category implements Entity {
    public static final String UNCATEGORIZED = "Uncategorized";
    public static final String INCOME = "Income";

    /** Categories created the first time the database is opened. */
    public static final List<String> DEFAULTS = List.of(
            "Groceries", "Dining", "Rent", "Transport", "Utilities", "Entertainment",
            "Shopping", "Health", "Subscriptions", "Education", "Travel", "Fees",
            "Transfers", INCOME, "Other", UNCATEGORIZED);

    private int id;
    private String name;

    public Category() {
    }

    public Category(String name) {
        this.name = name;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
