package com.financeassistant.model;

import java.time.LocalDate;

/** A recurring charge found in the user's transactions (F06). */
public class Subscription implements Entity {
    public enum Frequency {
        WEEKLY(7, 52 / 12.0), BIWEEKLY(14, 26 / 12.0), MONTHLY(30, 1), YEARLY(365, 1 / 12.0);

        public final int days;
        private final double chargesPerMonth;

        Frequency(int days, double chargesPerMonth) {
            this.days = days;
            this.chargesPerMonth = chargesPerMonth;
        }

        /** Multiply a charge by this to get its cost per month. */
        public double perMonth() {
            return chargesPerMonth;
        }
    }

    public enum Status { DETECTED, CONFIRMED, NOT_SUBSCRIPTION }

    private int id;
    private String merchantKey;
    private String name;
    private double amount;
    private double previousAmount;
    private boolean priceIncreased;
    private Frequency frequency;
    private LocalDate firstDate;
    private LocalDate lastDate;
    private LocalDate nextExpected;
    private int occurrences;
    private double totalSpent;
    private boolean active;
    private String serviceType;
    private Status status = Status.DETECTED;

    public Subscription() {
    }

    public Subscription(String merchantKey) {
        this.merchantKey = merchantKey;
        this.name = merchantKey;
    }

    /** Updates the figures from a fresh detection run, keeping the user's name, status and AI label. */
    public void update(double amount, double previousAmount, boolean priceIncreased, Frequency frequency,
                       LocalDate firstDate, LocalDate lastDate, int occurrences, double totalSpent, boolean active) {
        this.amount = amount;
        this.previousAmount = previousAmount;
        this.priceIncreased = priceIncreased;
        this.frequency = frequency;
        this.firstDate = firstDate;
        this.lastDate = lastDate;
        this.nextExpected = frequency == Frequency.MONTHLY ? lastDate.plusMonths(1)
                : frequency == Frequency.YEARLY ? lastDate.plusYears(1) : lastDate.plusDays(frequency.days);
        this.occurrences = occurrences;
        this.totalSpent = totalSpent;
        this.active = active;
    }

    public double getMonthlyCost() {
        return Math.round(amount * frequency.perMonth() * 100) / 100.0;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void setId(int id) {
        this.id = id;
    }

    public String getMerchantKey() {
        return merchantKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getAmount() {
        return amount;
    }

    public double getPreviousAmount() {
        return previousAmount;
    }

    public boolean isPriceIncreased() {
        return priceIncreased;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public LocalDate getFirstDate() {
        return firstDate;
    }

    public LocalDate getLastDate() {
        return lastDate;
    }

    public LocalDate getNextExpected() {
        return nextExpected;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public double getTotalSpent() {
        return totalSpent;
    }

    public boolean isActive() {
        return active;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}
