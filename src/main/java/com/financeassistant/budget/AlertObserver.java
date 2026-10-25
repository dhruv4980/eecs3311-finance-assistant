package com.financeassistant.budget;

/** Observer: anything that wants to hear about budget alerts (GUI panel, CLI printer, ...). */
public interface AlertObserver {
    void onAlert(String message);
}
