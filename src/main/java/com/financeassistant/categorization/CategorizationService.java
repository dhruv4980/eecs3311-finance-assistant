package com.financeassistant.categorization;

import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.LLMClient;
import com.financeassistant.llm.LLMException;
import com.financeassistant.model.Category;
import com.financeassistant.model.CategoryRule;
import com.financeassistant.model.CategorySource;
import com.financeassistant.model.Transaction;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * F02 Automatic categorization and F03 manual correction.
 *
 * <p>Categorization is hybrid: the user's stored rules are applied first
 * (deterministic, free, instant); only descriptions no rule matches are sent
 * to Claude, together with the list of allowed categories. Claude's answer
 * is validated - anything outside the allowed list becomes Uncategorized.
 * Categories the user set by hand are never overwritten.
 */
public class CategorizationService {
    static final int BATCH_SIZE = 40;

    private static final String SYSTEM_PROMPT = """
            You categorize personal bank transactions for a budgeting app.
            Use only the categories you are given. Negative amounts are money spent,
            positive amounts are money received (salary, refunds, transfers in).
            If you cannot tell, use "Other". Reply with a single JSON object and nothing else.""";

    private final DatabaseManager db;
    private final LLMClient llm;

    public CategorizationService(LLMClient llm) {
        this.db = DatabaseManager.getInstance();
        this.llm = llm;
    }

    // ------------------------------------------------------------ F02

    /** Categorizes the given transactions and saves them. User-set categories are left alone. */
    public CategorizationResult categorize(List<Transaction> transactions) {
        List<CategoryRule> rules = getRules();
        List<Transaction> unmatched = new ArrayList<>();
        int byRule = 0;
        List<Transaction> changed = new ArrayList<>();
        for (Transaction t : transactions) {
            if (t.getCategorySource() == CategorySource.USER) {
                continue;
            }
            if (applyRules(t, rules)) {
                byRule++;
                changed.add(t);
            } else {
                unmatched.add(t);
            }
        }

        int byAi = 0;
        String aiError = null;
        if (!unmatched.isEmpty()) {
            try {
                Map<String, String> answers = askClaude(unmatched);
                for (Transaction t : unmatched) {
                    String category = answers.get(CategoryRule.normalize(t.getDescription()));
                    if (category != null) {
                        t.setCategory(category, CategorySource.AI);
                        byAi++;
                        changed.add(t);
                    }
                }
            } catch (LLMException e) {
                aiError = e.getMessage();
            }
        }
        db.saveAll(changed);
        int left = (int) transactions.stream()
                .filter(t -> t.getCategorySource() != CategorySource.USER && t.isUncategorized()).count();
        return new CategorizationResult(byRule, byAi, left, aiError);
    }

    /** Categorizes every stored transaction that is still Uncategorized. */
    public CategorizationResult categorizeUncategorized() {
        return categorize(db.find(Transaction.class, Transaction::isUncategorized));
    }

    /** Applies the best (longest) matching rule. Returns true if one matched. */
    public boolean applyRules(Transaction t, List<CategoryRule> rules) {
        Optional<CategoryRule> best = rules.stream()
                .filter(r -> r.matches(t.getDescription()))
                .max(Comparator.comparingInt(r -> r.getPattern().length()));
        best.ifPresent(r -> t.setCategory(r.getCategory(), CategorySource.RULE));
        return best.isPresent();
    }

    /**
     * Sends the unmatched descriptions to Claude in batches and returns
     * normalized description -> allowed category. Each distinct description is
     * sent once, which keeps token use low for repeated merchants.
     */
    Map<String, String> askClaude(List<Transaction> unmatched) throws LLMException {
        Map<String, Transaction> distinct = new LinkedHashMap<>();
        for (Transaction t : unmatched) {
            distinct.putIfAbsent(CategoryRule.normalize(t.getDescription()), t);
        }
        Map<String, String> allowed = allowedLookup();
        List<String> keys = new ArrayList<>(distinct.keySet());
        Map<String, String> answers = new LinkedHashMap<>();
        for (int start = 0; start < keys.size(); start += BATCH_SIZE) {
            List<String> batch = keys.subList(start, Math.min(keys.size(), start + BATCH_SIZE));
            String reply = llm.generate(SYSTEM_PROMPT, buildPrompt(batch, distinct));
            JsonObject json = parseJsonObject(reply);
            for (int i = 0; i < batch.size(); i++) {
                JsonElement value = json.get(String.valueOf(i + 1));
                if (value != null && value.isJsonPrimitive()) {
                    String category = allowed.get(value.getAsString().strip().toLowerCase(Locale.ROOT));
                    if (category != null && !category.equals(Category.UNCATEGORIZED)) {
                        answers.put(batch.get(i), category);
                    }
                }
            }
        }
        return answers;
    }

    private String buildPrompt(List<String> batch, Map<String, Transaction> distinct) {
        StringBuilder sb = new StringBuilder("Allowed categories: ");
        sb.append(String.join(", ", getAllowedCategories().stream()
                .filter(c -> !c.equals(Category.UNCATEGORIZED)).toList()));
        sb.append("\n\nTransactions:\n");
        for (int i = 0; i < batch.size(); i++) {
            Transaction t = distinct.get(batch.get(i));
            sb.append(i + 1).append(". ").append(t.getDescription())
                    .append(String.format(Locale.ROOT, " (amount %.2f)", t.getAmount())).append('\n');
        }
        sb.append("\nReply as JSON mapping each number to one category, e.g. {\"1\": \"Dining\", \"2\": \"Rent\"}.");
        return sb.toString();
    }

    /** Pulls the JSON object out of the reply, tolerating ```json fences or text around it. */
    static JsonObject parseJsonObject(String reply) throws LLMException {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LLMException("Claude's reply did not contain JSON.");
        }
        try {
            return JsonParser.parseString(reply.substring(start, end + 1)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new LLMException("Claude's reply was not valid JSON.", e);
        }
    }

    // ------------------------------------------------------------ F03

    /**
     * Sets a transaction's category by hand. With applyToSimilar, also saves a
     * rule from the description so future imports use it, and updates other
     * existing transactions that match (except ones the user set by hand).
     *
     * @throws RuleConflictException if a rule for the same pattern points elsewhere and replace is false
     */
    public RecategorizeResult recategorize(int transactionId, String category, boolean applyToSimilar,
                                           boolean replaceConflictingRule) throws RuleConflictException {
        Transaction t = db.find(Transaction.class, transactionId)
                .orElseThrow(() -> new IllegalArgumentException("No transaction with id " + transactionId));
        String canonical = canonicalCategory(category);

        CategoryRule rule = null;
        int similar = 0;
        if (applyToSimilar) {
            rule = saveRule(suggestPattern(t.getDescription()), canonical, replaceConflictingRule);
            List<Transaction> others = new ArrayList<>();
            for (Transaction other : db.find(Transaction.class, o -> o.getId() != transactionId)) {
                if (other.getCategorySource() != CategorySource.USER && rule.matches(other.getDescription())
                        && !canonical.equals(other.getCategory())) {
                    other.setCategory(canonical, CategorySource.RULE);
                    others.add(other);
                }
            }
            db.saveAll(others);
            similar = others.size();
        }
        t.setCategory(canonical, CategorySource.USER);
        db.save(t);
        return new RecategorizeResult(t, rule, similar);
    }

    /** Saves (or replaces) a rule. Saving the same pattern and category again is a no-op. */
    public CategoryRule saveRule(String pattern, String category, boolean replace) throws RuleConflictException {
        String canonical = canonicalCategory(category);
        String normalized = CategoryRule.normalize(pattern);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("A rule needs a non-empty pattern.");
        }
        Optional<CategoryRule> existing = getRules().stream()
                .filter(r -> r.getPattern().equals(normalized)).findFirst();
        if (existing.isPresent()) {
            CategoryRule rule = existing.get();
            if (rule.getCategory().equals(canonical)) {
                return rule;
            }
            if (!replace) {
                throw new RuleConflictException(rule, canonical);
            }
            rule.setCategory(canonical);
            return db.save(rule);
        }
        return db.save(new CategoryRule(normalized, canonical));
    }

    /**
     * Merchant part of a description: words up to the first store number or
     * reference code, at most three words. "LOBLAWS #1024 TORONTO" -> "LOBLAWS".
     */
    public static String suggestPattern(String description) {
        String normalized = CategoryRule.normalize(description);
        List<String> words = new ArrayList<>();
        for (String word : normalized.split(" ")) {
            if (word.startsWith("#") || word.chars().anyMatch(Character::isDigit) || words.size() == 3) {
                break;
            }
            words.add(word);
        }
        return words.isEmpty() ? normalized : String.join(" ", words);
    }

    public List<CategoryRule> getRules() {
        return db.findAll(CategoryRule.class);
    }

    public boolean deleteRule(int ruleId) {
        return db.delete(CategoryRule.class, ruleId);
    }

    // ------------------------------------------------------------ summaries

    /** Spending per category for a month (YYYY-MM), or for all time when month is null. Largest first. */
    public List<CategorySummary> getCategorySummaries(String month) {
        Map<String, double[]> totals = new LinkedHashMap<>();
        double all = 0;
        for (Transaction t : db.find(Transaction.class, x -> x.isExpense() && (month == null || month.equals(x.getMonth())))) {
            double[] entry = totals.computeIfAbsent(t.getCategory(), k -> new double[2]);
            entry[0] += -t.getAmount();
            entry[1]++;
            all += -t.getAmount();
        }
        List<CategorySummary> result = new ArrayList<>();
        for (Map.Entry<String, double[]> e : totals.entrySet()) {
            double total = Math.round(e.getValue()[0] * 100) / 100.0;
            double percent = all == 0 ? 0 : Math.round(e.getValue()[0] / all * 1000) / 10.0;
            result.add(new CategorySummary(e.getKey(), total, (int) e.getValue()[1], percent));
        }
        result.sort(Comparator.comparingDouble(CategorySummary::total).reversed());
        return result;
    }

    public List<String> getAllowedCategories() {
        return db.findAll(Category.class).stream().map(Category::getName).toList();
    }

    /** Matches a category name case-insensitively against the allowed list. */
    public String canonicalCategory(String name) {
        String found = name == null ? null : allowedLookup().get(name.strip().toLowerCase(Locale.ROOT));
        if (found == null) {
            throw new IllegalArgumentException("Unknown category '" + name + "'. Allowed: "
                    + String.join(", ", getAllowedCategories()));
        }
        return found;
    }

    private Map<String, String> allowedLookup() {
        Map<String, String> lookup = new LinkedHashMap<>();
        for (String name : getAllowedCategories()) {
            lookup.put(name.toLowerCase(Locale.ROOT), name);
        }
        return lookup;
    }
}
