package com.financeassistant.budget;

import java.io.PrintStream;

/** Concrete observer that prints budget alerts to the console. */
public class CliAlertPrinter implements AlertObserver {
    private final PrintStream out;

    public CliAlertPrinter(PrintStream out) {
        this.out = out;
    }

    @Override
    public void onAlert(String message) {
        out.println("[ALERT] " + message);
    }
}
