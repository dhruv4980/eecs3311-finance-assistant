package com.financeassistant.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Concrete strategy: Markdown, readable as plain text and rendered nicely on GitHub or in notes apps. */
public class MarkdownExporter implements ReportExporter {

    @Override
    public String getFormat() {
        return "md";
    }

    @Override
    public String getExtension() {
        return ".md";
    }

    @Override
    public void export(ExportDocument document, OutputStream out) throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.println("# " + document.getTitle());
        writer.println();
        writer.println("_" + document.getSubtitle() + "_");
        for (ExportDocument.Section section : document.getSections()) {
            writer.println();
            writer.println("## " + section.getHeading());
            writer.println();
            for (String paragraph : section.getParagraphs()) {
                writer.println(paragraph);
                writer.println();
            }
            if (section.hasTable()) {
                writer.println(row(section.getHeaders()));
                writer.println(row(section.getHeaders().stream().map(h -> "---").toList()));
                for (List<String> r : section.getRows()) {
                    writer.println(row(r));
                }
            }
        }
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Could not write the Markdown file.");
        }
    }

    private static String row(List<String> cells) {
        return "| " + String.join(" | ", cells.stream().map(c -> c == null ? "" : c.replace("|", "\\|")).toList()) + " |";
    }
}
