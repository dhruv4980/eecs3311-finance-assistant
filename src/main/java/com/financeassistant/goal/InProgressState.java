package com.financeassistant.goal;

import com.financeassistant.llm.LLMClient;
import com.financeassistant.llm.LLMException;
import java.util.Locale;

/**
 * The goal is still being saved for: contributions count toward the target,
 * the required monthly amount is shown, and Claude writes a short progress
 * message (with suggestions when the goal is behind). Reaching the target
 * moves the goal to CompletedState.
 */
public class InProgressState implements GoalState {
    public static final String NAME = "IN_PROGRESS";

    private static final String SYSTEM_PROMPT = """
            You are an encouraging budgeting coach. Write 2-3 short sentences about the user's savings goal
            using ONLY the numbers given. If the goal is behind schedule, suggest one or two realistic
            adjustments (save more per month, extend the deadline, or lower the target). No investment advice.""";

    private final LLMClient llm;
    private final GoalState completed;

    public InProgressState(LLMClient llm, GoalState completed) {
        this.llm = llm;
        this.completed = completed;
    }

    @Override
    public void addContribution(Goal goal, double amount) {
        goal.increaseContributed(amount);
        if (goal.getContributed() >= goal.getTargetAmount() - 0.005) {
            goal.setState(completed);
        }
    }

    @Override
    public String getMessage(Goal goal, GoalProgress p) {
        String facts = String.format(Locale.ROOT,
                "Goal: %s. Target $%.2f by %s. Saved so far $%.2f (%.0f%%). Remaining $%.2f. Months left: %d. "
                        + "Needed per month from now: $%.2f. Average saved per month so far: $%.2f. %s",
                goal.getName(), goal.getTargetAmount(), goal.getDeadline(), goal.getContributed(), p.percent(),
                p.remaining(), p.monthsLeft(), p.requiredMonthly(), p.averageMonthly(),
                p.estimatedCompletion() == null ? "No contributions yet, so no completion estimate."
                        : "At that pace it would be reached around " + p.estimatedCompletion()
                        + (p.onTrack() ? ", which is on time." : ", which is AFTER the deadline."));
        try {
            return llm.generate(SYSTEM_PROMPT, facts);
        } catch (LLMException e) {
            return templateMessage(goal, p);
        }
    }

    /** Used when Claude is unavailable, so the user still gets the key facts. */
    static String templateMessage(Goal goal, GoalProgress p) {
        String pace = p.estimatedCompletion() == null ? "Add a first contribution to get started."
                : p.onTrack() ? "You're on track to finish by " + p.estimatedCompletion() + "."
                : "At your current pace you'd finish around " + p.estimatedCompletion()
                + ", after the deadline - try saving a bit more each month or moving the deadline.";
        return String.format(Locale.ROOT, "%.0f%% of the way to \"%s\". Save $%.2f a month to reach $%.2f by %s. %s",
                p.percent(), goal.getName(), p.requiredMonthly(), goal.getTargetAmount(), goal.getDeadline(), pace);
    }

    @Override
    public boolean showsRequiredContribution() {
        return true;
    }

    @Override
    public String getName() {
        return NAME;
    }
}
