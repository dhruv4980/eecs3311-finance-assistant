package com.financeassistant.importer;

import java.io.File;

/** Product interface of the Factory pattern: turns a bank statement file into transactions. */
public interface StatementParser {
    ParseResult parse(File file) throws ImportException;

    /** Bank this parser understands, e.g. "TD". */
    String getBankName();
}
