package com.financeassistant.budget;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.model.AlertRecord;
import com.financeassistant.model.Budget;
import com.financeassistant.model.Category;
import com.financeassistant.model.Transaction;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * F04 Set budget and F05 Budget alerts.
 *
 * <p>BudgetService is the Subject of the Observer pattern: when
 * checkThresholds() finds a category that has reached 80% of its budget or
 * gone over it, it notifies every attached AlertObserver without knowing
 * whether that is the GUI panel or the console. Each threshold alerts only
 * once per month; the record is stored so restarts do not repeat alerts.
 */
public class BudgetService {
    public static final double WARNING_THRESHOLD = 0.8;
    static final double MAX_BUDGET = 1_000_000;

    private final DatabaseManager db;
    private final List<AlertObserver> observers = new ArrayList<>();

    public BudgetService() {
        this.db = DatabaseManager.getInstance();
    }

    // ------------------------------------------------------------ observer

    public synchronized void attach(AlertObserver observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public synchronized void detach(AlertObserver observer) {
        observers.remove(observer);
    }

    protected void notifyObservers(String message) {
        List<AlertObserver> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(observers);
        }
        for (AlertObserver observer : snapshot) {
            observer.onAlert(message);
        }
    }

    // ------------------------------------------------------------ F04

    /**
     * Creates or (with overwrite) replaces the budget for a month and category.
     *
     * @throws IllegalArgumentException for a bad month, unknown category or non-positive/unrealistic amount
     * @throws BudgetExistsException    if one exists and overwrite is false
     */
    public Budget setBudget(String month, String category, double amount, boolean overwrite)
            throws BudgetExistsException {
        String validMonth = validateMonth(month);
        String validCategory = canonicalCategory(category);
        if (!(amount > 0)) {
            throw new IllegalArgumentException("The budget amount must be a positive number.");
        }
        if (amount > MAX_BUDGET) {
            throw new IllegalArgumentException("The budget amount must be at most " + (long) MAX_BUDGET + ".");
        }
        Optional<Budget> existing = findBudget(validMonth, validCategory);
        Budget budget;
        if (existing.isPresent()) {
            if (!overwrite) {
                throw new BudgetExistsException(existing.get());
            }
            budget = existing.get();
            budget.setAmount(round(amount));
            clearAlerts(validMonth, validCategory); // a new limit can alert again
        } else {
            budget = new Budget(validMonth, validCategory, round(amount));
        }
        db.save(budget);
        checkThresholds(validMonth);
        return budget;
    }

    public boolean budgetExists(String month, String category) {
        return findBudget(month, category).isPresent();
    }

    public List<Budget> getBudgets(String month) {
        List<Budget> budgets = db.find(Budget.class, b -> b.getMonth().equals(month));
        budgets.sort(Comparator.comparing(Budget::getCategory));
        return budgets;
    }

    public boolean deleteBudget(String month, String category) {
        Optional<Budget> budget = findBudget(validateMonth(month), canonicalCategory(category));
        budget.ifPresent(b -> {
            db.delete(Budget.class, b.getId());
            clearAlerts(b.getMonth(), b.getCategory());
        });
        return budget.isPresent();
    }

    // ------------------------------------------------------------ F05

    /** Spending against each budget for the month. Empty when no budgets are set. */
    public List<BudgetStatus> getStatus(String month) {
        String validMonth = validateMonth(month);
        List<BudgetStatus> result = new ArrayList<>();
        for (Budget b : getBudgets(validMonth)) {
            double spent = spentIn(validMonth, b.getCategory());
            double ratio = spent / b.getAmount();
            BudgetStatus.Level level = ratio > 1 ? BudgetStatus.Level.EXCEEDED
                    : ratio >= WARNING_THRESHOLD ? BudgetStatus.Level.WARNING : BudgetStatus.Level.OK;
            result.add(new BudgetStatus(b.getCategory(), b.getAmount(), round(spent),
                    round(b.getAmount() - spent), Math.round(ratio * 1000) / 10.0, level));
        }
        return result;
    }

    /**
     * Checks every budget for the month and notifies observers about thresholds
     * crossed for the first time. Returns the new alert messages.
     */
    public List<String> checkThresholds(String month) {
        List<String> messages = new ArrayList<>();
        for (BudgetStatus s : getStatus(month)) {
            if (s.level() == BudgetStatus.Level.OK || alreadyAlerted(month, s.category(), s.level())) {
                continue;
            }
            String message = s.level() == BudgetStatus.Level.EXCEEDED
                    ? String.format(Locale.ROOT, "%s budget for %s exceeded: spent $%.2f of $%.2f ($%.2f over).",
                    s.category(), month, s.spent(), s.limit(), -s.remaining())
                    : String.format(Locale.ROOT, "%s budget for %s is %.0f%% used: spent $%.2f of $%.2f, $%.2f left.",
                    s.category(), month, s.percentUsed(), s.spent(), s.limit(), s.remaining());
            db.save(new AlertRecord(month, s.category(), s.level().name()));
            if (s.level() == BudgetStatus.Level.EXCEEDED && !alreadyAlerted(month, s.category(), BudgetStatus.Level.WARNING)) {
                db.save(new AlertRecord(month, s.category(), BudgetStatus.Level.WARNING.name()));
            }
            messages.add(message);
            notifyObservers(message);
        }
        return messages;
    }

    /** Re-checks every month that has a budget, e.g. after an import or a correction. */
    public List<String> checkAllThresholds() {
        List<String> messages = new ArrayList<>();
        db.findAll(Budget.class).stream().map(Budget::getMonth).distinct().sorted()
                .forEach(m -> messages.addAll(checkThresholds(m)));
        return messages;
    }

    // ------------------------------------------------------------ helpers

    private double spentIn(String month, String category) {
        double spent = 0;
        for (Transaction t : db.find(Transaction.class,
                x -> x.isExpense() && month.equals(x.getMonth()) && category.equals(x.getCategory()))) {
            spent += -t.getAmount();
        }
        return spent;
    }

    private boolean alreadyAlerted(String month, String category, BudgetStatus.Level level) {
        return !db.find(AlertRecord.class, a -> a.getMonth().equals(month)
                && a.getCategory().equals(category) && a.getLevel().equals(level.name())).isEmpty();
    }

    private void clearAlerts(String month, String category) {
        for (AlertRecord a : db.find(AlertRecord.class,
                r -> r.getMonth().equals(month) && r.getCategory().equals(category))) {
            db.delete(AlertRecord.class, a.getId());
        }
    }

    private Optional<Budget> findBudget(String month, String category) {
        return db.find(Budget.class, b -> b.getMonth().equals(month) && b.getCategory().equalsIgnoreCase(category))
                .stream().findFirst();
    }

    public static String validateMonth(String month) {
        try {
            return YearMonth.parse(month == null ? "" : month.strip()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Month must look like 2026-09, not '" + month + "'.");
        }
    }

    private String canonicalCategory(String name) {
        return db.findAll(Category.class).stream().map(Category::getName)
                .filter(c -> name != null && c.equalsIgnoreCase(name.strip()))
                .filter(c -> !c.equals(Category.INCOME) && !c.equals(Category.UNCATEGORIZED))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("'" + name + "' is not a spending category."));
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
