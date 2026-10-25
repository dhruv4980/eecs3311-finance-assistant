package com.financeassistant.importer;

import com.financeassistant.model.Transaction;
import java.time.format.DateTimeFormatter;

/**
 * CIBC online banking export, no header:
 * {@code date (yyyy-MM-dd), description, debit, credit[, card number]}.
 */
public class CIBCParser extends CsvParser {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

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
        double debit = parseMoney(cell(row, 2), "debit");
        double credit = parseMoney(cell(row, 3), "credit");
        return new Transaction(parseDate(row[0]), credit - debit, row[1]);
    }
}
