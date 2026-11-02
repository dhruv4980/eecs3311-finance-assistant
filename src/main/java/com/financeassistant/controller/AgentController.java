package com.financeassistant.controller;

import com.financeassistant.agent.AgentAnswer;
import com.financeassistant.agent.BudgetTool;
import com.financeassistant.agent.ConversationMemory;
import com.financeassistant.agent.FinanceAgent;
import com.financeassistant.agent.SpendingTool;
import com.financeassistant.agent.TransactionSearchTool;
import com.financeassistant.budget.AlertObserver;
import com.financeassistant.budget.BudgetExistsException;
import com.financeassistant.budget.BudgetService;
import com.financeassistant.budget.BudgetStatus;
import com.financeassistant.categorization.CategorizationResult;
import com.financeassistant.categorization.CategorizationService;
import com.financeassistant.categorization.CategorySummary;
import com.financeassistant.categorization.RecategorizeResult;
import com.financeassistant.categorization.RuleConflictException;
import com.financeassistant.config.Config;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.importer.ImportException;
import com.financeassistant.importer.ImportResult;
import com.financeassistant.importer.ImportService;
import com.financeassistant.importer.ParserFactory;
import com.financeassistant.llm.LLMClient;
import com.financeassistant.llm.LLMClients;
import com.financeassistant.llm.LLMException;
import com.financeassistant.model.Budget;
import com.financeassistant.model.ChatMessage;
import com.financeassistant.model.CategoryRule;
import com.financeassistant.model.Transaction;
import java.io.File;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

/**
 * Facade between the user interfaces and the services. The GUI and the CLI
 * both talk only to this class, so they offer the same functionality and
 * neither needs to know how importing, categorizing or budgeting work.
 * It also sequences multi-service workflows (import -> categorize -> check budgets).
 */
public class AgentController {
    private final LLMClient llm;
    private final ImportService importService;
    private final CategorizationService categorizationService;
    private final BudgetService budgetService;
    private final ConversationMemory memory;
    private final FinanceAgent agent;

    public AgentController(LLMClient llm) {
        this.llm = llm;
        this.importService = new ImportService(new ParserFactory());
        this.categorizationService = new CategorizationService(llm);
        this.budgetService = new BudgetService();
        this.memory = new ConversationMemory(10);
        this.agent = new FinanceAgent(llm, memory, 6);
        agent.registerTool(new SpendingTool(categorizationService));
        agent.registerTool(new TransactionSearchTool());
        agent.registerTool(new BudgetTool(budgetService));
        agent.setContext(() -> {
            List<String> months = getMonths();
            return months.isEmpty() ? "The user has not imported any transactions yet."
                    : "Transactions exist for " + months.get(months.size() - 1) + " to " + months.get(0)
                    + ". Categories: " + String.join(", ", getAllowedCategories()) + ".";
        });
    }

    /** Builds a controller from .env / environment settings. */
    public static AgentController fromConfig(Config config) {
        return new AgentController(LLMClients.fromConfig(config));
    }

    public String describeAi() {
        return llm.describe();
    }

    // ------------------------------------------------------------ F01 import

    public List<String> getSupportedBanks() {
        return importService.supportedBanks();
    }

    public ImportResult previewImport(File file, String bank, String accountType) throws ImportException {
        return importService.previewImport(file, bank, accountType);
    }

    /** Saves the previewed rows, categorizes them (F02) and re-checks budgets (F05). */
    public ImportOutcome confirmImport(ImportResult preview) {
        ImportResult saved = importService.confirmImport(preview);
        CategorizationResult categorization = categorizationService.categorize(saved.getSavedRows());
        List<String> alerts = budgetService.checkAllThresholds();
        return new ImportOutcome(saved, categorization, alerts);
    }

    // ------------------------------------------------------------ F02 / F03 categories

    /** Runs categorization again for anything still Uncategorized (e.g. after Claude was down). */
    public CategorizationResult categorizeTransactions() {
        CategorizationResult result = categorizationService.categorizeUncategorized();
        budgetService.checkAllThresholds();
        return result;
    }

    public List<CategorySummary> getCategorySummaries(String month) {
        return categorizationService.getCategorySummaries(month);
    }

    public List<String> getAllowedCategories() {
        return categorizationService.getAllowedCategories();
    }

    public RecategorizeResult recategorize(int transactionId, String category, boolean applyToSimilar,
                                           boolean replaceConflictingRule) throws RuleConflictException {
        RecategorizeResult result = categorizationService.recategorize(
                transactionId, category, applyToSimilar, replaceConflictingRule);
        budgetService.checkAllThresholds();
        return result;
    }

    public List<CategoryRule> getRules() {
        return categorizationService.getRules();
    }

    public boolean deleteRule(int ruleId) {
        return categorizationService.deleteRule(ruleId);
    }

    /** Transactions, newest first. month and category are optional filters (null = all). */
    public List<Transaction> getTransactions(String month, String category) {
        return DatabaseManager.getInstance().find(Transaction.class,
                        t -> (month == null || month.equals(t.getMonth()))
                                && (category == null || category.equalsIgnoreCase(t.getCategory())))
                .stream()
                .sorted(Comparator.comparing(Transaction::getDate).reversed().thenComparing(Transaction::getId))
                .toList();
    }

    /** Months that have transactions, newest first. */
    public List<String> getMonths() {
        return DatabaseManager.getInstance().findAll(Transaction.class).stream()
                .map(Transaction::getMonth).distinct().sorted(Comparator.reverseOrder()).toList();
    }

    /** The newest month with data, or the current month if there is none. */
    public String getDefaultMonth() {
        List<String> months = getMonths();
        return months.isEmpty() ? YearMonth.now().toString() : months.get(0);
    }

    // ------------------------------------------------------------ F07 chat

    /** Asks the agent a question. If Claude cannot be reached the answer says so instead of throwing. */
    public AgentAnswer askQuestion(String text) {
        try {
            return agent.run(text);
        } catch (LLMException e) {
            return AgentAnswer.unavailable(e.getMessage());
        }
    }

    public List<ChatMessage> getChatHistory() {
        return memory.getHistory();
    }

    public void clearChat() {
        memory.clear();
    }

    // ------------------------------------------------------------ F04 / F05 budgets

    public Budget setBudget(String month, String category, double amount, boolean overwrite)
            throws BudgetExistsException {
        return budgetService.setBudget(month, category, amount, overwrite);
    }

    public List<BudgetStatus> getBudgetStatus(String month) {
        return budgetService.getStatus(month);
    }

    public boolean deleteBudget(String month, String category) {
        return budgetService.deleteBudget(month, category);
    }

    public void addAlertObserver(AlertObserver observer) {
        budgetService.attach(observer);
    }

    public void removeAlertObserver(AlertObserver observer) {
        budgetService.detach(observer);
    }
}
