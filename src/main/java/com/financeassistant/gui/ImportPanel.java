package com.financeassistant.gui;

import com.financeassistant.controller.AgentController;
import com.financeassistant.controller.ImportOutcome;
import com.financeassistant.importer.ImportException;
import com.financeassistant.importer.ImportResult;
import com.financeassistant.importer.RejectedRow;
import com.financeassistant.model.Transaction;
import java.awt.BorderLayout;
import java.io.File;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;

/** F01 + F02: choose bank, account and CSV file, preview the rows, then confirm. */
class ImportPanel extends JPanel implements Refreshable {
    private final AgentController controller;
    private final Runnable afterImport;
    private final JComboBox<String> bank = new JComboBox<>();
    private final JComboBox<String> account = new JComboBox<>(new String[]{"Chequing", "Savings", "Visa", "Mastercard"});
    private final JTextField file = new JTextField(30);
    private final JButton confirm = new JButton("Confirm import");
    private final DefaultTableModel rows = Ui.model("Date", "Amount", "Description");
    private final JTextArea notes = Ui.textArea(6);
    private ImportResult preview;

    ImportPanel(AgentController controller, Runnable afterImport) {
        super(new BorderLayout());
        this.controller = controller;
        this.afterImport = afterImport;
        account.setEditable(true);
        file.setEditable(false);
        controller.getSupportedBanks().forEach(bank::addItem);

        JButton browse = new JButton("Choose CSV...");
        browse.addActionListener(e -> chooseFile());
        JButton previewButton = new JButton("Preview");
        previewButton.addActionListener(e -> preview());
        confirm.setEnabled(false);
        confirm.addActionListener(e -> confirmImport());

        add(Ui.row("Bank:", bank, "Account type:", account, "File:", file, browse, previewButton, confirm), BorderLayout.NORTH);
        JTable table = Ui.table(rows);
        Ui.widths(table, 100, 100);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                Ui.titled(table, "Preview - nothing is saved until you confirm"), Ui.titled(notes, "Import summary"));
        split.setResizeWeight(0.75);
        add(split, BorderLayout.CENTER);
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(file.getText().isBlank() ? new File(".") : new File(file.getText()));
        chooser.setFileFilter(new FileNameExtensionFilter("Bank statement (*.csv)", "csv"));
        chooser.setAcceptAllFileFilterUsed(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            file.setText(chooser.getSelectedFile().getPath());
            preview();
        }
    }

    void preview() {
        confirm.setEnabled(false);
        rows.setRowCount(0);
        preview = null;
        if (file.getText().isBlank()) {
            Ui.error(this, "Choose a CSV file first.");
            return;
        }
        try {
            preview = controller.previewImport(new File(file.getText()), Ui.selected(bank), Ui.selected(account));
        } catch (ImportException | IllegalArgumentException e) {
            notes.setText(e.getMessage());
            Ui.error(this, e.getMessage());
            return;
        }
        for (Transaction t : preview.getValidRows()) {
            rows.addRow(new Object[]{t.getDate(), Ui.money(t.getAmount()), t.getDescription()});
        }
        StringBuilder text = new StringBuilder(preview.getSummary()).append('\n');
        for (RejectedRow r : preview.getRejectedRows()) {
            text.append("Rejected ").append(r).append('\n');
        }
        List<Transaction> repeated = preview.getRepeatedRows();
        if (!repeated.isEmpty()) {
            text.append(repeated.size()).append(" rows appear more than once in this file; they will all be imported.\n");
        }
        notes.setText(text.toString());
        confirm.setEnabled(!preview.getValidRows().isEmpty());
    }

    private void confirmImport() {
        ImportResult toSave = preview;
        confirm.setEnabled(false);
        notes.append("\nSaving and categorizing (this asks the AI for new merchants)...\n");
        Ui.background(this, () -> controller.confirmImport(toSave), (ImportOutcome outcome) -> {
            notes.setText(outcome.importResult().getSummary() + "\n" + outcome.categorization().summary());
            rows.setRowCount(0);
            afterImport.run();
        });
    }

    /** Lets other code (and the demo) start an import with a file already chosen. */
    void setFile(File chosen, String bankName, String accountType) {
        file.setText(chosen.getPath());
        bank.setSelectedItem(bankName);
        account.setSelectedItem(accountType);
    }

    @Override
    public void refresh() {
        // nothing to reload: the import form keeps whatever the user typed
    }
}
