package com.financeassistant.importer;

/** A CSV row that could not be imported, with the reason shown to the user. */
public record RejectedRow(int lineNumber, String rawLine, String reason) {
    @Override
    public String toString() {
        return "line " + lineNumber + ": " + reason + "  [" + rawLine + "]";
    }
}
