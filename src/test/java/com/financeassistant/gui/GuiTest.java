package com.financeassistant.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.financeassistant.budget.BudgetService;
import com.financeassistant.controller.AgentController;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.FakeLLMClient;
import com.financeassistant.model.CategorySource;
import com.financeassistant.model.Transaction;
import com.financeassistant.report.MonthlyReport;
import java.time.LocalDate;
import javax.swing.SwingUtilities;
import org.junit.Before;
import org.junit.Test;

/** Tests for the GUI pieces that do not need a screen. */
public class GuiTest {
    private AgentController controller;

    @Before
    public void setUp() {
        DatabaseManager db = DatabaseManager.open(null);
        Transaction t = new Transaction(LocalDate.of(2026, 9, 5), -95, "UBER EATS");
        t.setCategory("Dining", CategorySource.USER);
        db.save(t);
        controller = new AgentController(FakeLLMClient.failing("off"));
    }

    @Test
    public void alertPanelReceivesBudgetAlertsAsAnObserver() throws Exception {
        GuiAlertPanel panel = new GuiAlertPanel();
        controller.addAlertObserver(panel);
        controller.setBudget("2026-09", "Dining", 100, false); // 95% used -> warning
        SwingUtilities.invokeAndWait(() -> { }); // let the panel's update run on the Swing thread
        assertEquals(1, panel.getUnreadCount());

        controller.removeAlertObserver(panel);
        controller.setBudget("2026-09", "Dining", 50, true); // exceeded, but the panel is detached
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.getUnreadCount());
    }

    @Test
    public void reportTextShowsFiguresAndSections() throws Exception {
        new BudgetService().setBudget("2026-09", "Dining", 80, false);
        MonthlyReport report = controller.generateMonthlyReport("2026-09");
        String text = ReportsPanel.format(report);
        assertTrue(text, text.contains("Monthly report - 2026-09"));
        assertTrue(text.contains("Spending $     95.00"));
        assertTrue(text.contains("Dining"));
        assertTrue(text.contains("Budgets"));
        assertTrue(text.contains("EXCEEDED"));
    }

    @Test
    public void amountParsingAcceptsDollarsAndCommas() {
        assertEquals(1200.5, Ui.amount("$1,200.50"), 0.001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void badAmountIsAReadableError() {
        Ui.amount("twelve");
    }
}
