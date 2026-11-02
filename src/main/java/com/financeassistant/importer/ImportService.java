package com.financeassistant.importer;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.model.Account;
import com.financeassistant.model.Transaction;
import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
     * Rows that were already imported. Matching is by count: if the database
     * already holds two identical rows (same account, date, amount and
     * description) the first two copies in the file are duplicates and any
     * further copy is new. This keeps genuine repeat purchases - two identical
     * vending-machine charges on the same day - while still skipping a
     * statement (or the overlapping part of one) that was imported before.
     */
    public List<Transaction> findDuplicates(List<Transaction> rows) {
        Map<String, Integer> alreadyStored = new HashMap<>();
        if (!rows.isEmpty() && rows.get(0).getAccountId() != 0) {
            int accountId = rows.get(0).getAccountId();
            for (Transaction existing : db.find(Transaction.class, t -> t.getAccountId() == accountId)) {
                alreadyStored.merge(existing.duplicateKey(), 1, Integer::sum);
            }
        }
        List<Transaction> duplicates = new ArrayList<>();
        Map<String, Integer> seenInFile = new HashMap<>();
        for (Transaction t : rows) {
            int occurrence = seenInFile.merge(t.duplicateKey(), 1, Integer::sum);
            if (occurrence <= alreadyStored.getOrDefault(t.duplicateKey(), 0)) {
                duplicates.add(t);
            }
        }
        return duplicates;
    }

    /** Rows that appear more than once in the same file. They are imported, but the preview points them out. */
    public static List<Transaction> findRepeatedRows(List<Transaction> rows) {
        Map<String, Integer> counts = new HashMap<>();
        rows.forEach(t -> counts.merge(t.duplicateKey(), 1, Integer::sum));
        return rows.stream().filter(t -> counts.get(t.duplicateKey()) > 1).toList();
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
