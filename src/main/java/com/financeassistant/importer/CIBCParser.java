package com.financeassistant.importer;

import com.financeassistant.model.Transaction;
import java.time.format.DateTimeFormatter;

/**
 * CIBC online banking export, no header:
 * {@code date (yyyy-MM-dd), description, debit, credit[, card number]}.
 */
public class CIBCParser extends CsvParser {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** CIBC exports end each row with the card number masked like 4505********1234. */
    static boolean isMaskedCardNumber(String text) {
        return text.matches("\\d{4}\\*+\\d{4}");
    }

    @Override
    public String getBankName() {
        return "CIBC";
    }

    @Override
    protected DateTimeFormatter dateFormat() {
        return DATE_FORMAT;
    }

    @Override
    protected Transaction toTransaction(String[] row) {
        requireColumns(row, 4);
        String fifth = cell(row, 4).strip();
        if (!fifth.isEmpty() && !isMaskedCardNumber(fifth)) {
            throw new IllegalArgumentException("this looks like a TD row (5th column is a balance, not a CIBC card number)");
        }
        double debit = parseMoney(cell(row, 2), "debit");
        double credit = parseMoney(cell(row, 3), "credit");
        return new Transaction(parseDate(row[0]), credit - debit, row[1]);
    }
}
