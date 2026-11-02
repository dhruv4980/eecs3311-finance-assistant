package com.financeassistant.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.financeassistant.budget.BudgetService;
import com.financeassistant.categorization.CategorizationService;
import com.financeassistant.controller.AgentController;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.FakeLLMClient;
import com.financeassistant.model.CategorySource;
import com.financeassistant.model.ChatMessage;
import com.financeassistant.model.Transaction;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class FinanceAgentTest {
    private DatabaseManager db;

    @Before
    public void setUp() {
        db = DatabaseManager.open(null);
        add(LocalDate.of(2026, 9, 3), -40, "UBER EATS", "Dining");
        add(LocalDate.of(2026, 9, 8), -60, "UBER EATS", "Dining");
        add(LocalDate.of(2026, 9, 10), -100, "COSTCO", "Groceries");
        add(LocalDate.of(2026, 9, 15), 1850, "PAYROLL", "Income");
        add(LocalDate.of(2026, 9, 21), 1300, "PAYMENT THANK YOU", "Transfers");
        add(LocalDate.of(2026, 8, 3), -25, "UBER EATS", "Dining");
    }

    private void add(LocalDate date, double amount, String description, String category) {
        Transaction t = new Transaction(date, amount, description);
        t.setCategory(category, CategorySource.USER);
        db.save(t);
    }

    /** An LLM that plays back fixed replies in order. */
    private static FakeLLMClient scripted(String... replies) {
        Deque<String> queue = new ArrayDeque<>(List.of(replies));
        return new FakeLLMClient(prompt -> queue.isEmpty() ? "{\"tool\":\"get_spending\",\"args\":{}}" : queue.poll());
    }

    private FinanceAgent agent(FakeLLMClient llm, int maxSteps) {
        FinanceAgent agent = new FinanceAgent(llm, new ConversationMemory(10), maxSteps);
        agent.registerTool(new SpendingTool(new CategorizationService(llm)));
        agent.registerTool(new TransactionSearchTool());
        agent.registerTool(new BudgetTool(new BudgetService()));
        return agent;
    }

    @Test
    public void callsAToolThenAnswersFromItsResult() throws Exception {
        FakeLLMClient llm = scripted(
                "{\"tool\": \"get_spending\", \"args\": {\"month\": \"2026-09\"}}",
                "```json\n{\"answer\": \"You spent $200.00 in September.\"}\n```");
        AgentAnswer answer = agent(llm, 5).run("How much did I spend in September?");

        assertEquals(AgentAnswer.Status.ANSWERED, answer.status());
        assertEquals("You spent $200.00 in September.", answer.text());
        assertEquals(1, answer.toolCalls().size());
        assertEquals("get_spending", answer.toolCalls().get(0).tool());
        // the second prompt contains the real figures computed by the tool
        String second = llm.prompts.get(1);
        assertTrue(second, second.contains("\"totalSpending\":200.0"));
        assertTrue(second.contains("\"totalIncome\":1850.0")); // the card payment (Transfers) is not income
    }

    @Test
    public void conversationIsRememberedForFollowUps() throws Exception {
        FakeLLMClient llm = scripted("{\"answer\": \"$100.00 on dining.\"}", "{\"answer\": \"$25.00 in August.\"}");
        FinanceAgent agent = agent(llm, 5);
        agent.run("Dining in September?");
        agent.run("And the month before?");

        assertTrue(llm.prompts.get(1).contains("User: Dining in September?"));
        assertTrue(llm.prompts.get(1).contains("Assistant: $100.00 on dining."));
        List<ChatMessage> history = new ConversationMemory(10).getHistory();
        assertEquals(4, history.size());
        assertEquals(ChatMessage.Role.ASSISTANT, history.get(3).getRole());
    }

    @Test
    public void unknownToolAndBadArgumentsAreReportedBackToTheModel() throws Exception {
        FakeLLMClient llm = scripted(
                "{\"tool\": \"get_weather\", \"args\": {}}",
                "{\"tool\": \"get_budget_status\", \"args\": {\"month\": \"September\"}}",
                "{\"answer\": \"done\"}");
        AgentAnswer answer = agent(llm, 5).run("q");
        assertEquals("done", answer.text());
        assertTrue(llm.prompts.get(1).contains("Error: there is no tool called \"get_weather\""));
        assertTrue(llm.prompts.get(2).contains("Error: Month must look like 2026-09"));
    }

    @Test
    public void stopsAtTheStepLimit() throws Exception {
        FakeLLMClient llm = scripted(); // keeps calling tools forever
        AgentAnswer answer = agent(llm, 3).run("q");
        assertEquals(AgentAnswer.Status.STEP_LIMIT_REACHED, answer.status());
        assertEquals(3, llm.prompts.size());
        assertEquals(3, answer.toolCalls().size());
        assertTrue(answer.text().contains("couldn't finish"));
    }

    @Test
    public void plainTextReplyIsTreatedAsTheAnswer() throws Exception {
        AgentAnswer answer = agent(scripted("You have no data yet."), 3).run("q");
        assertEquals("You have no data yet.", answer.text());
        assertEquals(AgentAnswer.Status.ANSWERED, answer.status());
    }

    @Test
    public void systemPromptListsEveryTool() {
        String prompt = agent(scripted(), 3).systemPrompt();
        assertTrue(prompt.contains("get_spending"));
        assertTrue(prompt.contains("search_transactions"));
        assertTrue(prompt.contains("get_budget_status"));
    }

    @Test
    public void controllerReportsUnavailableAgentInsteadOfThrowing() {
        AgentController controller = new AgentController(FakeLLMClient.failing("timed out"));
        AgentAnswer answer = controller.askQuestion("hi");
        assertEquals(AgentAnswer.Status.UNAVAILABLE, answer.status());
        assertTrue(answer.text().contains("timed out"));
    }

    @Test
    public void searchToolFiltersSortsAndTotals() {
        String result = new TransactionSearchTool().execute(Map.of("text", "uber", "sort", "amount", "limit", 2.0));
        assertTrue(result, result.contains("\"matchCount\":3"));
        assertTrue(result.contains("\"matchTotal\":-125.0"));
        // largest spending first, and only 2 listed
        assertTrue(result.indexOf("-60.0") < result.indexOf("-40.0"));
        assertTrue(!result.contains("-25.0"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void budgetToolNeedsAMonth() {
        new BudgetTool(new BudgetService()).execute(Map.of());
    }
}
