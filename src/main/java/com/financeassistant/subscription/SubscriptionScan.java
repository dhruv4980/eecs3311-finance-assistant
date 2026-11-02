package com.financeassistant.subscription;

import com.financeassistant.model.Subscription;
import java.util.List;

/**
 * Result of scanning for subscriptions. message explains an empty result
 * (no data, not enough history); aiError is set when Claude could not label
 * the services.
 */
public record SubscriptionScan(List<Subscription> subscriptions, String message, String aiError) {
}
