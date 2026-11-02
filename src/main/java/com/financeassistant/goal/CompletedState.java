package com.financeassistant.goal;

import java.util.Locale;

/** The target has been reached: no more contributions, no required amount, a completion message. */
public class CompletedState implements GoalState {
    public static final String NAME = "COMPLETED";

    @Override
    public void addContribution(Goal goal, double amount) {
        throw new IllegalStateException("\"" + goal.getName() + "\" is already completed - create a new goal to keep saving.");
    }

    @Override
    public String getMessage(Goal goal, GoalProgress progress) {
        return String.format(Locale.ROOT, "Goal reached! You saved $%.2f for \"%s\" (target $%.2f).",
                goal.getContributed(), goal.getName(), goal.getTargetAmount());
    }

    @Override
    public boolean showsRequiredContribution() {
        return false;
    }

    @Override
    public String getName() {
        return NAME;
    }
}
