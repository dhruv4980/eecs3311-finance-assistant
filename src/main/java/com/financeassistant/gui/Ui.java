package com.financeassistant.gui;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;

/** Small helpers shared by the tabs, so each tab only contains its own logic. */
final class Ui {
    private Ui() {
    }

    /**
     * Runs slow work (anything that may call the AI) off the Swing thread so the
     * window stays responsive, then hands the result back on the Swing thread.
     * Errors the user caused are shown as a message, not a stack trace.
     */
    static <T> void background(Component parent, Callable<T> work, Consumer<T> done) {
        Component root = SwingUtilities.getRoot(parent);
        if (root != null) {
            root.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        }
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                if (root != null) {
                    root.setCursor(Cursor.getDefaultCursor());
                }
                try {
                    done.accept(get());
                } catch (ExecutionException e) {
                    error(parent, e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    static void error(Component parent, Throwable problem) {
        String message = problem instanceof IllegalArgumentException || problem instanceof IllegalStateException
                ? problem.getMessage()
                : "Something went wrong: " + problem.getMessage();
        error(parent, message);
    }

    static void error(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Problem", JOptionPane.WARNING_MESSAGE);
    }

    static void info(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Done", JOptionPane.INFORMATION_MESSAGE);
    }

    static boolean confirm(Component parent, String question) {
        return JOptionPane.showConfirmDialog(parent, question, "Please confirm", JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION;
    }

    /** A read-only table model. */
    static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    static JTable table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        return table;
    }

    /** Sets preferred column widths in pixels (the last column takes the rest). */
    static void widths(JTable table, int... pixels) {
        for (int i = 0; i < pixels.length && i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(pixels[i]);
            table.getColumnModel().getColumn(i).setMaxWidth(pixels[i] * 3);
        }
    }

    /** Model row of the selected table row (accounts for sorting), or -1. */
    static int selectedRow(JTable table) {
        int view = table.getSelectedRow();
        return view < 0 ? -1 : table.convertRowIndexToModel(view);
    }

    static JTextArea textArea(int rows) {
        JTextArea area = new JTextArea(rows, 40);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    static JScrollPane titled(JComponent content, String title) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createTitledBorder(title));
        return scroll;
    }

    /** A left-aligned row of controls; plain strings become labels. */
    static JPanel row(Object... items) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        for (Object item : items) {
            panel.add(item instanceof Component c ? c : new JLabel(String.valueOf(item)));
        }
        return panel;
    }

    /** Replaces a combo box's items, keeping the current choice if it is still there. */
    static void fill(JComboBox<String> box, List<String> items, String fallback) {
        Object current = box.getSelectedItem();
        box.removeAllItems();
        items.forEach(box::addItem);
        if (current != null && items.contains(current.toString())) {
            box.setSelectedItem(current);
        } else if (fallback != null) {
            box.setSelectedItem(fallback);
        }
    }

    static String selected(JComboBox<String> box) {
        Object item = box.getSelectedItem();
        return item == null ? null : Objects.toString(item).strip();
    }

    static String money(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Parses an amount typed by the user ("$1,200.50" is fine). */
    static double amount(String text) {
        try {
            return Double.parseDouble(text.replace("$", "").replace(",", "").strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Please enter an amount like 200 or 49.99.");
        }
    }
}
