package com.financeassistant.model;

/** Who decided a transaction's category. USER choices are never overwritten automatically. */
public enum CategorySource {
    NONE, RULE, AI, USER
}
