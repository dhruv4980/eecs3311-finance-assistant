package com.financeassistant.subscription;

import com.financeassistant.categorization.CategorizationService;
import com.financeassistant.db.DatabaseManager;
import com.financeassistant.llm.LLMClient;
import com.financeassistant.llm.LLMException;
import com.financeassistant.model.Subscription;
import com.financeassistant.model.Transaction;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * F06 Manage subscriptions (hybrid).
 *
 * <p>Detection is deterministic: charges are grouped by merchant, and a group
 * counts as a subscription when it repeats at a regular interval (weekly,
 * every two weeks, monthly or yearly, within a few days) with similar
 * amounts. A later charge up to 30% higher is treated as a price increase.
 * Claude then labels each subscription's service type ("Music streaming"),
 * which is used to find overlapping services, and writes the overlap advice.
 */
public class SubscriptionService {
    static final double AMOUNT_TOLERANCE = 0.10;
    static final double MAX_PRICE_INCREASE = 0.30;
    static final int DATE_TOLERANCE_DAYS = 3;
    static final int MIN_OCCURRENCES = 3;
    static final int MIN_HISTORY_DAYS = 60;

    private static final String CLASSIFY_PROMPT = """
            You identify recurring charges from a bank statement. For each one give:
            - "name": the plain brand or payee name a person would use (e.g. "Netflix", "Apple Music", "Rent")
            - "type": the kind of service. Use short, general types so that similar services get the SAME type,
              e.g. "Music streaming", "Video streaming", "Gym membership", "Mobile phone", "Internet", "Rent",
              "Insurance", "Transit pass", "Delivery membership", "Cloud storage", "Loan payment", "Other".
            Reply with a single JSON object and nothing else.""";

    private final DatabaseManager db = DatabaseManager.getInstance();
    private final LLMClient llm;

    public SubscriptionService(LLMClient llm) {
        this.llm = llm;
    }

    // ------------------------------------------------------------ detection

    /** Scans all transactions, saves what it finds and labels new subscriptions with Claude. */
    public SubscriptionScan detectSubscriptions() {
        List<Transaction> expenses = db.find(Transaction.class, Transaction::isExpense);
        if (expenses.isEmpty()) {
            return new SubscriptionScan(List.of(), "No transactions yet. Import a statement (CSV) first.", null);
        }
        LocalDate first = expenses.stream().map(Transaction::getDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate last = expenses.stream().map(Transaction::getDate).max(Comparator.naturalOrder()).orElseThrow();
        if (ChronoUnit.DAYS.between(first, last) < MIN_HISTORY_DAYS) {
            return new SubscriptionScan(List.of(), "Only " + (ChronoUnit.DAYS.between(first, last) + 1)
                    + " days of history - import at least 2-3 months of statements to detect repeating charges.", null);
        }

        Map<String, List<Transaction>> byMerchant = new LinkedHashMap<>();
        for (Transaction t : expenses) {
            byMerchant.computeIfAbsent(CategorizationService.suggestPattern(t.getDescription()), k -> new ArrayList<>()).add(t);
        }
        Map<String, Subscription> existing = new LinkedHashMap<>();
        db.findAll(Subscription.class).forEach(s -> existing.put(s.getMerchantKey(), s));

        List<Subscription> found = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> group : byMerchant.entrySet()) {
            analyze(group.getKey(), group.getValue(), last).ifPresent(fresh -> {
                Subscription saved = existing.get(group.getKey());
                if (saved != null) {
                    saved.update(fresh.getAmount(), fresh.getPreviousAmount(), fresh.isPriceIncreased(), fresh.getFrequency(),
                            fresh.getFirstDate(), fresh.getLastDate(), fresh.getOccurrences(), fresh.getTotalSpent(), fresh.isActive());
                    found.add(saved);
                } else {
                    found.add(fresh);
                }
            });
        }
        db.saveAll(found);
        // forget auto-detected entries that no longer qualify; keep ones the user confirmed or dismissed
        for (Subscription old : existing.values()) {
            if (old.getStatus() == Subscription.Status.DETECTED && !found.contains(old)) {
                db.delete(Subscription.class, old.getId());
            }
        }

        String aiError = null;
        try {
            classifyServiceTypes(found.stream().filter(s -> s.getServiceType() == null).toList());
        } catch (LLMException e) {
            aiError = e.getMessage();
        }
        List<Subscription> visible = getSubscriptions();
        String message = visible.isEmpty() ? "No repeating charges found in " + byMerchant.size() + " merchants." : null;
        return new SubscriptionScan(visible, message, aiError);
    }

    /**
     * Decides whether one merchant's charges form a subscription. Needs at
     * least three charges, regular gaps and stable (or increasing) amounts.
     */
    Optional<Subscription> analyze(String merchantKey, List<Transaction> charges, LocalDate latestData) {
        if (charges.size() < MIN_OCCURRENCES) {
            return Optional.empty();
        }
        List<Transaction> sorted = charges.stream().sorted(Comparator.comparing(Transaction::getDate)).toList();
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(sorted.get(i - 1).getDate(), sorted.get(i).getDate()));
        }
        Subscription.Frequency frequency = frequencyOf(median(gaps));
        if (frequency == null || !gapsAreRegular(gaps, frequency)) {
            return Optional.empty();
        }
        boolean increased = false;
        double previous = -sorted.get(0).getAmount();
        double total = previous;
        double lastDifferent = previous;
        for (int i = 1; i < sorted.size(); i++) {
            double amount = -sorted.get(i).getAmount();
            double prev = -sorted.get(i - 1).getAmount();
            double ratio = amount / prev;
            if (ratio > 1 + AMOUNT_TOLERANCE * 0.1 && ratio <= 1 + MAX_PRICE_INCREASE) {
                increased = true;
                lastDifferent = prev;
            } else if (Math.abs(ratio - 1) > AMOUNT_TOLERANCE) {
                return Optional.empty(); // amounts jump around: a store, not a subscription
            }
            total += amount;
        }
        double amount = -sorted.get(sorted.size() - 1).getAmount();
        LocalDate lastDate = sorted.get(sorted.size() - 1).getDate();
        boolean active = ChronoUnit.DAYS.between(lastDate, latestData) <= frequency.days + DATE_TOLERANCE_DAYS * 2L;

        Subscription s = new Subscription(merchantKey);
        s.update(amount, increased ? lastDifferent : amount, increased, frequency, sorted.get(0).getDate(), lastDate,
                sorted.size(), Math.round(total * 100) / 100.0, active);
        return Optional.of(s);
    }

    private static Subscription.Frequency frequencyOf(long medianGap) {
        for (Subscription.Frequency f : Subscription.Frequency.values()) {
            int tolerance = f == Subscription.Frequency.YEARLY ? 15 : f == Subscription.Frequency.MONTHLY ? 4 : 1;
            if (Math.abs(medianGap - f.days) <= tolerance) {
                return f;
            }
        }
        return null;
    }

    /** At least three quarters of the gaps must be close to the frequency (months are 28-31 days). */
    private static boolean gapsAreRegular(List<Long> gaps, Subscription.Frequency frequency) {
        int tolerance = frequency == Subscription.Frequency.MONTHLY ? DATE_TOLERANCE_DAYS + 1
                : frequency == Subscription.Frequency.YEARLY ? 15 : 1;
        long regular = gaps.stream().filter(g -> Math.abs(g - frequency.days) <= tolerance).count();
        return regular * 4 >= gaps.size() * 3L;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    // ------------------------------------------------------------ AI labels and overlaps

    /** Asks Claude for each subscription's service type; saves the labels. */
    void classifyServiceTypes(List<Subscription> subscriptions) throws LLMException {
        if (subscriptions.isEmpty()) {
            return;
        }
        StringBuilder prompt = new StringBuilder("Recurring charges:\n");
        for (int i = 0; i < subscriptions.size(); i++) {
            Subscription s = subscriptions.get(i);
            prompt.append(i + 1).append(". ").append(s.getMerchantKey())
                    .append(String.format(Locale.ROOT, " ($%.2f %s)", s.getAmount(), s.getFrequency().name().toLowerCase(Locale.ROOT)))
                    .append('\n');
        }
        prompt.append("\nReply like {\"1\": {\"name\": \"Spotify\", \"type\": \"Music streaming\"}, "
                + "\"2\": {\"name\": \"GoodLife Fitness\", \"type\": \"Gym membership\"}}.");
        JsonObject json = parseJson(llm.generate(CLASSIFY_PROMPT, prompt.toString()));
        for (int i = 0; i < subscriptions.size(); i++) {
            JsonElement entry = json.get(String.valueOf(i + 1));
            Subscription s = subscriptions.get(i);
            String type = null;
            String name = null;
            if (entry != null && entry.isJsonObject()) {
                type = text(entry.getAsJsonObject().get("type"));
                name = text(entry.getAsJsonObject().get("name"));
            } else if (entry != null && entry.isJsonPrimitive()) {
                type = text(entry);
            }
            if (type != null) {
                s.setServiceType(capitalize(type));
            }
            // never overwrite a name the user chose
            if (name != null && s.getName().equals(s.getMerchantKey())) {
                s.setName(name);
            }
            db.save(s);
        }
    }

    /** Active subscriptions that share a service type, e.g. Spotify and Apple Music. Deterministic. */
    public Map<String, List<Subscription>> findOverlaps() {
        Map<String, List<Subscription>> byType = new LinkedHashMap<>();
        for (Subscription s : getSubscriptions()) {
            if (s.isActive() && s.getServiceType() != null && !s.getServiceType().equalsIgnoreCase("Other")) {
                byType.computeIfAbsent(s.getServiceType().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(s);
            }
        }
        byType.values().removeIf(list -> list.size() < 2);
        return byType;
    }

    /**
     * A short explanation of overlapping services and what cancelling one would
     * save. Claude writes it from the computed figures; if Claude is down a
     * plain template is used so the user still sees the overlap.
     */
    public String getOverlapMessage() {
        Map<String, List<Subscription>> overlaps = findOverlaps();
        if (overlaps.isEmpty()) {
            return "No overlapping subscriptions found.";
        }
        StringBuilder facts = new StringBuilder();
        for (List<Subscription> group : overlaps.values()) {
            facts.append(group.get(0).getServiceType()).append(": ");
            facts.append(String.join(", ", group.stream().map(s -> String.format(Locale.ROOT, "%s ($%.2f/month)",
                    s.getName(), s.getMonthlyCost())).toList()));
            facts.append('\n');
        }
        try {
            return llm.generate("You are a friendly budgeting assistant. Be brief (2-4 sentences) and use only the "
                            + "numbers given.",
                    "The user pays for several services of the same kind:\n" + facts
                            + "\nPoint out the overlap and say how much they would save per month and per year by keeping only one of each kind.");
        } catch (LLMException e) {
            StringBuilder text = new StringBuilder("You have overlapping subscriptions:\n").append(facts);
            for (List<Subscription> group : overlaps.values()) {
                double cheapest = group.stream().mapToDouble(Subscription::getMonthlyCost).min().orElse(0);
                double saving = group.stream().mapToDouble(Subscription::getMonthlyCost).sum() - cheapest;
                text.append(String.format(Locale.ROOT, "Keeping only the cheapest %s service would save about $%.2f a month ($%.2f a year).%n",
                        group.get(0).getServiceType().toLowerCase(Locale.ROOT), saving, saving * 12));
            }
            return text.toString().strip();
        }
    }

    // ------------------------------------------------------------ user actions

    /** Saved subscriptions the user has not dismissed, most expensive first. */
    public List<Subscription> getSubscriptions() {
        return db.find(Subscription.class, s -> s.getStatus() != Subscription.Status.NOT_SUBSCRIPTION).stream()
                .sorted(Comparator.comparing(Subscription::isActive).reversed()
                        .thenComparing(Comparator.comparingDouble(Subscription::getMonthlyCost).reversed()))
                .toList();
    }

    public Subscription confirm(int id) {
        return setStatus(id, Subscription.Status.CONFIRMED);
    }

    /** Hides a false positive; it stays hidden in later scans. */
    public Subscription markNotSubscription(int id) {
        return setStatus(id, Subscription.Status.NOT_SUBSCRIPTION);
    }

    public Subscription rename(int id, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The new name cannot be empty.");
        }
        Subscription s = find(id);
        s.setName(name.strip());
        return db.save(s);
    }

    private Subscription setStatus(int id, Subscription.Status status) {
        Subscription s = find(id);
        s.setStatus(status);
        return db.save(s);
    }

    private Subscription find(int id) {
        return db.find(Subscription.class, id)
                .orElseThrow(() -> new IllegalArgumentException("No subscription with id " + id));
    }

    private static JsonObject parseJson(String reply) throws LLMException {
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

    private static String text(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || element.getAsString().isBlank()) {
            return null;
        }
        return element.getAsString().strip();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
