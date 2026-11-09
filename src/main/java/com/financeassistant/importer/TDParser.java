package com.financeassistant.importer;

import com.financeassistant.model.Transaction;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * TD Canada Trust account-activity export, no header:
 * {@code date, description, debit, credit, balance}, usually with every field quoted.
 * Current TD exports write dates as yyyy-MM-dd; older EasyWeb exports used MM/dd/yyyy,
 * so both are accepted.
 */
public class TDParser extends CsvParser {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter OLD_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    @Override
    public String getBankName() {
        return "TD";
    }

    @Override
    protected DateTimeFormatter dateFormat() {
        return DATE_FORMAT;
    }

    @Override
    protected LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.strip(), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return super.parseDate(text.strip().length() == 10 && text.contains("/") ? reformat(text) : text);
        }
    }

    /** Converts an old MM/dd/yyyy date to yyyy-MM-dd, or throws the usual "invalid date" error. */
    private String reformat(String text) {
        try {
            return LocalDate.parse(text.strip(), OLD_DATE_FORMAT).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid date '" + text.strip() + "'");
        }
    }

    @Override
    protected Transaction toTransaction(String[] row) {
        requireColumns(row, 4);
        String fifth = cell(row, 4).strip();
        if (CIBCParser.isMaskedCardNumber(fifth)) {
            throw new IllegalArgumentException("this looks like a CIBC row (it has a card number, not a TD balance)");
        }
        double debit = parseMoney(cell(row, 2), "debit");
        double credit = parseMoney(cell(row, 3), "credit");
        return new Transaction(parseDate(row[0]), credit - debit, row[1]);
    }
}
