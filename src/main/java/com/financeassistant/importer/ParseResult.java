package com.financeassistant.importer;

import com.financeassistant.model.Transaction;
import java.util.List;

/** What a parser produced: the rows it understood and the rows it could not read. */
public record ParseResult(List<Transaction> transactions, List<RejectedRow> rejected) {
}
