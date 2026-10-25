package com.financeassistant.importer;

/** A problem with an import that the user should be told about (bad file type, empty file, ...). */
public class ImportException extends Exception {
    public ImportException(String message) {
        super(message);
    }

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
