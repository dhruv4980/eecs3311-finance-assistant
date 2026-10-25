package com.financeassistant.model;

/** A monthly spending limit for one category. Month is in YYYY-MM form. */
public class Budget implements Entity {
    private int id;
    private String month;
    private String category;
    private double amount;

    public Budget() {
    }

    public Budget(String month, String category, double amount) {
        this.month = month;
        this.category = category;
        this.amount = amount;
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

    public String getCategory() {
        return category;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }
}
