package com.financeassistant.importer;

import com.financeassistant.model.Transaction;
import java.util.List;

/** What a parser produced: the rows it understood and the rows it could not read. */
public record ParseResult(List<ParsedRow> rows, List<RejectedRow> rejected) {

    /** A successfully parsed row, remembering where it came from so later validation errors can point at it. */
    public record ParsedRow(int lineNumber, String rawLine, Transaction transaction) {
    }

    public List<Transaction> transactions() {
        return rows.stream().map(ParsedRow::transaction).toList();
    }
}
