package com.craftpilot.clientpolicy.fingerprint;

import com.craftpilot.clientpolicy.ClientPolicyPlugin;
import com.craftpilot.clientpolicy.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects what the client reveals about itself and decides when to evaluate it.
 *
 * <p>Timing is the fragile part of this plugin, so the collection window is wider
 * than the evaluation window:</p>
 * <ol>
 *   <li>A fingerprint is created at login - or by the channel listener itself, which
 *       can fire during the configuration phase - so registrations that arrive before
 *       {@link PlayerJoinEvent} are buffered instead of lost.</li>
 *   <li>{@code scan-delay-ticks} after join the first evaluation runs.</li>
 *   <li>For the next {@code rescan-window-seconds} every newly registered channel
 *       schedules a debounced re-evaluation, so a mod cannot dodge enforcement by
 *       registering late.</li>
 *   <li>A final pass closes the window. Only that pass may act on a missing brand,
 *       which keeps a brand that is merely slow from being treated as absent.</li>
 * </ol>
 *
 * <p>Unregistered channels are deliberately never forgotten: a client that registers
 * and immediately unregisters has still revealed the mod.</p>
 */
public final class FingerprintService implements Listener {

    /** Coalescing delay for re-evaluations triggered by a late channel registration. */
    private static final long RESCAN_DEBOUNCE_TICKS = 20L;

    private final ClientPolicyPlugin plugin;
    private final Map<UUID, ClientFingerprint> sessions = new ConcurrentHashMap<>();

    public FingerprintService(ClientPolicyPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lookups

    public ClientFingerprint get(UUID uuid) {
        return uuid == null ? null : sessions.get(uuid);
    }

    public ClientFingerprint getOrCreate(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(),
                uuid -> new ClientFingerprint(uuid, player.getName()));
    }

    public Collection<ClientFingerprint> sessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public void clear() {
        sessions.clear();
    }

    // ------------------------------------------------------------------ events

    /** Earliest point at which a {@link Player} object exists for the connection. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) {
            getOrCreate(event.getPlayer());
        }
    }

    /**
     * May fire before, during or long after the join event; all three cases end up in
     * the same buffer and only the post-scan ones trigger a re-evaluation.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        ClientFingerprint fingerprint = getOrCreate(player);
        boolean isNew = fingerprint.addChannel(event.getChannel());
        if (isNew && fingerprint.initialScanDone()) {
            requestRescan(fingerprint);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ClientFingerprint fingerprint = getOrCreate(player);
        fingerprint.name(player.getName());
        fingerprint.joined(true);
        beginScanCycle(player, fingerprint);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ scanning

    /** Schedules the first scan and the closing pass for a freshly joined player. */
    private void beginScanCycle(Player player, ClientFingerprint fingerprint) {
        PluginConfig config = plugin.config();
        UUID uuid = player.getUniqueId();

        long delay = config.scanDelayTicks();
        long windowTicks = config.rescanWindowSeconds() * 20L;
        boolean hasWindow = windowTicks > 0;
        fingerprint.rescanDeadline(System.currentTimeMillis()
                + (delay * 50L) + config.rescanWindowMillis());

        plugin.runLater(() -> {
            ClientFingerprint live = sessions.get(uuid);
            if (live != fingerprint) {
                return; // player left and possibly rejoined with a new session
            }
            fingerprint.initialScanDone(true);
            // With rescanning switched off there is no later pass, so this scan has to
            // be the one allowed to act on a brand that never arrived.
            scan(uuid, fingerprint, !hasWindow);
        }, delay);

        if (hasWindow) {
            plugin.runLater(() -> {
                ClientFingerprint live = sessions.get(uuid);
                if (live != fingerprint) {
                    return;
                }
                scan(uuid, fingerprint, true);
            }, delay + windowTicks);
        }
    }

    /**
     * Queues one re-evaluation for a late registration. Repeated calls inside the
     * debounce window collapse into a single scan, so a mod that registers twenty
     * channels at once is evaluated once rather than twenty times.
     */
    private void requestRescan(ClientFingerprint fingerprint) {
        if (System.currentTimeMillis() > fingerprint.rescanDeadline()) {
            return;
        }
        if (!fingerprint.rescanPending().compareAndSet(false, true)) {
            return;
        }
        UUID uuid = fingerprint.uuid();
        plugin.runLater(() -> {
            fingerprint.rescanPending().set(false);
            if (sessions.get(uuid) == fingerprint) {
                scan(uuid, fingerprint, false);
            }
        }, RESCAN_DEBOUNCE_TICKS);
    }

    /**
     * Refreshes the fingerprint from the live connection and hands it to enforcement.
     * Must run on the main thread.
     *
     * @param finalPass see {@link com.craftpilot.clientpolicy.policy.PolicyEngine#evaluate}
     */
    private void scan(UUID uuid, ClientFingerprint fingerprint, boolean finalPass) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        refresh(player, fingerprint);
        plugin.enforcement().evaluateAndEnforce(player, fingerprint, finalPass);
    }

    /**
     * Backfills from the connection's own channel set - the authoritative list Paper
     * maintains - and re-reads the brand, which is frequently still null at join time.
     */
    public void refresh(Player player, ClientFingerprint fingerprint) {
        fingerprint.name(player.getName());
        fingerprint.addChannels(player.getListeningPluginChannels());
        if (plugin.config().trackBrand()) {
            fingerprint.brand(readBrand(player));
        }
    }

    /**
     * Reads {@code minecraft:brand} from the connection.
     *
     * <p>The accessor is deprecated in the Paper API because a client can send any brand
     * string it likes. That caveat is understood and documented: the brand is treated as a
     * hint that is combined with channel evidence, never as proof on its own, so the
     * deprecation is suppressed rather than the signal dropped.</p>
     *
     * @return the brand, or {@code null} if it has not arrived yet or could not be read
     */
    @SuppressWarnings("deprecation")
    private String readBrand(Player player) {
        try {
            return player.getClientBrandName();
        } catch (RuntimeException ex) {
            // A disconnecting player can throw here; a missing brand is handled by policy.
            plugin.getLogger().fine("Could not read the client brand of " + player.getName());
            return null;
        }
    }

    /** Immediate, manual re-run used by {@code /clientpolicy rescan}. */
    public void forceRescan(Player player) {
        ClientFingerprint fingerprint = getOrCreate(player);
        fingerprint.clearEnforced();
        scan(player.getUniqueId(), fingerprint, true);
    }

    /**
     * Re-applies the current policy to everyone online.
     *
     * @param fresh {@code true} clears the per-session "already acted on" marks, letting
     *              the ladder fire again; {@code false} only reports newly introduced
     *              violations, which is what a config reload should do
     */
    public void reevaluateOnlinePlayers(boolean fresh) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ClientFingerprint fingerprint = getOrCreate(player);
            if (fresh) {
                fingerprint.clearEnforced();
            }
            scan(player.getUniqueId(), fingerprint, fingerprint.initialScanDone());
        }
    }

    /** Rebuilds sessions after a plugin manager reload, where join events were missed. */
    public void adoptOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ClientFingerprint fingerprint = getOrCreate(player);
            fingerprint.joined(true);
            beginScanCycle(player, fingerprint);
        }
    }

    /** Safety net for sessions whose player disappeared without a quit event. */
    public void dropStaleSessions() {
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, ClientFingerprint> entry : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                stale.add(entry.getKey());
            }
        }
        stale.forEach(sessions::remove);
    }
}
