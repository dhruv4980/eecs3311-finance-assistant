package com.financeassistant.export;

import java.nio.file.Path;

/** The chosen file already exists; the user decides whether to overwrite it. */
public class ExportFileExistsException extends Exception {
    private final Path path;

    public ExportFileExistsException(Path path) {
        super(path.toAbsolutePath() + " already exists. Overwrite it?");
        this.path = path;
    }

    public Path getPath() {
        return path;
    }
}
