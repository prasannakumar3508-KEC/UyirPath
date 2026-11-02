package com.uyirpath.dispatch.repository;

import com.uyirpath.dispatch.config.AppConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Key/value application settings with safe defaults. */
public class SettingsRepository {
    private final Database db;

    public SettingsRepository(Database db) {
        this.db = db;
    }

    public void ensureDefaults() {
        String sql = "INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) " +
                "ON CONFLICT(key) DO NOTHING";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : AppConfig.defaultSettings().entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setString(2, entry.getValue());
                ps.setString(3, Instant.now().toString());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot seed settings", ex);
        }
    }

    public Map<String, String> getAll() {
        Map<String, String> settings = new LinkedHashMap<>(AppConfig.defaultSettings());
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT key,value FROM settings ORDER BY key");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) settings.put(rs.getString("key"), rs.getString("value"));
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot read settings", ex);
        }
        return settings;
    }

    public String get(String key, String fallback) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : fallback;
            }
        } catch (SQLException ex) {
            return fallback;
        }
    }

    public void put(String key, String value) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) " +
                             "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot write setting " + key, ex);
        }
    }

    public void putAll(Map<String, String> values) {
        if (values == null || values.isEmpty()) return;
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO settings(key,value,updated_at) VALUES(?,?,?) " +
                            "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")) {
                String now = Instant.now().toString();
                for (Map.Entry<String, String> e : values.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue());
                    ps.setString(3, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot write settings", ex);
        }
    }
}
