package com.financeassistant.model;

/** Anything stored by DatabaseManager. Ids are assigned by the database on first save. */
public interface Entity {
    int getId();

    void setId(int id);
}
