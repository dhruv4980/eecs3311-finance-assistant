package com.financeassistant.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.financeassistant.model.Budget;
import com.financeassistant.model.Category;
import com.financeassistant.model.CategorySource;
import com.financeassistant.model.Transaction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;

public class DatabaseManagerTest {

    @Test
    public void getInstanceReturnsTheSameObject() {
        DatabaseManager.open(null);
        assertSame(DatabaseManager.getInstance(), DatabaseManager.getInstance());
    }

    @Test
    public void seedsDefaultCategories() {
        DatabaseManager db = DatabaseManager.open(null);
        List<Category> categories = db.findAll(Category.class);
        assertEquals(Category.DEFAULTS.size(), categories.size());
    }

    @Test
    public void saveAssignsIdsAndUpdatesInPlace() {
        DatabaseManager db = DatabaseManager.open(null);
        Budget budget = db.save(new Budget("2026-09", "Dining", 200));
        assertNotEquals(0, budget.getId());

        budget.setAmount(250);
        db.save(budget);
        List<Budget> all = db.findAll(Budget.class);
        assertEquals(1, all.size());
        assertEquals(250, all.get(0).getAmount(), 0.001);
    }

    @Test
    public void returnedObjectsAreCopies() {
        DatabaseManager db = DatabaseManager.open(null);
        Budget budget = db.save(new Budget("2026-09", "Dining", 200));
        Budget loaded = db.find(Budget.class, budget.getId()).orElseThrow();
        loaded.setAmount(999);
        assertEquals(200, db.find(Budget.class, budget.getId()).orElseThrow().getAmount(), 0.001);
    }

    @Test
    public void persistsToFileAndReloads() throws Exception {
        Path file = Files.createTempDirectory("db").resolve("finance.json");
        DatabaseManager db = DatabaseManager.open(file);
        Transaction t = new Transaction(LocalDate.of(2026, 9, 14), -12.5, "TIM HORTONS");
        t.setCategory("Dining", CategorySource.AI);
        db.save(t);

        DatabaseManager reopened = DatabaseManager.open(file);
        Transaction loaded = reopened.find(Transaction.class, t.getId()).orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 14), loaded.getDate());
        assertEquals("Dining", loaded.getCategory());
        assertEquals(CategorySource.AI, loaded.getCategorySource());
        assertEquals(Category.DEFAULTS.size(), reopened.findAll(Category.class).size());

        // ids keep increasing after a reload
        Transaction next = reopened.save(new Transaction(LocalDate.of(2026, 9, 15), -3, "X"));
        assertTrue(next.getId() > t.getId());
    }

    @Test
    public void filterAndDelete() {
        DatabaseManager db = DatabaseManager.open(null);
        db.save(new Budget("2026-09", "Dining", 200));
        Budget rent = db.save(new Budget("2026-09", "Rent", 1500));
        db.save(new Budget("2026-10", "Rent", 1500));

        assertEquals(2, db.find(Budget.class, b -> b.getMonth().equals("2026-09")).size());
        assertTrue(db.delete(Budget.class, rent.getId()));
        assertFalse(db.delete(Budget.class, rent.getId()));
        assertEquals(2, db.findAll(Budget.class).size());
    }

    @Test(expected = IllegalStateException.class)
    public void corruptedFileIsReportedNotOverwritten() throws Exception {
        Path file = Files.createTempFile("bad", ".json");
        Files.writeString(file, "{ not json");
        DatabaseManager.open(file);
    }
}
