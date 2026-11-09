package com.financeassistant.gui;

import com.financeassistant.config.Config;
import com.financeassistant.controller.AgentController;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * The main window. Like the CLI, it only talks to AgentController (the
 * facade), so both interfaces offer the same features. Each feature area is
 * a tab; a tab reloads its data whenever it is selected.
 */
public class GUI extends JFrame {
    private final AgentController controller;
    private final JTabbedPane tabs = new JTabbedPane();
    private final ImportPanel importPanel;
    private final TransactionsPanel transactionsPanel;
    private final BudgetPanel budgetPanel;
    private final SubscriptionsPanel subscriptionsPanel;
    private final GoalsPanel goalsPanel;
    private final GuiAlertPanel alertPanel = new GuiAlertPanel();

    public GUI(AgentController controller) {
        super("AI Personal Finance Assistant");
        this.controller = controller;
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 650));

        importPanel = new ImportPanel(controller, this::refresh);
        transactionsPanel = new TransactionsPanel(controller);
        budgetPanel = new BudgetPanel(controller);
        subscriptionsPanel = new SubscriptionsPanel(controller);
        goalsPanel = new GoalsPanel(controller);
        controller.addAlertObserver(alertPanel); // Observer: budget alerts appear here from any tab
        tabs.addTab("Import", importPanel);
        tabs.addTab("Transactions", transactionsPanel);
        tabs.addTab("Budgets", budgetPanel);
        tabs.addTab("Subscriptions", subscriptionsPanel);
        tabs.addTab("Goals", goalsPanel);
        tabs.addChangeListener(e -> refreshSelected());

        JLabel ai = new JLabel("AI: " + controller.describeAi());
        ai.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(ai, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(alertPanel, BorderLayout.SOUTH);
        pack();
        setSize(1180, 760);
        setLocationRelativeTo(null);
    }

    public void start() {
        setVisible(true);
        refreshSelected();
    }

    /** Reloads the visible tab, e.g. after an import changed the data. */
    public void refresh() {
        refreshSelected();
    }

    public void showImportDialog() {
        tabs.setSelectedComponent(importPanel);
    }

    public void showTransactions() {
        tabs.setSelectedComponent(transactionsPanel);
    }

    public void showBudgetPanel() {
        tabs.setSelectedComponent(budgetPanel);
    }

    public void showSubscriptionsTab() {
        tabs.setSelectedComponent(subscriptionsPanel);
    }

    public void showGoalsTab() {
        tabs.setSelectedComponent(goalsPanel);
    }

    SubscriptionsPanel getSubscriptionsPanel() {
        return subscriptionsPanel;
    }

    GuiAlertPanel getAlertPanel() {
        return alertPanel;
    }

    TransactionsPanel getTransactionsPanel() {
        return transactionsPanel;
    }

    void select(Component tab) {
        tabs.setSelectedComponent(tab);
    }

    ImportPanel getImportPanel() {
        return importPanel;
    }

    AgentController getController() {
        return controller;
    }

    private void refreshSelected() {
        if (tabs.getSelectedComponent() instanceof Refreshable r) {
            r.refresh();
        }
    }

    /** Opens the window on the Swing thread. */
    public static void launch(AgentController controller) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // the default look and feel is fine
            }
            new GUI(controller).start();
        });
    }

    public static void main(String[] args) {
        launch(AgentController.fromConfig(Config.load()));
    }
}
