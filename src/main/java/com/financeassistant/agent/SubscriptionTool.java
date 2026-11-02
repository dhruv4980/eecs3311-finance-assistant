package com.financeassistant.agent;

import com.financeassistant.model.Subscription;
import com.financeassistant.subscription.SubscriptionService;
import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The user's recurring charges, their monthly cost and any overlapping services. */
public class SubscriptionTool implements Tool {
    private final SubscriptionService subscriptionService;
    private final Gson gson = new Gson();

    public SubscriptionTool(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Override
    public String getName() {
        return "get_subscriptions";
    }

    @Override
    public String getDescription() {
        return "Recurring charges (subscriptions, memberships, rent...) with amount, frequency, monthly cost, "
                + "next expected date, price increases, and groups of overlapping services.";
    }

    @Override
    public String getParameters() {
        return "none";
    }

    @Override
    public String execute(Map<String, Object> args) {
        List<Map<String, Object>> rows = subscriptionService.getSubscriptions().stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", s.getName());
            row.put("serviceType", s.getServiceType());
            row.put("amount", s.getAmount());
            row.put("frequency", s.getFrequency().name());
            row.put("monthlyCost", s.getMonthlyCost());
            row.put("nextExpected", String.valueOf(s.getNextExpected()));
            row.put("active", s.isActive());
            if (s.isPriceIncreased()) {
                row.put("priceIncreasedFrom", s.getPreviousAmount());
            }
            return row;
        }).toList();
        double monthly = subscriptionService.getSubscriptions().stream()
                .filter(Subscription::isActive).mapToDouble(Subscription::getMonthlyCost).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subscriptions", rows);
        result.put("activeMonthlyTotal", SpendingTool.round(monthly));
        Map<String, List<String>> overlaps = new LinkedHashMap<>();
        subscriptionService.findOverlaps().forEach((type, list) ->
                overlaps.put(type, list.stream().map(Subscription::getName).toList()));
        result.put("overlappingServices", overlaps);
        if (rows.isEmpty()) {
            result.put("note", "No subscriptions detected (needs 2-3 months of imported history).");
        }
        return gson.toJson(result);
    }
}
