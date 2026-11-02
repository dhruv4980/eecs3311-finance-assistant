package com.financeassistant.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Concrete strategy: a simple text PDF (Letter size, built-in Helvetica and
 * Courier fonts) written directly in the PDF format, so no PDF library is
 * needed. Tables use the fixed-width font so columns line up.
 */
public class PdfExporter implements ReportExporter {
    private static final int PAGE_WIDTH = 612;
    private static final int PAGE_HEIGHT = 792;
    private static final int MARGIN = 54;
    private static final int TEXT_CHARS = 92;   // characters per line in 9pt Courier between the margins
    private static final int PROSE_CHARS = 100; // roughly, for 10pt Helvetica

    /** One line of output: which font, size, and how much space it takes. */
    private record Line(String font, int size, String text, int spaceBefore) {
    }

    @Override
    public String getFormat() {
        return "pdf";
    }

    @Override
    public String getExtension() {
        return ".pdf";
    }

    @Override
    public void export(ExportDocument document, OutputStream out) throws IOException {
        List<List<Line>> pages = paginate(layout(document));
        writePdf(pages, out);
    }

    private List<Line> layout(ExportDocument document) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("F2", 18, document.getTitle(), 0));
        lines.add(new Line("F3", 10, document.getSubtitle(), 4));
        for (ExportDocument.Section section : document.getSections()) {
            lines.add(new Line("F2", 13, section.getHeading(), 16));
            for (String paragraph : section.getParagraphs()) {
                boolean first = true;
                for (String wrapped : wrap(paragraph, PROSE_CHARS)) {
                    lines.add(new Line("F3", 10, wrapped, first ? 6 : 0));
                    first = false;
                }
            }
            if (section.hasTable()) {
                List<String> table = formatTable(section.getHeaders(), section.getRows());
                for (int i = 0; i < table.size(); i++) {
                    lines.add(new Line(i == 0 ? "F4" : "F1", 9, table.get(i), i == 0 ? 6 : 0));
                }
            }
        }
        return lines;
    }

    /** Lays the table out in fixed-width columns, shrinking the widest column if it does not fit. */
    static List<String> formatTable(List<String> headers, List<List<String>> rows) {
        int columns = headers.size();
        int[] width = new int[columns];
        for (int c = 0; c < columns; c++) {
            width[c] = headers.get(c).length();
            for (List<String> row : rows) {
                width[c] = Math.max(width[c], c < row.size() ? safe(row.get(c)).length() : 0);
            }
        }
        while (sum(width) + 2 * (columns - 1) > TEXT_CHARS) {
            int widest = 0;
            for (int c = 1; c < columns; c++) {
                if (width[c] > width[widest]) {
                    widest = c;
                }
            }
            width[widest]--;
        }
        List<String> lines = new ArrayList<>();
        lines.add(formatRow(headers, width, rows));
        for (List<String> row : rows) {
            lines.add(formatRow(row, width, rows));
        }
        return lines;
    }

    private static String formatRow(List<String> cells, int[] width, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < width.length; c++) {
            String cell = c < cells.size() ? safe(cells.get(c)) : "";
            if (cell.length() > width[c]) {
                cell = cell.substring(0, Math.max(0, width[c] - 1)) + "~";
            }
            boolean numeric = isNumericColumn(rows, c);
            sb.append(numeric ? " ".repeat(width[c] - cell.length()) + cell : cell + " ".repeat(width[c] - cell.length()));
            if (c < width.length - 1) {
                sb.append("  ");
            }
        }
        return sb.toString().stripTrailing();
    }

    private static boolean isNumericColumn(List<List<String>> rows, int column) {
        return !rows.isEmpty() && rows.stream().allMatch(r -> column < r.size()
                && safe(r.get(column)).matches("[-+]?\\$?[\\d,]*\\.?\\d+%?|"));
    }

    static List<String> wrap(String text, int max) {
        List<String> lines = new ArrayList<>();
        for (String para : safe(text).split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > max) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private List<List<Line>> paginate(List<Line> lines) {
        List<List<Line>> pages = new ArrayList<>();
        List<Line> page = new ArrayList<>();
        double y = PAGE_HEIGHT - MARGIN;
        for (Line line : lines) {
            double needed = line.spaceBefore() + line.size() * 1.3;
            if (y - needed < MARGIN && !page.isEmpty()) {
                pages.add(page);
                page = new ArrayList<>();
                y = PAGE_HEIGHT - MARGIN;
            }
            page.add(line);
            y -= needed;
        }
        pages.add(page);
        return pages;
    }

    /** Writes the PDF objects: catalog, page tree, fonts, then one page + content stream per page. */
    private void writePdf(List<List<Line>> pages, OutputStream out) throws IOException {
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int fontBase = 3;
        int pageBase = fontBase + 4;
        int objectCount = pageBase - 1 + pages.size() * 2;

        write(pdf, "%PDF-1.4\n%âãÏÓ\n");
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(pageBase + i * 2).append(" 0 R ");
        }
        object(pdf, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        object(pdf, offsets, 2, "<< /Type /Pages /Kids [" + kids.toString().strip() + "] /Count " + pages.size() + " >>");
        String[] fonts = {"Courier", "Helvetica-Bold", "Helvetica", "Courier-Bold"};
        for (int f = 0; f < fonts.length; f++) {
            object(pdf, offsets, fontBase + f, "<< /Type /Font /Subtype /Type1 /BaseFont /" + fonts[f]
                    + " /Encoding /WinAnsiEncoding >>");
        }
        String resources = "<< /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R /F4 6 0 R >> >>";
        for (int i = 0; i < pages.size(); i++) {
            int pageObj = pageBase + i * 2;
            String content = contentStream(pages.get(i), i + 1, pages.size());
            byte[] bytes = content.getBytes(StandardCharsets.ISO_8859_1);
            object(pdf, offsets, pageObj, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_WIDTH + " " + PAGE_HEIGHT
                    + "] /Resources " + resources + " /Contents " + (pageObj + 1) + " 0 R >>");
            offsets.add(pdf.size());
            write(pdf, (pageObj + 1) + " 0 obj\n<< /Length " + bytes.length + " >>\nstream\n");
            pdf.write(bytes);
            write(pdf, "\nendstream\nendobj\n");
        }
        int xref = pdf.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (objectCount + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            table.append(String.format("%010d 00000 n \n", offset));
        }
        table.append("trailer\n<< /Size ").append(objectCount + 1).append(" /Root 1 0 R >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        write(pdf, table.toString());
        pdf.writeTo(out);
    }

    private static String contentStream(List<Line> lines, int pageNumber, int pageCount) {
        StringBuilder sb = new StringBuilder();
        double y = PAGE_HEIGHT - MARGIN;
        for (Line line : lines) {
            y -= line.spaceBefore() + line.size() * 1.3;
            sb.append(String.format(java.util.Locale.ROOT, "BT /%s %d Tf %d %.1f Td (%s) Tj ET%n",
                    line.font(), line.size(), MARGIN, y + line.size() * 0.3, escape(line.text())));
        }
        sb.append(String.format(java.util.Locale.ROOT, "BT /F3 8 Tf %d %d Td (Page %d of %d) Tj ET%n",
                PAGE_WIDTH - MARGIN - 50, MARGIN / 2, pageNumber, pageCount));
        return sb.toString();
    }

    /** Escapes PDF string delimiters and replaces characters the built-in fonts cannot show. */
    static String escape(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : safe(text).toCharArray()) {
            if (c == '\\' || c == '(' || c == ')') {
                sb.append('\\').append(c);
            } else if (c == '—' || c == '–') {
                sb.append('-');
            } else if (c == '‘' || c == '’') {
                sb.append('\'');
            } else if (c == '“' || c == '”') {
                sb.append('"');
            } else if (c < 32 || c > 255) {
                sb.append(c == '\t' ? ' ' : '?');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void object(ByteArrayOutputStream pdf, List<Integer> offsets, int number, String body) throws IOException {
        offsets.add(pdf.size());
        write(pdf, number + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void write(ByteArrayOutputStream pdf, String text) throws IOException {
        pdf.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }
}
