package com.arunai.dispatch.repository;

import com.arunai.dispatch.config.AppConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the SQLite database file and schema creation. Repositories open short
 * lived connections; WAL mode lets the HTTP threads and simulator cooperate.
 */
public class Database {
    private final Path path;

    public Database(Path path) {
        this.path = path.toAbsolutePath();
    }

    public Database() {
        this(AppConfig.DB_PATH);
    }

    public Path path() { return path; }

    public void initialise() {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
            }
            for (String sql : loadSchemaStatements()) {
                try (Connection connection = open(); Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialise SQLite database: " + ex.getMessage(), ex);
        }
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=8000");
        }
        return connection;
    }

    public <T> T transaction(TransactionWork<T> work) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Exception ex) {
                connection.rollback();
                if (ex instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Database transaction failed: " + ex.getMessage(), ex);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Database transaction failed: " + ex.getMessage(), ex);
        }
    }

    @FunctionalInterface
    public interface TransactionWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private List<String> loadSchemaStatements() throws IOException {
        try (var stream = Database.class.getResourceAsStream("/database/schema.sql")) {
            if (stream == null) throw new IOException("schema.sql not found on classpath");
            String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            List<String> statements = new ArrayList<>();
            for (String part : schema.split(";")) {
                String cleaned = stripComments(part).trim();
                if (!cleaned.isEmpty()) statements.add(cleaned);
            }
            return statements;
        }
    }

    private String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }
}
