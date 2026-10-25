package com.financeassistant.db;

import com.financeassistant.config.Config;
import com.financeassistant.model.Category;
import com.financeassistant.model.Entity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Singleton that owns the application's single local database.
 *
 * <p>Every service (ImportService, CategorizationService, BudgetService, ...)
 * calls {@link #getInstance()} instead of creating its own connection, so the
 * whole application reads and writes one consistent store. Data is kept in a
 * human-readable JSON file (one "table" per entity class) and written to disk
 * after every change. Objects returned to callers are copies: a change only
 * reaches the database when the caller saves it.
 */
public final class DatabaseManager {

    private static DatabaseManager instance;

    private final Gson gson;
    private final Path file;
    private final Map<String, JsonArray> tables = new LinkedHashMap<>();
    private int nextId = 1;

    /** Private: other classes must use getInstance(). */
    private DatabaseManager(Path file) {
        this.file = file;
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
                .setPrettyPrinting()
                .create();
        load();
        seedDefaults();
    }

    /** Returns the shared instance, opening the file named by DB_PATH on first use. */
    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            String path = Config.load().get("DB_PATH", "data/finance-db.json");
            instance = new DatabaseManager(Path.of(path));
        }
        return instance;
    }

    /**
     * Points the singleton at a different file (or an in-memory store when
     * {@code file} is null). Used by the CLI's --db option and by tests.
     */
    public static synchronized DatabaseManager open(Path file) {
        instance = new DatabaseManager(file);
        return instance;
    }

    // ---------------------------------------------------------------- queries

    public synchronized <T extends Entity> T save(T entity) {
        upsert(entity);
        flush();
        return entity;
    }

    /** Saves many entities with a single write to disk. */
    public synchronized void saveAll(Collection<? extends Entity> entities) {
        for (Entity entity : entities) {
            upsert(entity);
        }
        flush();
    }

    public synchronized <T extends Entity> Optional<T> find(Class<T> type, int id) {
        for (JsonElement row : table(type)) {
            if (row.getAsJsonObject().get("id").getAsInt() == id) {
                return Optional.of(gson.fromJson(row, type));
            }
        }
        return Optional.empty();
    }

    public synchronized <T extends Entity> List<T> findAll(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (JsonElement row : table(type)) {
            result.add(gson.fromJson(row, type));
        }
        return result;
    }

    public synchronized <T extends Entity> List<T> find(Class<T> type, Predicate<T> filter) {
        List<T> result = new ArrayList<>();
        for (T item : findAll(type)) {
            if (filter.test(item)) {
                result.add(item);
            }
        }
        return result;
    }

    public synchronized boolean delete(Class<? extends Entity> type, int id) {
        JsonArray rows = table(type);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getAsJsonObject().get("id").getAsInt() == id) {
                rows.remove(i);
                flush();
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- internals

    private void upsert(Entity entity) {
        if (entity.getId() == 0) {
            entity.setId(nextId++);
        } else if (entity.getId() >= nextId) {
            nextId = entity.getId() + 1;
        }
        JsonArray rows = table(entity.getClass());
        JsonElement json = gson.toJsonTree(entity);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getAsJsonObject().get("id").getAsInt() == entity.getId()) {
                rows.set(i, json);
                return;
            }
        }
        rows.add(json);
    }

    private JsonArray table(Class<?> type) {
        return tables.computeIfAbsent(type.getSimpleName(), k -> new JsonArray());
    }

    private void seedDefaults() {
        if (table(Category.class).isEmpty()) {
            for (String name : Category.DEFAULTS) {
                upsert(new Category(name));
            }
            flush();
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return;
            }
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            nextId = root.get("nextId").getAsInt();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("tables").entrySet()) {
                tables.put(entry.getKey(), entry.getValue().getAsJsonArray());
            }
        } catch (IOException | JsonParseException | IllegalStateException | NullPointerException e) {
            throw new IllegalStateException("The database file " + file
                    + " is unreadable or corrupted. Move it aside to start fresh.", e);
        }
    }

    /** Writes to a temp file then renames it, so a crash cannot leave a half-written database. */
    private void flush() {
        if (file == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("nextId", nextId);
        JsonObject all = new JsonObject();
        tables.forEach(all::add);
        root.add("tables", all);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save the database to " + file + ": " + e.getMessage(), e);
        }
    }

    /** Stores dates as ISO strings (2026-09-14). */
    private static final class LocalDateAdapter extends TypeAdapter<LocalDate> {
        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public LocalDate read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return LocalDate.parse(in.nextString());
        }
    }
}
