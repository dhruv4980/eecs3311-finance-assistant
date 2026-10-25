package com.financeassistant.importer;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.model.Account;
import com.financeassistant.model.Transaction;
import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * F01 Importing data. Two steps, so the user can check the rows first:
 * previewImport() parses and validates without saving; confirmImport() saves.
 */
public class ImportService {
    private static final double MAX_AMOUNT = 10_000_000;

    private final ParserFactory parserFactory;
    private final DatabaseManager db;

    public ImportService(ParserFactory parserFactory) {
        this.parserFactory = parserFactory;
        this.db = DatabaseManager.getInstance();
    }

    public ImportResult previewImport(File file, String bank, String accountType) throws ImportException {
        checkFile(file);
        if (accountType == null || accountType.isBlank()) {
            throw new ImportException("Please choose an account type (e.g. Chequing, Visa).");
        }
        StatementParser parser;
        try {
            parser = parserFactory.createParser(bank);
        } catch (IllegalArgumentException e) {
            throw new ImportException(e.getMessage(), e);
        }
        ParseResult parsed = parser.parse(file);
        if (parsed.rows().isEmpty() && parsed.rejected().isEmpty()) {
            throw new ImportException("The file " + file.getName() + " has no transactions in it.");
        }

        int accountId = findAccount(parser.getBankName(), accountType).map(Account::getId).orElse(0);
        List<RejectedRow> rejected = new ArrayList<>(parsed.rejected());
        List<Transaction> candidates = new ArrayList<>();
        for (ParseResult.ParsedRow row : parsed.rows()) {
            Transaction t = row.transaction();
            t.setAccountId(accountId);
            String problem = validateRow(t);
            if (problem == null) {
                candidates.add(t);
            } else {
                rejected.add(new RejectedRow(row.lineNumber(), row.rawLine(), problem));
            }
        }
        rejected.sort(java.util.Comparator.comparingInt(RejectedRow::lineNumber));
        List<Transaction> duplicates = findDuplicates(candidates);
        candidates.removeAll(duplicates);
        return new ImportResult(file, parser.getBankName(), accountType.strip(), candidates, duplicates, rejected);
    }

    /** Saves the previewed rows. Calling it twice does not import anything twice. */
    public ImportResult confirmImport(ImportResult preview) {
        if (preview.isConfirmed()) {
            return preview;
        }
        Account account = findAccount(preview.getBankName(), preview.getAccountType())
                .orElseGet(() -> db.save(new Account(preview.getBankName(), preview.getAccountType())));
        for (Transaction t : preview.getValidRows()) {
            t.setAccountId(account.getId());
        }
        List<Transaction> toSave = new ArrayList<>(preview.getValidRows());
        toSave.removeAll(findDuplicates(toSave)); // the same file may have been confirmed elsewhere meanwhile
        db.saveAll(toSave);
        preview.markSaved(toSave);
        return preview;
    }

    /** Returns null when the row is fine, otherwise the reason it is rejected. */
    public String validateRow(Transaction t) {
        if (t.getDate() == null) {
            return "missing date";
        }
        if (t.getDate().isAfter(LocalDate.now().plusDays(1))) {
            return "date " + t.getDate() + " is in the future";
        }
        if (t.getDescription() == null || t.getDescription().isBlank()) {
            return "missing description";
        }
        if (t.getAmount() == 0) {
            return "amount is zero";
        }
        if (Math.abs(t.getAmount()) > MAX_AMOUNT) {
            return "amount " + t.getAmount() + " is unrealistically large";
        }
        return null;
    }

    /**
     * Rows that are already in the database, or that repeat an earlier row in
     * the same file (same account, date, amount and description).
     */
    public List<Transaction> findDuplicates(List<Transaction> rows) {
        Set<String> seen = new HashSet<>();
        if (!rows.isEmpty() && rows.get(0).getAccountId() != 0) {
            int accountId = rows.get(0).getAccountId();
            for (Transaction existing : db.find(Transaction.class, t -> t.getAccountId() == accountId)) {
                seen.add(existing.duplicateKey());
            }
        }
        List<Transaction> duplicates = new ArrayList<>();
        for (Transaction t : rows) {
            if (!seen.add(t.duplicateKey())) {
                duplicates.add(t);
            }
        }
        return duplicates;
    }

    public List<String> supportedBanks() {
        return parserFactory.supportedBanks();
    }

    private Optional<Account> findAccount(String bank, String accountType) {
        return db.find(Account.class, a -> a.sameAs(bank, accountType.strip())).stream().findFirst();
    }

    private void checkFile(File file) throws ImportException {
        if (file == null || !file.isFile()) {
            throw new ImportException("File not found: " + (file == null ? "(none)" : file.getPath()));
        }
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ImportException("Only CSV files can be imported. Please export your statement as .csv.");
        }
        if (file.length() == 0) {
            throw new ImportException("The file " + file.getName() + " is empty.");
        }
    }
}
