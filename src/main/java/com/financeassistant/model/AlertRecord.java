package com.financeassistant.model;

/**
 * Remembers that an alert was already sent for a category in a month, so the
 * user is alerted only the first time a threshold is crossed (F05).
 */
public class AlertRecord implements Entity {
    private int id;
    private String month;
    private String category;
    private String level;

    public AlertRecord() {
    }

    public AlertRecord(String month, String category, String level) {
        this.month = month;
        this.category = category;
        this.level = level;
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

    public String getLevel() {
        return level;
    }
}
