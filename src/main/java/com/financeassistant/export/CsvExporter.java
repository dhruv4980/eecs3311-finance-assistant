package com.financeassistant.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Concrete strategy: CSV for spreadsheets. Each table becomes a block of rows under its heading. */
public class CsvExporter implements ReportExporter {

    @Override
    public String getFormat() {
        return "csv";
    }

    @Override
    public String getExtension() {
        return ".csv";
    }

    @Override
    public void export(ExportDocument document, OutputStream out) throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.print("﻿"); // byte-order mark so Excel reads the file as UTF-8
        writer.println(cell(document.getTitle()));
        writer.println(cell(document.getSubtitle()));
        for (ExportDocument.Section section : document.getSections()) {
            writer.println();
            writer.println(cell(section.getHeading()));
            for (String paragraph : section.getParagraphs()) {
                writer.println(cell(paragraph));
            }
            if (section.hasTable()) {
                writer.println(line(section.getHeaders()));
                for (List<String> row : section.getRows()) {
                    writer.println(line(row));
                }
            }
        }
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Could not write the CSV file.");
        }
    }

    private static String line(List<String> cells) {
        return String.join(",", cells.stream().map(CsvExporter::cell).toList());
    }

    /** Quotes a cell when it contains a comma, quote or line break. */
    static String cell(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
