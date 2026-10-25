package com.financeassistant.budget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.model.CategorySource;
import com.financeassistant.model.Transaction;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class BudgetServiceTest {
    private DatabaseManager db;
    private BudgetService service;
    private final List<String> received = new ArrayList<>();

    @Before
    public void setUp() {
        db = DatabaseManager.open(null);
        service = new BudgetService();
        service.attach(received::add);
    }

    private void spend(String category, double amount, int day) {
        Transaction t = new Transaction(LocalDate.of(2026, 9, day), -amount, "SHOP " + day);
        t.setCategory(category, CategorySource.USER);
        db.save(t);
    }

    @Test
    public void createsBudgetAndValidatesInput() throws Exception {
        service.setBudget("2026-09", "dining", 200, false);
        assertTrue(service.budgetExists("2026-09", "Dining"));
        assertEquals("Dining", service.getBudgets("2026-09").get(0).getCategory());

        expectInvalid("2026-09", "Dining", 0);
        expectInvalid("2026-09", "Dining", -5);
        expectInvalid("2026-09", "Dining", Double.NaN);
        expectInvalid("2026-09", "Dining", 5_000_000);
        expectInvalid("Sept", "Dining", 100);
        expectInvalid("2026-09", "Spaceships", 100);
        expectInvalid("2026-09", "Income", 100);
    }

    @Test
    public void existingBudgetNeedsOverwrite() throws Exception {
        service.setBudget("2026-09", "Dining", 200, false);
        try {
            service.setBudget("2026-09", "Dining", 300, false);
            fail();
        } catch (BudgetExistsException e) {
            assertEquals(200, e.getExisting().getAmount(), 0.001);
        }
        service.setBudget("2026-09", "Dining", 300, true);
        assertEquals(1, service.getBudgets("2026-09").size());
        assertEquals(300, service.getBudgets("2026-09").get(0).getAmount(), 0.001);
    }

    @Test
    public void statusShowsSpentRemainingAndLevel() throws Exception {
        service.setBudget("2026-09", "Dining", 200, false);
        spend("Dining", 50, 1);
        spend("Dining", 20, 2);
        spend("Groceries", 500, 3);
        BudgetStatus s = service.getStatus("2026-09").get(0);
        assertEquals(70, s.spent(), 0.001);
        assertEquals(130, s.remaining(), 0.001);
        assertEquals(35.0, s.percentUsed(), 0.01);
        assertEquals(BudgetStatus.Level.OK, s.level());
        assertTrue(service.getStatus("2026-10").isEmpty()); // no budgets that month
    }

    @Test
    public void alertsOnceAtEightyPercentAndOnceWhenExceeded() throws Exception {
        service.setBudget("2026-09", "Dining", 100, false);
        spend("Dining", 79, 1);
        assertTrue(service.checkThresholds("2026-09").isEmpty());

        spend("Dining", 1, 2); // exactly 80%
        assertEquals(1, service.checkThresholds("2026-09").size());
        assertTrue(received.get(0).contains("80% used"));
        assertTrue(service.checkThresholds("2026-09").isEmpty()); // not repeated

        spend("Dining", 25, 3);
        service.checkThresholds("2026-09");
        assertEquals(2, received.size());
        assertTrue(received.get(1).contains("exceeded"));
        assertTrue(received.get(1).contains("$5.00 over"));
        service.checkThresholds("2026-09");
        assertEquals(2, received.size());
    }

    @Test
    public void jumpingStraightOverSendsOnlyTheExceededAlert() throws Exception {
        service.setBudget("2026-09", "Dining", 100, false);
        spend("Dining", 150, 1);
        service.checkThresholds("2026-09");
        service.checkThresholds("2026-09");
        assertEquals(1, received.size());
        assertTrue(received.get(0).contains("exceeded"));
    }

    @Test
    public void raisingTheBudgetAllowsNewAlerts() throws Exception {
        service.setBudget("2026-09", "Dining", 100, false);
        spend("Dining", 90, 1);
        service.checkThresholds("2026-09");
        service.setBudget("2026-09", "Dining", 110, true); // 82% of the new budget
        assertEquals(2, received.size());
    }

    @Test
    public void alertsReachEveryObserverAndDetachStopsThem() throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        CliAlertPrinter printer = new CliAlertPrinter(new PrintStream(console, true));
        service.attach(printer);
        service.setBudget("2026-09", "Dining", 100, false);
        spend("Dining", 85, 1);
        service.checkThresholds("2026-09");
        assertEquals(1, received.size());
        assertTrue(console.toString().startsWith("[ALERT] Dining budget"));

        service.detach(printer);
        spend("Dining", 50, 2);
        service.checkThresholds("2026-09");
        assertEquals(2, received.size());
        assertEquals(1, console.toString().split("\n").length);
    }

    @Test
    public void alertMemorySurvivesARestart() throws Exception {
        service.setBudget("2026-09", "Dining", 100, false);
        spend("Dining", 85, 1);
        service.checkThresholds("2026-09");
        BudgetService restarted = new BudgetService();
        List<String> again = new ArrayList<>();
        restarted.attach(again::add);
        restarted.checkAllThresholds();
        assertTrue(again.isEmpty());
    }

    private void expectInvalid(String month, String category, double amount) throws Exception {
        try {
            service.setBudget(month, category, amount, true);
            fail("expected rejection of " + month + "/" + category + "/" + amount);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
