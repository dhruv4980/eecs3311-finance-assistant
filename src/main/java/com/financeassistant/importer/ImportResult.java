package com.financeassistant.importer;

import com.financeassistant.model.Transaction;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of an import. After previewImport() it holds the rows that will be
 * saved; after confirmImport() it also holds how many were actually saved.
 */
public class ImportResult {
    private final File file;
    private final String bankName;
    private final String accountType;
    private final List<Transaction> validRows;
    private final List<Transaction> duplicateRows;
    private final List<RejectedRow> rejectedRows;
    private final List<Transaction> savedRows = new ArrayList<>();
    private boolean confirmed;

    public ImportResult(File file, String bankName, String accountType, List<Transaction> validRows,
                        List<Transaction> duplicateRows, List<RejectedRow> rejectedRows) {
        this.file = file;
        this.bankName = bankName;
        this.accountType = accountType;
        this.validRows = validRows;
        this.duplicateRows = duplicateRows;
        this.rejectedRows = rejectedRows;
    }

    public File getFile() {
        return file;
    }

    public String getBankName() {
        return bankName;
    }

    public String getAccountType() {
        return accountType;
    }

    public List<Transaction> getValidRows() {
        return validRows;
    }

    public List<Transaction> getDuplicateRows() {
        return duplicateRows;
    }

    public List<RejectedRow> getRejectedRows() {
        return rejectedRows;
    }

    public List<Transaction> getSavedRows() {
        return savedRows;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    void markSaved(List<Transaction> saved) {
        savedRows.clear();
        savedRows.addAll(saved);
        confirmed = true;
    }

    public int getImportedCount() {
        return savedRows.size();
    }

    public int getSkippedCount() {
        return duplicateRows.size();
    }

    public int getRejectedCount() {
        return rejectedRows.size();
    }

    public String getSummary() {
        if (confirmed) {
            return String.format("Imported %d transaction(s), skipped %d duplicate(s), rejected %d invalid row(s).",
                    getImportedCount(), getSkippedCount(), getRejectedCount());
        }
        return String.format("Ready to import %d transaction(s); %d duplicate(s) will be skipped and %d invalid row(s) rejected.",
                validRows.size(), getSkippedCount(), getRejectedCount());
    }
}
