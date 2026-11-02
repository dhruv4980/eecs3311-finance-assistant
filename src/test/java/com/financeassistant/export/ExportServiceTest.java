package com.financeassistant.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.financeassistant.budget.BudgetService;
import com.financeassistant.controller.AgentController;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.FakeLLMClient;
import com.financeassistant.model.CategorySource;
import com.financeassistant.model.Transaction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;

public class ExportServiceTest {
    private AgentController controller;
    private Path folder;

    @Before
    public void setUp() throws Exception {
        DatabaseManager db = DatabaseManager.open(null);
        add(db, LocalDate.of(2026, 9, 3), -45.50, "PIZZA NOVA, LOCATION 52", "Dining");
        add(db, LocalDate.of(2026, 9, 8), -120, "COSTCO \"BULK\"", "Groceries");
        add(db, LocalDate.of(2026, 9, 15), 1850, "PAYROLL", "Income");
        controller = new AgentController(FakeLLMClient.failing("no AI in tests"));
        controller.setBudget("2026-09", "Dining", 100, false);
        folder = Files.createTempDirectory("exports");
    }

    private static void add(DatabaseManager db, LocalDate date, double amount, String description, String category) {
        Transaction t = new Transaction(date, amount, description);
        t.setCategory(category, CategorySource.USER);
        db.save(t);
    }

    @Test
    public void eachFormatIsHandledByItsOwnStrategy() throws Exception {
        assertEquals(List.of("pdf", "csv", "md"), controller.getExportFormats());
        for (String format : controller.getExportFormats()) {
            Path written = controller.exportReport("monthly", "2026-09", format, folder.resolve("sept"), false);
            assertEquals("sept." + format, written.getFileName().toString());
            assertTrue(Files.size(written) > 100);
        }
        try (Stream<Path> files = Files.list(folder)) {
            assertEquals("no temporary files are left behind", 3, files.count());
        }
    }

    @Test
    public void csvQuotesCellsWithCommasAndQuotes() throws Exception {
        Path csv = controller.exportReport("transactions", "2026-09", "csv", folder.resolve("t.csv"), false);
        String text = Files.readString(csv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿Transactions - 2026-09"));
        assertTrue(text, text.contains("2026-09-03,\"PIZZA NOVA, LOCATION 52\",Dining,-45.50"));
        assertTrue(text.contains("\"COSTCO \"\"BULK\"\"\""));
    }

    @Test
    public void markdownHasHeadingsAndTables() throws Exception {
        String md = Files.readString(controller.exportReport("budget", "2026-09", "md", folder.resolve("b"), false));
        assertTrue(md.startsWith("# Budget status - 2026-09"));
        assertTrue(md, md.contains("| Category | Budget | Spent | Remaining | Used | Status |"));
        assertTrue(md.contains("| Dining | 100.00 | 45.50 | 54.50 | 45.5% | OK |"));
    }

    @Test
    public void pdfIsWellFormed() throws Exception {
        Path pdf = controller.exportReport("monthly", "2026-09", "pdf", folder.resolve("m"), false);
        byte[] bytes = Files.readAllBytes(pdf);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.4"));
        assertTrue(text.endsWith("%%EOF\n"));
        assertTrue(text.contains("(Monthly report - 2026-09) Tj"));

        // the xref table must point at each "N 0 obj" exactly
        int xref = Integer.parseInt(text.substring(text.lastIndexOf("startxref\n") + 10, text.lastIndexOf("\n%%EOF")).strip());
        assertTrue(text.startsWith("xref", xref));
        Matcher entries = Pattern.compile("(\\d{10}) 00000 n").matcher(text.substring(xref));
        int object = 1;
        while (entries.find()) {
            int offset = Integer.parseInt(entries.group(1));
            assertTrue("object " + object, text.startsWith(object + " 0 obj", offset));
            object++;
        }
        assertTrue(object > 7);
    }

    @Test
    public void longTransactionListSpansSeveralPdfPages() throws Exception {
        DatabaseManager db = DatabaseManager.getInstance();
        for (int i = 0; i < 150; i++) {
            add(db, LocalDate.of(2026, 9, 1 + i % 28), -1 - i, "STORE " + i, "Shopping");
        }
        String text = new String(Files.readAllBytes(
                controller.exportReport("transactions", "2026-09", "pdf", folder.resolve("many"), false)),
                StandardCharsets.ISO_8859_1);
        Matcher count = Pattern.compile("/Count (\\d+)").matcher(text);
        assertTrue(count.find());
        assertTrue(Integer.parseInt(count.group(1)) >= 3);
        assertTrue(text.contains("(Page 3 of "));
    }

    @Test
    public void existingFileNeedsOverwrite() throws Exception {
        Path target = folder.resolve("r.md");
        Files.writeString(target, "keep me");
        try {
            controller.exportReport("monthly", "2026-09", "md", target, false);
            fail("expected ExportFileExistsException");
        } catch (ExportFileExistsException e) {
            assertEquals("keep me", Files.readString(target));
        }
        controller.exportReport("monthly", "2026-09", "md", target, true);
        assertTrue(Files.readString(target).startsWith("# Monthly report"));
    }

    @Test
    public void badRequestsGiveClearErrors() {
        expectError("monthly", "2026-09", "docx", folder.resolve("x"), "Unknown format");
        expectError("yearly", "2026-09", "pdf", folder.resolve("x"), "Unknown report type");
        expectError("monthly", "2026-09", "pdf", folder.resolve("missing/x"), "does not exist");
        expectError("monthly", "September", "pdf", folder.resolve("x"), "Month must look like");
        expectError("transactions", "2026-01", "csv", folder.resolve("x"), "no transactions");
        expectError("budget", "2026-01", "csv", folder.resolve("x"), "No budgets");
    }

    @Test
    public void choosingAFolderSavesInsideItWithADefaultName() throws Exception {
        Path written = controller.exportReport("monthly", "2026-09", "pdf", folder, false);
        assertEquals(folder.resolve("monthly-2026-09.pdf").toAbsolutePath(), written);
        assertTrue(Files.isRegularFile(written));
    }

    @Test
    public void pdfTextIsEscapedAndUnsupportedCharactersReplaced() {
        assertEquals("a \\(b\\) c\\\\ - 'x' \"y\" ?", PdfExporter.escape("a (b) c\\ — ‘x’ “y” ☃"));
        assertEquals(List.of("one two", "three"), PdfExporter.wrap("one two three", 8));
    }

    private void expectError(String type, String month, String format, Path path, String message) {
        try {
            controller.exportReport(type, month, format, path, false);
            fail("expected error: " + message);
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(message));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertFalse(Files.exists(folder.resolve("x.pdf")));
    }
}
