package com.financeassistant.export;

import java.util.ArrayList;
import java.util.List;

/**
 * A report in a format-neutral shape - a title and sections of paragraphs
 * and tables. Every exporter strategy renders this same structure, so a new
 * format never needs to know where the data came from.
 */
public class ExportDocument {
    private final String title;
    private final String subtitle;
    private final List<Section> sections = new ArrayList<>();

    public ExportDocument(String title, String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    public Section addSection(String heading) {
        Section section = new Section(heading);
        sections.add(section);
        return section;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public List<Section> getSections() {
        return sections;
    }

    /** A heading followed by optional paragraphs and an optional table. */
    public static class Section {
        private final String heading;
        private final List<String> paragraphs = new ArrayList<>();
        private List<String> headers;
        private final List<List<String>> rows = new ArrayList<>();

        Section(String heading) {
            this.heading = heading;
        }

        public Section paragraph(String text) {
            paragraphs.add(text);
            return this;
        }

        public Section table(String... columnHeaders) {
            this.headers = List.of(columnHeaders);
            return this;
        }

        public Section row(String... cells) {
            rows.add(List.of(cells));
            return this;
        }

        public String getHeading() {
            return heading;
        }

        public List<String> getParagraphs() {
            return paragraphs;
        }

        public boolean hasTable() {
            return headers != null;
        }

        public List<String> getHeaders() {
            return headers;
        }

        public List<List<String>> getRows() {
            return rows;
        }
    }
}
