package com.financeassistant.goal;

/**
 * State pattern: what a savings goal does depends on whether it is still in
 * progress or already completed. Goal delegates these operations to its
 * current state instead of checking "if completed" in every method.
 */
public interface GoalState {
    void addContribution(Goal goal, double amount);

    /** A short message about the goal's progress, shown to the user. */
    String getMessage(Goal goal, GoalProgress progress);

    /** Whether the UI should show "you need $X a month". */
    boolean showsRequiredContribution();

    /** Stored with the goal so the state survives a restart. */
    String getName();
}
