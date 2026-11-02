package com.financeassistant.goal;

import java.time.LocalDate;

/**
 * Computed facts about a goal. requiredMonthly is 0 once completed;
 * estimatedCompletion is null until there is at least one contribution.
 */
public record GoalProgress(double percent, double remaining, long monthsLeft, double requiredMonthly,
                           double averageMonthly, LocalDate estimatedCompletion, boolean onTrack) {
}
