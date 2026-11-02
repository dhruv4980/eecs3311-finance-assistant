package com.financeassistant.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.financeassistant.budget.BudgetService;
import com.financeassistant.categorization.CategorizationService;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.FakeLLMClient;
import com.financeassistant.llm.LLMClient;
import com.financeassistant.model.CategorySource;
import com.financeassistant.model.Transaction;
import com.financeassistant.subscription.SubscriptionService;
import java.time.LocalDate;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ReportServiceTest {
    private DatabaseManager db;

    @Before
    public void setUp() throws Exception {
        db = DatabaseManager.open(null);
        // August: usual shopping and dining
        for (int day = 1; day <= 6; day++) {
            add(LocalDate.of(2026, 8, day), -40 - day, "SHOP " + day, "Shopping");
        }
        add(LocalDate.of(2026, 8, 10), -100, "RESTAURANT", "Dining");
        // September: one huge shopping charge, more dining, salary and a card payment
        add(LocalDate.of(2026, 9, 3), -45, "SHOP A", "Shopping");
        add(LocalDate.of(2026, 9, 19), -1299.99, "BEST BUY", "Shopping");
        add(LocalDate.of(2026, 9, 12), -250, "RESTAURANT", "Dining");
        add(LocalDate.of(2026, 9, 15), 2000, "PAYROLL", "Income");
        add(LocalDate.of(2026, 9, 21), 900, "PAYMENT THANK YOU", "Transfers");
        new BudgetService().setBudget("2026-09", "Dining", 200, false);
    }

    private void add(LocalDate date, double amount, String description, String category) {
        Transaction t = new Transaction(date, amount, description);
        t.setCategory(category, CategorySource.USER);
        db.save(t);
    }

    private ReportService service(LLMClient llm) {
        return new ReportService(llm, new CategorizationService(llm), new BudgetService(), new SubscriptionService(llm));
    }

    @Test
    public void reportHasCorrectFiguresAndAnAiSummary() {
        FakeLLMClient llm = new FakeLLMClient(prompt -> "You spent a lot on a Best Buy purchase.");
        MonthlyReport r = service(llm).generateMonthlyReport("2026-09");

        assertEquals(2000.0, r.getTotalIncome(), 0.001); // the card payment is a transfer, not income
        assertEquals(1594.99, r.getTotalSpending(), 0.001);
        assertEquals(405.01, r.getNet(), 0.001);
        assertEquals(5, r.getTransactionCount());
        assertEquals("Shopping", r.getCategoryBreakdown().get(0).category());
        assertEquals("You spent a lot on a Best Buy purchase.", r.getSummary());
        assertTrue(r.isAiSummary());

        // the figures Claude was given are the computed ones
        String prompt = llm.prompts.get(llm.prompts.size() - 1);
        assertTrue(prompt, prompt.contains("Income: $2000.00. Spending: $1594.99"));
        assertTrue(prompt.contains("Dining: spent $250.00 of $200.00"));
        assertTrue(prompt.contains("BEST BUY $1299.99"));
    }

    @Test
    public void comparesEachCategoryWithLastMonth() {
        MonthlyReport r = service(new FakeLLMClient(p -> "ok")).generateMonthlyReport("2026-09");
        CategoryChange shopping = r.getChangesFromLastMonth().get(0); // biggest change first
        assertEquals("Shopping", shopping.category());
        assertEquals(261.0, shopping.lastMonth(), 0.001);
        assertEquals(1083.99, shopping.change(), 0.001);
    }

    @Test
    public void flagsOnlyChargesFarAboveTheUsualAmount() {
        List<UnusualTransaction> unusual = service(new FakeLLMClient(p -> "ok")).findUnusualTransactions("2026-09");
        assertEquals(1, unusual.size());
        assertEquals("BEST BUY", unusual.get(0).description());
        assertTrue(unusual.get(0).reason(), unusual.get(0).reason().contains("typical Shopping charge of $44.00"));
        // dining has too little history to judge, so the $250 dinner is not flagged
        assertTrue(unusual.stream().noneMatch(u -> u.category().equals("Dining")));
    }

    @Test
    public void templateSummaryWhenAiIsDown() {
        MonthlyReport r = service(FakeLLMClient.failing("down")).generateMonthlyReport("2026-09");
        assertFalse(r.isAiSummary());
        assertTrue(r.getSummary(), r.getSummary().contains("received $2000.00 and spent $1594.99"));
        assertTrue(r.getSummary().contains("over 1 budget"));
        assertTrue(r.getSummary().contains("1 unusual charge"));
    }

    @Test
    public void regeneratingReplacesTheSavedReport() {
        ReportService service = service(new FakeLLMClient(p -> "ok"));
        service.generateMonthlyReport("2026-09");
        service.generateMonthlyReport("2026-09");
        assertEquals(1, service.getReports().size());
        assertEquals("2026-09", service.getReport("2026-09").getMonth());
        assertNull(service.getReport("2026-08"));
    }

    @Test
    public void monthWithoutTransactionsIsRejected() {
        try {
            service(new FakeLLMClient(p -> "ok")).generateMonthlyReport("2026-01");
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("no transactions in 2026-01"));
        }
    }
}
