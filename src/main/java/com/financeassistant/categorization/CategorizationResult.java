package com.financeassistant.categorization;

/** How a categorization run went. aiError is null when Claude answered (or was not needed). */
public record CategorizationResult(int byRule, int byAi, int uncategorized, String aiError) {
    public int total() {
        return byRule + byAi + uncategorized;
    }

    public String summary() {
        String text = String.format("Categorized %d by your rules and %d by AI; %d left uncategorized.",
                byRule, byAi, uncategorized);
        return aiError == null ? text : text + " AI unavailable: " + aiError;
    }
}
