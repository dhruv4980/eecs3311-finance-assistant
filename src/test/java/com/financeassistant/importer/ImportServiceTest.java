package com.financeassistant.importer;

import static com.financeassistant.importer.ParserTest.csv;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.model.Account;
import com.financeassistant.model.Transaction;
import java.io.File;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;

public class ImportServiceTest {
    private DatabaseManager db;
    private ImportService service;

    @Before
    public void setUp() {
        db = DatabaseManager.open(null);
        service = new ImportService(new ParserFactory());
    }

    @Test
    public void previewDoesNotSaveAndConfirmDoes() throws Exception {
        File file = csv("09/14/2026,TIM HORTONS,4.35,,1\n09/15/2026,LOBLAWS,54.20,,1\n");
        ImportResult preview = service.previewImport(file, "TD", "Chequing");
        assertEquals(2, preview.getValidRows().size());
        assertEquals(0, db.findAll(Transaction.class).size());

        ImportResult done = service.confirmImport(preview);
        assertEquals(2, done.getImportedCount());
        assertEquals(2, db.findAll(Transaction.class).size());
        Account account = db.findAll(Account.class).get(0);
        assertEquals("TD Chequing", account.getName());
        assertTrue(db.findAll(Transaction.class).stream().allMatch(t -> t.getAccountId() == account.getId()));
    }

    @Test
    public void duplicatesInFileAndDatabaseAreSkipped() throws Exception {
        File file = csv("09/14/2026,TIM HORTONS,4.35,,1\n09/14/2026,TIM HORTONS,4.35,,1\n");
        ImportResult first = service.confirmImport(service.previewImport(file, "TD", "Chequing"));
        assertEquals(1, first.getImportedCount());
        assertEquals(1, first.getSkippedCount());

        // importing the same statement again adds nothing
        ImportResult second = service.previewImport(file, "TD", "Chequing");
        assertEquals(0, second.getValidRows().size());
        assertEquals(2, second.getSkippedCount());
        service.confirmImport(second);
        assertEquals(1, db.findAll(Transaction.class).size());

        // the same row in a different account is not a duplicate
        service.confirmImport(service.previewImport(file, "TD", "Savings"));
        assertEquals(2, db.findAll(Transaction.class).size());
    }

    @Test
    public void confirmingTwiceDoesNotDoubleImport() throws Exception {
        ImportResult preview = service.previewImport(csv("09/14/2026,A,1.00,,1\n"), "TD", "Chequing");
        service.confirmImport(preview);
        service.confirmImport(preview);
        assertEquals(1, db.findAll(Transaction.class).size());
    }

    @Test
    public void invalidRowsAreReportedNotSaved() throws Exception {
        File file = csv("09/14/2026,ZERO,0,,1\n09/14/2026,,5.00,,1\n13/45/2026,BAD,1,,1\n12/31/2099,FUTURE,5.00,,1\n09/14/2026,OK,5.00,,1\n");
        ImportResult result = service.confirmImport(service.previewImport(file, "TD", "Chequing"));
        assertEquals(1, result.getImportedCount());
        assertEquals(4, result.getRejectedCount());
        assertTrue(result.getSummary().contains("rejected 4"));
        // rejected rows point at their real line in the file, in file order
        assertEquals(java.util.List.of(1, 2, 3, 4),
                result.getRejectedRows().stream().map(RejectedRow::lineNumber).toList());
        assertEquals("09/14/2026,,5.00,,1", result.getRejectedRows().get(1).rawLine());
    }

    @Test
    public void nonCsvFileIsRejected() throws Exception {
        File txt = Files.createTempFile("statement", ".txt").toFile();
        Files.writeString(txt.toPath(), "09/14/2026,A,1.00,,1\n");
        expectImportError(txt, "Only CSV");
    }

    @Test
    public void emptyFileIsRejected() throws Exception {
        expectImportError(csv(""), "empty");
        expectImportError(csv("\n  \n"), "no transactions");
    }

    @Test
    public void unsupportedBankIsAnImportError() throws Exception {
        try {
            service.previewImport(csv("09/14/2026,A,1.00,,1\n"), "RBC", "Chequing");
            fail();
        } catch (ImportException e) {
            assertTrue(e.getMessage().contains("Unsupported bank"));
        }
    }

    @Test
    public void sampleStatementsImportCleanly() throws Exception {
        ImportResult td = service.confirmImport(
                service.previewImport(new File("sample-data/td-chequing-jul-sep-2026.csv"), "TD", "Chequing"));
        ImportResult cibc = service.confirmImport(
                service.previewImport(new File("sample-data/cibc-visa-jul-sep-2026.csv"), "CIBC", "Visa"));
        assertEquals(0, td.getRejectedCount());
        assertEquals(0, cibc.getRejectedCount());
        assertTrue(td.getImportedCount() > 30);
        assertTrue(cibc.getImportedCount() > 100);
    }

    private void expectImportError(File file, String message) {
        try {
            service.previewImport(file, "TD", "Chequing");
            fail("expected ImportException");
        } catch (ImportException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(message));
        }
    }
}
