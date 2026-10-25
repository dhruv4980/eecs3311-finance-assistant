package com.financeassistant.categorization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.FakeLLMClient;
import com.financeassistant.model.Category;
import com.financeassistant.model.CategorySource;
import com.financeassistant.model.Transaction;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class CategorizationServiceTest {
    private DatabaseManager db;

    @Before
    public void setUp() {
        db = DatabaseManager.open(null);
    }

    private Transaction save(String description, double amount) {
        return db.save(new Transaction(LocalDate.of(2026, 9, 10), amount, description));
    }

    /** Answers every numbered line in the prompt with a category picked by keyword. */
    private static String answerByKeyword(String prompt) {
        StringBuilder json = new StringBuilder("```json\n{");
        for (String line : prompt.split("\n")) {
            if (line.matches("\\d+\\. .*")) {
                String n = line.substring(0, line.indexOf('.'));
                String category = line.contains("TIM") ? "dining" : line.contains("LOBLAWS") ? "Groceries" : "Spaceships";
                if (json.length() > 9) {
                    json.append(',');
                }
                json.append('"').append(n).append("\":\"").append(category).append('"');
            }
        }
        return json.append("}\n```").toString();
    }

    @Test
    public void rulesFirstThenClaudeForTheRest() throws Exception {
        FakeLLMClient llm = new FakeLLMClient(CategorizationServiceTest::answerByKeyword);
        CategorizationService service = new CategorizationService(llm);
        service.saveRule("RENT PAYMENT", "Rent", false);
        List<Transaction> txns = List.of(
                save("RENT PAYMENT - MAPLE", -1450),
                save("TIM HORTONS #1", -4),
                save("TIM HORTONS #1", -5),
                save("LOBLAWS #1024", -60),
                save("MYSTERY SHOP", -9));

        CategorizationResult result = service.categorize(txns);

        assertEquals(1, result.byRule());
        assertEquals(3, result.byAi());
        assertEquals(1, result.uncategorized()); // "Spaceships" is not an allowed category
        assertNull(result.aiError());
        assertEquals(1, llm.prompts.size());
        // identical descriptions are only sent once; the rule-matched one is not sent at all
        assertEquals(1, llm.prompts.get(0).split("TIM HORTONS").length - 1);
        assertTrue(!llm.prompts.get(0).contains("RENT PAYMENT"));

        Transaction coffee = db.find(Transaction.class, txns.get(1).getId()).orElseThrow();
        assertEquals("Dining", coffee.getCategory()); // case fixed to the allowed name
        assertEquals(CategorySource.AI, coffee.getCategorySource());
        assertEquals(CategorySource.RULE, db.find(Transaction.class, txns.get(0).getId()).orElseThrow().getCategorySource());
    }

    @Test
    public void claudeDownLeavesTransactionsUncategorized() {
        CategorizationService service = new CategorizationService(FakeLLMClient.failing("timed out"));
        CategorizationResult result = service.categorize(List.of(save("TIM HORTONS", -4)));
        assertEquals(1, result.uncategorized());
        assertTrue(result.summary().contains("timed out"));
    }

    @Test
    public void nonJsonReplyIsTreatedAsAiFailure() {
        CategorizationService service = new CategorizationService(new FakeLLMClient(p -> "Sure! Dining."));
        CategorizationResult result = service.categorize(List.of(save("TIM HORTONS", -4)));
        assertNotNull(result.aiError());
        assertEquals(1, result.uncategorized());
    }

    @Test
    public void largeImportsAreSentInBatches() {
        FakeLLMClient llm = new FakeLLMClient(CategorizationServiceTest::answerByKeyword);
        List<Transaction> txns = new ArrayList<>();
        for (int i = 0; i < CategorizationService.BATCH_SIZE + 5; i++) {
            txns.add(save("TIM HORTONS STORE " + (char) ('A' + i % 26) + (char) ('A' + i / 26), -3));
        }
        CategorizationResult result = new CategorizationService(llm).categorize(txns);
        assertEquals(2, llm.prompts.size());
        assertEquals(txns.size(), result.byAi());
    }

    @Test
    public void userChoicesAreNeverOverwritten() throws Exception {
        CategorizationService service = new CategorizationService(new FakeLLMClient(p -> "{\"1\":\"Dining\"}"));
        Transaction t = save("TIM HORTONS", -4);
        service.recategorize(t.getId(), "Groceries", false, false);
        service.categorize(db.findAll(Transaction.class));
        assertEquals("Groceries", db.find(Transaction.class, t.getId()).orElseThrow().getCategory());
    }

    @Test
    public void recategorizeWithRuleUpdatesSimilarAndFutureTransactions() throws Exception {
        CategorizationService service = new CategorizationService(FakeLLMClient.failing("off"));
        Transaction a = save("UBER* EATS 8842", -30);
        Transaction b = save("UBER* EATS 1234", -22);
        Transaction trip = save("UBER* TRIP", -15);

        RecategorizeResult result = service.recategorize(a.getId(), "dining", true, false);

        assertEquals("UBER* EATS", result.rule().getPattern());
        assertEquals(1, result.similarUpdated());
        assertEquals(CategorySource.USER, db.find(Transaction.class, a.getId()).orElseThrow().getCategorySource());
        assertEquals("Dining", db.find(Transaction.class, b.getId()).orElseThrow().getCategory());
        assertEquals(Category.UNCATEGORIZED, db.find(Transaction.class, trip.getId()).orElseThrow().getCategory());

        // a future import is matched by the saved rule without asking Claude
        Transaction future = save("UBER* EATS 5555", -18);
        CategorizationResult later = service.categorize(List.of(future));
        assertEquals(1, later.byRule());
    }

    @Test
    public void conflictingRuleMustBeConfirmed() throws Exception {
        CategorizationService service = new CategorizationService(FakeLLMClient.failing("off"));
        Transaction t = save("AMAZON.CA*2K4L", -40);
        service.saveRule("AMAZON.CA*2K4L", "Shopping", false);
        try {
            service.recategorize(t.getId(), "Education", true, false);
            fail("expected conflict");
        } catch (RuleConflictException e) {
            assertEquals("Shopping", e.getExisting().getCategory());
        }
        // nothing changed after the conflict
        assertEquals(Category.UNCATEGORIZED, db.find(Transaction.class, t.getId()).orElseThrow().getCategory());

        service.recategorize(t.getId(), "Education", true, true);
        assertEquals(1, service.getRules().size());
        assertEquals("Education", service.getRules().get(0).getCategory());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownCategoryIsRejected() throws Exception {
        Transaction t = save("X", -1);
        new CategorizationService(FakeLLMClient.failing("off")).recategorize(t.getId(), "Spaceships", false, false);
    }

    @Test
    public void suggestPatternKeepsTheMerchantName() {
        assertEquals("LOBLAWS", CategorizationService.suggestPattern("LOBLAWS #1024 TORONTO"));
        assertEquals("TIM HORTONS", CategorizationService.suggestPattern("Tim Hortons #2241"));
        assertEquals("SPOTIFY", CategorizationService.suggestPattern("SPOTIFY P2B4C1"));
        assertEquals("PAYROLL DEP YORK", CategorizationService.suggestPattern("PAYROLL DEP YORK CAMPUS SVCS"));
        assertEquals("#123", CategorizationService.suggestPattern("#123"));
    }

    @Test
    public void summariesShowTotalsAndPercentages() throws Exception {
        CategorizationService service = new CategorizationService(FakeLLMClient.failing("off"));
        service.recategorize(save("RENT", -750).getId(), "Rent", false, false);
        service.recategorize(save("FOOD", -200).getId(), "Groceries", false, false);
        service.recategorize(save("FOOD2", -50).getId(), "Groceries", false, false);
        save("PAYROLL", 2000); // income is not spending
        db.save(new Transaction(LocalDate.of(2026, 8, 1), -999, "OTHER MONTH"));

        List<CategorySummary> summaries = service.getCategorySummaries("2026-09");
        assertEquals(2, summaries.size());
        assertEquals("Rent", summaries.get(0).category());
        assertEquals(75.0, summaries.get(0).percent(), 0.01);
        assertEquals(250.0, summaries.get(1).total(), 0.01);
        assertEquals(2, summaries.get(1).count());
    }
}
