package com.financeassistant.model;

import java.util.Locale;

/**
 * A user-created rule: any transaction whose description contains the
 * pattern (case-insensitive) is put into the category.
 */
public class CategoryRule implements Entity {
    private int id;
    private String pattern;
    private String category;

    public CategoryRule() {
    }

    public CategoryRule(String pattern, String category) {
        this.pattern = normalize(pattern);
        this.category = category;
    }

    /** Upper-case, single-spaced text so matching is not thrown off by case or spacing. */
    public static String normalize(String text) {
        return text == null ? "" : text.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    public boolean matches(String description) {
        return !pattern.isEmpty() && normalize(description).contains(pattern);
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void setId(int id) {
        this.id = id;
    }

    public String getPattern() {
        return pattern;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    @Override
    public String toString() {
        return "\"" + pattern + "\" -> " + category;
    }
}
