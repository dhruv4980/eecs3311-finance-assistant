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
    public void identicalRowsInOneFileAreKeptButReImportsAreSkipped() throws Exception {
        // two identical vending-machine charges on the same day are two real purchases
        File file = csv("09/14/2026,LOYALTY VENDING,2.36,,1\n09/14/2026,LOYALTY VENDING,2.36,,1\n09/15/2026,A,1.00,,1\n");
        ImportResult first = service.previewImport(file, "TD", "Chequing");
        assertEquals(3, first.getValidRows().size());
        assertEquals(2, first.getRepeatedRows().size());
        service.confirmImport(first);
        assertEquals(3, first.getImportedCount());
        assertEquals(0, first.getSkippedCount());

        // importing the same statement again adds nothing
        ImportResult second = service.previewImport(file, "TD", "Chequing");
        assertEquals(0, second.getValidRows().size());
        assertEquals(3, second.getSkippedCount());
        service.confirmImport(second);
        assertEquals(3, db.findAll(Transaction.class).size());

        // the same row in a different account is not a duplicate
        service.confirmImport(service.previewImport(file, "TD", "Savings"));
        assertEquals(6, db.findAll(Transaction.class).size());
    }

    @Test
    public void overlappingStatementOnlyAddsTheNewRows() throws Exception {
        service.confirmImport(service.previewImport(
                csv("09/14/2026,VEND,2.36,,1\n09/20/2026,B,5.00,,1\n"), "TD", "Chequing"));
        // next statement overlaps: repeats both old rows, adds a third identical charge and a new row
        ImportResult next = service.previewImport(
                csv("09/14/2026,VEND,2.36,,1\n09/14/2026,VEND,2.36,,1\n09/20/2026,B,5.00,,1\n10/01/2026,C,7.00,,1\n"),
                "TD", "Chequing");
        assertEquals(2, next.getSkippedCount());
        assertEquals(2, next.getValidRows().size());
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
    public void wrongBankChoiceSuggestsTheRightOne() throws Exception {
        File cibcFile = csv("2026-10-30,\"PIZZA PIZZA # 047 ETOBICOKE, ON\",10.45,,5268********1485\n"
                + "2026-10-29,\"UBER CANADA/UBEREATS TORONTO, ON\",21.85,,5268********1485\n");
        try {
            service.previewImport(cibcFile, "TD", "Visa");
            fail("expected a wrong-bank hint");
        } catch (ImportException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Did you mean to choose CIBC"));
        }
        assertEquals(2, service.previewImport(cibcFile, "CIBC", "Visa").getValidRows().size());
        // and the other way round: a TD export chosen as CIBC
        File tdFile = csv("\"2026-06-10\",\"CANADA           PRO\",,\"30.99\",\"4405.26\"\n");
        try {
            service.previewImport(tdFile, "CIBC", "Chequing");
            fail("expected a wrong-bank hint");
        } catch (ImportException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Did you mean to choose TD"));
        }
        // a file no bank can read still gets the normal per-row errors
        ImportResult junk = service.previewImport(csv("not,a,real,row\n13/45/2026,X,1,,1\n"), "TD", "Visa");
        assertEquals(0, junk.getValidRows().size());
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
