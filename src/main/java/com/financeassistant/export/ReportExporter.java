package com.financeassistant.export;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Strategy interface for writing a report in one file format. ExportService
 * picks the strategy that matches the format the user chose; adding a format
 * means adding one class, with no changes to the export workflow.
 */
public interface ReportExporter {
    /** Format name the user picks, e.g. "pdf". */
    String getFormat();

    /** File extension including the dot, e.g. ".pdf". */
    String getExtension();

    void export(ExportDocument document, OutputStream out) throws IOException;
}
