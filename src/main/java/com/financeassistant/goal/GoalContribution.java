package com.financeassistant.goal;

import com.financeassistant.model.Entity;
import java.time.LocalDate;

/** One amount the user put toward a goal; used to estimate their saving pace. */
public class GoalContribution implements Entity {
    private int id;
    private int goalId;
    private LocalDate date;
    private double amount;

    public GoalContribution() {
    }

    public GoalContribution(int goalId, LocalDate date, double amount) {
        this.goalId = goalId;
        this.date = date;
        this.amount = amount;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void setId(int id) {
        this.id = id;
    }

    public int getGoalId() {
        return goalId;
    }

    public LocalDate getDate() {
        return date;
    }

    public double getAmount() {
        return amount;
    }
}
