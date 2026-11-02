package com.financeassistant.goal;

import com.financeassistant.model.Entity;
import java.time.LocalDate;

/**
 * A savings goal (context of the State pattern). Behaviour that depends on
 * whether the goal is finished is delegated to the current GoalState.
 */
public class Goal implements Entity {
    private int id;
    private String name;
    private double targetAmount;
    private LocalDate deadline;
    private double contributed;
    private LocalDate createdDate;
    private String stateName = InProgressState.NAME;

    /** Not stored: rebuilt from stateName by updateState() after loading. */
    private transient GoalState state;

    public Goal() {
    }

    public Goal(String name, double targetAmount, LocalDate deadline, LocalDate createdDate) {
        this.name = name;
        this.targetAmount = targetAmount;
        this.deadline = deadline;
        this.createdDate = createdDate;
    }

    public void addContribution(double amount) {
        state.addContribution(this, amount);
    }

    public String getMessage(GoalProgress progress) {
        return state.getMessage(this, progress);
    }

    public boolean showsRequiredContribution() {
        return state.showsRequiredContribution();
    }

    /** Picks the state object that matches the stored state name. */
    public void updateState(GoalState inProgress, GoalState completed) {
        this.state = CompletedState.NAME.equals(stateName) ? completed : inProgress;
    }

    void setState(GoalState state) {
        this.state = state;
        this.stateName = state.getName();
    }

    void increaseContributed(double amount) {
        contributed = Math.round((contributed + amount) * 100) / 100.0;
    }

    public boolean isCompleted() {
        return CompletedState.NAME.equals(stateName);
    }

    public GoalState getState() {
        return state;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public double getTargetAmount() {
        return targetAmount;
    }

    public LocalDate getDeadline() {
        return deadline;
    }

    public double getContributed() {
        return contributed;
    }

    public LocalDate getCreatedDate() {
        return createdDate;
    }
}
