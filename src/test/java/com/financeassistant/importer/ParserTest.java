package com.financeassistant.importer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.financeassistant.model.Transaction;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.Test;

public class ParserTest {

    static File csv(String content) throws Exception {
        Path file = Files.createTempFile("statement", ".csv");
        Files.writeString(file, content);
        file.toFile().deleteOnExit();
        return file.toFile();
    }

    @Test
    public void factoryCreatesTheRightParser() {
        ParserFactory factory = new ParserFactory();
        assertTrue(factory.createParser("td") instanceof TDParser);
        assertTrue(factory.createParser(" CIBC ") instanceof CIBCParser);
    }

    @Test(expected = IllegalArgumentException.class)
    public void factoryRejectsUnknownBank() {
        new ParserFactory().createParser("RBC");
    }

    @Test
    public void tdDebitsAreNegativeAndCreditsPositive() throws Exception {
        ParseResult result = new TDParser().parse(csv(
                "09/14/2026,TIM HORTONS #2241,4.35,,995.65\n09/15/2026,PAYROLL DEP,,1850.00,2845.65\n"));
        assertEquals(2, result.transactions().size());
        Transaction coffee = result.transactions().get(0);
        assertEquals(LocalDate.of(2026, 9, 14), coffee.getDate());
        assertEquals(-4.35, coffee.getAmount(), 0.001);
        assertEquals("TIM HORTONS #2241", coffee.getDescription());
        assertEquals(1850.00, result.transactions().get(1).getAmount(), 0.001);
    }

    @Test
    public void cibcHandlesQuotedCommasAndHeader() throws Exception {
        ParseResult result = new CIBCParser().parse(csv(
                "Date,Description,Debit,Credit,Card\n2026-08-09,\"PIZZA NOVA, LOCATION 52\",27.40,,4505\n"));
        assertEquals(1, result.transactions().size());
        assertEquals("PIZZA NOVA, LOCATION 52", result.transactions().get(0).getDescription());
        assertEquals(-27.40, result.transactions().get(0).getAmount(), 0.001);
    }

    @Test
    public void badRowsAreRejectedWithReasons() throws Exception {
        ParseResult result = new TDParser().parse(csv(
                "13/45/2026,BAD DATE,10.00,,1\n10/02/2026,BAD AMOUNT,abc,,1\n10/03/2026\n10/04/2026,OK,1.00,,1\n"));
        assertEquals(1, result.transactions().size());
        assertEquals(3, result.rejected().size());
        assertTrue(result.rejected().get(0).reason().contains("invalid date"));
        assertTrue(result.rejected().get(1).reason().contains("invalid debit"));
        assertTrue(result.rejected().get(2).reason().contains("columns"));
    }

    @Test
    public void splitHandlesEscapedQuotes() {
        assertArrayEquals(new String[]{"a", "say \"hi\"", "", "b"},
                CsvParser.splitCsvLine("a,\"say \"\"hi\"\"\",,b"));
    }
}
