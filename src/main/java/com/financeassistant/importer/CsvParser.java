package com.financeassistant.importer;

import com.financeassistant.model.Transaction;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base for CSV statement parsers. It holds the reading logic every
 * bank shares (splitting quoted CSV, skipping a header row, collecting bad
 * rows); each concrete parser only says how one row maps to a Transaction.
 */
public abstract class CsvParser implements StatementParser {

    @Override
    public ParseResult parse(File file) throws ImportException {
        List<String> lines = readRows(file);
        List<Transaction> transactions = new ArrayList<>();
        List<RejectedRow> rejected = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] row = splitCsvLine(line);
            if (i == 0 && looksLikeHeader(row)) {
                continue;
            }
            try {
                transactions.add(toTransaction(row));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                rejected.add(new RejectedRow(i + 1, line, e.getMessage()));
            }
        }
        return new ParseResult(transactions, rejected);
    }

    protected List<String> readRows(File file) throws ImportException {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty() && lines.get(0).startsWith("﻿")) {
                lines.set(0, lines.get(0).substring(1)); // strip Excel's byte-order mark
            }
            return lines;
        } catch (IOException e) {
            throw new ImportException("Could not read " + file.getName() + ": " + e.getMessage(), e);
        }
    }

    /** Converts one CSV row; throws IllegalArgumentException with a readable reason if the row is bad. */
    protected abstract Transaction toTransaction(String[] row);

    /** A first row is a header if its first cell is not a date this bank uses. */
    protected boolean looksLikeHeader(String[] row) {
        try {
            parseDate(row[0]);
            return false;
        } catch (RuntimeException e) {
            return row[0].chars().anyMatch(Character::isLetter);
        }
    }

    protected abstract DateTimeFormatter dateFormat();

    protected LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.strip(), dateFormat());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid date '" + text.strip() + "'");
        }
    }

    /** Parses a money cell; blank means zero. Accepts "$1,234.50" style values. */
    protected double parseMoney(String text, String column) {
        String clean = text == null ? "" : text.replace("$", "").replace(",", "").strip();
        if (clean.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + column + " amount '" + text.strip() + "'");
        }
    }

    protected static String cell(String[] row, int index) {
        return index < row.length ? row[index] : "";
    }

    protected static void requireColumns(String[] row, int count) {
        if (row.length < count) {
            throw new IllegalArgumentException("expected at least " + count + " columns but found " + row.length);
        }
    }

    /** Splits one CSV line, honouring double quotes ("A, B" stays one cell; "" is an escaped quote). */
    static String[] splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cells.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().strip());
        return cells.toArray(new String[0]);
    }
}
