package com.financeassistant.model;

import java.util.Objects;

/** A bank account that statements are imported into (e.g. TD Chequing). */
public class Account implements Entity {
    private int id;
    private String name;
    private String bankName;
    private String accountType;

    public Account() {
    }

    public Account(String bankName, String accountType) {
        this.bankName = bankName;
        this.accountType = accountType;
        this.name = bankName + " " + accountType;
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

    public String getBankName() {
        return bankName;
    }

    public String getAccountType() {
        return accountType;
    }

    /** Two accounts are the same account if the bank and type match (case-insensitive). */
    public boolean sameAs(String bank, String type) {
        return bankName != null && accountType != null
                && bankName.equalsIgnoreCase(bank) && accountType.equalsIgnoreCase(type);
    }

    @Override
    public String toString() {
        return Objects.toString(name, "");
    }
}
