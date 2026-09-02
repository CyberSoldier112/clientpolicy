package com.craftpilot.clientpolicy.store;

import com.craftpilot.clientpolicy.ClientPolicyPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * SQLite audit log. Every method here blocks and therefore must only ever be
 * called from an async task - see {@link ClientPolicyPlugin#runAsync(Runnable)}.
 * The main thread only reads the in-memory fingerprint cache.
 *
 * <p>The driver is provided at runtime through the {@code libraries:} block of
 * plugin.yml, so it is never shaded into the jar.</p>
 */
public final class DetectionStore {

    private static final String CREATE_DETECTIONS = """
            CREATE TABLE IF NOT EXISTS detections (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              uuid        TEXT    NOT NULL,
              name        TEXT    NOT NULL,
              mod_id      TEXT    NOT NULL,
              channels    TEXT    NOT NULL,
              brand       TEXT,
              profile     TEXT    NOT NULL,
              action      TEXT    NOT NULL,
              created_at  INTEGER NOT NULL
            )""";

    private static final String CREATE_DETECTIONS_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_det_uuid_time ON detections(uuid, created_at DESC)";

    private static final String CREATE_COUNTERS = """
            CREATE TABLE IF NOT EXISTS violation_counters (
              uuid        TEXT NOT NULL,
              mod_id      TEXT NOT NULL,
              count       INTEGER NOT NULL DEFAULT 0,
              last_at     INTEGER NOT NULL,
              PRIMARY KEY (uuid, mod_id)
            )""";

    private final ClientPolicyPlugin plugin;
    private final File databaseFile;
    private final ReentrantLock lock = new ReentrantLock();
    private final CountDownLatch ready = new CountDownLatch(1);

    private volatile boolean available;
    private volatile boolean closed;
    private Connection connection;

    public DetectionStore(ClientPolicyPlugin plugin, File databaseFile) {
        this.plugin = plugin;
        this.databaseFile = databaseFile;
    }

    /** Opens the database and creates the schema. Blocking - call from an async task. */
    public void initialize() {
        try {
            File parent = databaseFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                plugin.getLogger().severe("Could not create the plugin data folder, history is disabled.");
                return;
            }
            // Loaded through the plugin class loader that Paper's library loader populates.
            Class.forName("org.sqlite.JDBC");

            lock.lock();
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA journal_mode=WAL");
                    statement.execute("PRAGMA busy_timeout=5000");
                    statement.execute(CREATE_DETECTIONS);
                    statement.execute(CREATE_DETECTIONS_INDEX);
                    statement.execute(CREATE_COUNTERS);
                }
                available = true;
            } finally {
                lock.unlock();
            }
            plugin.getLogger().info("Detection store ready (" + databaseFile.getName() + ").");
        } catch (ClassNotFoundException ex) {
            plugin.getLogger().severe("SQLite driver missing - is the server allowed to download plugin libraries? "
                    + "Detection history is disabled, enforcement still works.");
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not open the detection database.", ex);
        } finally {
            ready.countDown();
        }
    }

    public boolean available() {
        return available && !closed;
    }

    /** Blocks the calling async thread until {@link #initialize()} has finished. */
    private boolean awaitReady() {
        try {
            if (!ready.await(20, TimeUnit.SECONDS)) {
                return false;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        return available && !closed;
    }

    /**
     * Increments the per-player, per-mod violation counter, restarting it when the
     * previous hit is older than the profile's {@code counter-reset-hours}.
     *
     * @return the new count, always at least 1 (also when storage is unavailable)
     */
    public int bumpCounter(UUID uuid, String modId, long now, long resetMillis) {
        if (!awaitReady()) {
            return 1;
        }
        lock.lock();
        try {
            if (!usable()) {
                return 1;
            }
            int count = 1;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT count, last_at FROM violation_counters WHERE uuid = ? AND mod_id = ?")) {
                select.setString(1, uuid.toString());
                select.setString(2, modId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        int previous = rs.getInt(1);
                        long lastAt = rs.getLong(2);
                        boolean expired = resetMillis > 0 && (now - lastAt) >= resetMillis;
                        count = expired ? 1 : previous + 1;
                    }
                }
            }
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO violation_counters (uuid, mod_id, count, last_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT(uuid, mod_id) DO UPDATE SET count = excluded.count, last_at = excluded.last_at""")) {
                upsert.setString(1, uuid.toString());
                upsert.setString(2, modId);
                upsert.setInt(3, count);
                upsert.setLong(4, now);
                upsert.executeUpdate();
            }
            return count;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not update the violation counter for " + modId, ex);
            return 1;
        } finally {
            lock.unlock();
        }
    }

    public void recordDetection(DetectionRecord record) {
        if (!awaitReady()) {
            return;
        }
        lock.lock();
        try {
            if (!usable()) {
                return;
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO detections (uuid, name, mod_id, channels, brand, profile, action, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                insert.setString(1, record.uuid().toString());
                insert.setString(2, record.name());
                insert.setString(3, record.modId());
                insert.setString(4, record.channels());
                insert.setString(5, record.brand());
                insert.setString(6, record.profile());
                insert.setString(7, record.action());
                insert.setLong(8, record.createdAt());
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not write a detection record.", ex);
        } finally {
            lock.unlock();
        }
    }

    public List<DetectionRecord> history(UUID uuid, int limit) {
        List<DetectionRecord> records = new ArrayList<>();
        if (!awaitReady()) {
            return records;
        }
        lock.lock();
        try {
            if (!usable()) {
                return records;
            }
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id, uuid, name, mod_id, channels, brand, profile, action, created_at
                    FROM detections WHERE uuid = ? ORDER BY created_at DESC LIMIT ?""")) {
                select.setString(1, uuid.toString());
                select.setInt(2, Math.max(1, limit));
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        records.add(new DetectionRecord(
                                rs.getLong("id"),
                                uuid,
                                rs.getString("name"),
                                rs.getString("mod_id"),
                                rs.getString("channels"),
                                rs.getString("brand"),
                                rs.getString("profile"),
                                rs.getString("action"),
                                rs.getLong("created_at")));
                    }
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not read the detection history.", ex);
        } finally {
            lock.unlock();
        }
        return records;
    }

    /** Deletes detections older than the cutoff and counters that can no longer escalate. */
    public int purgeOlderThan(long cutoff) {
        if (!awaitReady()) {
            return 0;
        }
        lock.lock();
        try {
            if (!usable()) {
                return 0;
            }
            int removed;
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM detections WHERE created_at < ?")) {
                delete.setLong(1, cutoff);
                removed = delete.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM violation_counters WHERE last_at < ?")) {
                delete.setLong(1, cutoff);
                delete.executeUpdate();
            }
            return removed;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not purge old detections.", ex);
            return 0;
        } finally {
            lock.unlock();
        }
    }

    private boolean usable() {
        try {
            return available && !closed && connection != null && !connection.isClosed();
        } catch (SQLException ex) {
            return false;
        }
    }

    public void close() {
        closed = true;
        ready.countDown();
        lock.lock();
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not close the detection database cleanly.", ex);
        } finally {
            connection = null;
            available = false;
            lock.unlock();
        }
    }
}
