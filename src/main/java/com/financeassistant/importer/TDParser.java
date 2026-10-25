package com.financeassistant.importer;

import com.financeassistant.model.Transaction;
import java.time.format.DateTimeFormatter;

/**
 * TD Canada Trust EasyWeb export, no header:
 * {@code date (MM/dd/yyyy), description, debit, credit, balance}.
 */
public class TDParser extends CsvParser {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    @Override
    public String getBankName() {
        return "TD";
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
