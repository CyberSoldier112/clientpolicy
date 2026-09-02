package com.craftpilot.clientpolicy.fingerprint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory session fingerprint of one player. Created as early as the login
 * event so channel registrations that arrive before {@code PlayerJoinEvent} are
 * never lost, and dropped on quit.
 *
 * <p>Written from the main thread (events, scans) and read from async tasks,
 * hence the concurrent collections and volatile fields.</p>
 */
public final class ClientFingerprint {

    private final UUID uuid;
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private final Set<String> enforcedMods = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean rescanPending = new AtomicBoolean(false);
    private final long createdAt = System.currentTimeMillis();

    private volatile String name;
    private volatile String brand;
    private volatile boolean joined;
    private volatile boolean initialScanDone;
    private volatile long rescanDeadline;

    public ClientFingerprint(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    /** @return {@code true} when this channel had not been seen before */
    public boolean addChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return false;
        }
        return channels.add(channel.trim().toLowerCase(Locale.ROOT));
    }

    public int addChannels(Iterable<String> incoming) {
        int added = 0;
        for (String channel : incoming) {
            if (addChannel(channel)) {
                added++;
            }
        }
        return added;
    }

    /** Live view; iterate defensively or use {@link #sortedChannels()}. */
    public Set<String> channels() {
        return Collections.unmodifiableSet(channels);
    }

    public List<String> sortedChannels() {
        List<String> copy = new ArrayList<>(channels);
        Collections.sort(copy);
        return copy;
    }

    public String brand() {
        return brand;
    }

    public void brand(String brand) {
        if (brand != null && !brand.isBlank()) {
            this.brand = brand.trim();
        }
    }

    public ClientLoader loader() {
        return ClientLoader.detect(brand, channels);
    }

    public boolean joined() {
        return joined;
    }

    public void joined(boolean joined) {
        this.joined = joined;
    }

    public boolean initialScanDone() {
        return initialScanDone;
    }

    public void initialScanDone(boolean done) {
        this.initialScanDone = done;
    }

    public long rescanDeadline() {
        return rescanDeadline;
    }

    public void rescanDeadline(long deadline) {
        this.rescanDeadline = deadline;
    }

    public AtomicBoolean rescanPending() {
        return rescanPending;
    }

    public long createdAt() {
        return createdAt;
    }

    /**
     * Marks a mod id as already handled in this session.
     *
     * @return {@code true} when it was not handled before, i.e. the caller should act
     */
    public boolean markEnforced(String modId) {
        return enforcedMods.add(modId);
    }

    /**
     * Forgets which mods were already acted on, so the ladder can fire again.
     *
     * <p>Only {@code /clientpolicy rescan} does this. A config reload deliberately
     * does not, otherwise reloading three times would walk a player up the ladder
     * and kick them for a single offence.</p>
     */
    public void clearEnforced() {
        enforcedMods.clear();
    }
}
