package com.craftpilot.clientpolicy;

import com.craftpilot.clientpolicy.command.ClientPolicyCommand;
import com.craftpilot.clientpolicy.config.PluginConfig;
import com.craftpilot.clientpolicy.enforcement.EnforcementService;
import com.craftpilot.clientpolicy.fingerprint.FingerprintService;
import com.craftpilot.clientpolicy.policy.PolicyEngine;
import com.craftpilot.clientpolicy.signature.SignatureRegistry;
import com.craftpilot.clientpolicy.store.DetectionStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Entry point.
 *
 * <p>Wiring, in order: config + signatures are parsed from disk, the policy
 * engine and enforcement service are built on top of them, the fingerprint
 * listener starts collecting, and the SQLite store opens on an async task so a
 * slow disk never delays server startup.</p>
 *
 * <p>{@link #config()} and {@link #signatures()} return immutable snapshots that
 * are swapped wholesale by {@code /clientpolicy reload}; callers must therefore
 * read them once per operation instead of caching a reference.</p>
 */
public final class ClientPolicyPlugin extends JavaPlugin {

    private static final String CONFIG_FILE = "config.yml";
    private static final String SIGNATURES_FILE = "signatures.yml";
    private static final long CLEANUP_INTERVAL_TICKS = 20L * 300L;

    private volatile PluginConfig config;
    private volatile SignatureRegistry signatures;

    private DetectionStore store;
    private PolicyEngine policy;
    private EnforcementService enforcement;
    private FingerprintService fingerprints;
    private BukkitTask cleanupTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing(SIGNATURES_FILE);
        migrateConfig();

        this.config = readConfig();
        this.signatures = readSignatures();

        this.policy = new PolicyEngine(this);
        this.store = new DetectionStore(this, new File(getDataFolder(), "data.db"));
        this.enforcement = new EnforcementService(this);
        this.fingerprints = new FingerprintService(this);

        getServer().getPluginManager().registerEvents(fingerprints, this);

        PluginCommand command = getCommand("clientpolicy");
        if (command == null) {
            getLogger().severe("The 'clientpolicy' command is missing from plugin.yml - commands are unavailable.");
        } else {
            ClientPolicyCommand handler = new ClientPolicyCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        // Opening SQLite and purging old rows are both blocking; keep them off the main thread.
        runAsync(() -> {
            store.initialize();
            purgeExpiredRecords();
        });

        // Adopts players that are already online (plugin manager reload) and drops
        // fingerprints whose player vanished without a quit event.
        fingerprints.adoptOnlinePlayers();
        this.cleanupTask = getServer().getScheduler().runTaskTimer(
                this, fingerprints::dropStaleSessions, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);

        getLogger().info("Enabled with " + signatures.size() + " signatures and "
                + config.profiles().size() + " profiles.");
        getLogger().info("Reminder: ClientPolicy only detects mods that announce themselves to the server. "
                + "It is not an anti-cheat.");
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        getServer().getScheduler().cancelTasks(this);
        if (fingerprints != null) {
            fingerprints.clear();
        }
        if (store != null) {
            // Async tasks are already cancelled at this point, so this last close has
            // to happen inline. It only waits for a write that is already in flight.
            store.close();
        }
    }

    // ------------------------------------------------------------------ services

    public PluginConfig config() {
        return config;
    }

    public SignatureRegistry signatures() {
        return signatures;
    }

    public DetectionStore store() {
        return store;
    }

    public PolicyEngine policy() {
        return policy;
    }

    public EnforcementService enforcement() {
        return enforcement;
    }

    public FingerprintService fingerprints() {
        return fingerprints;
    }

    // ------------------------------------------------------------------ scheduling

    /** Runs off the main thread; silently skipped while the plugin is disabled. */
    public void runAsync(Runnable runnable) {
        if (!isEnabled()) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, runnable);
    }

    /** Hops back onto the main thread, where the Bukkit API is safe to touch. */
    public void runSync(Runnable runnable) {
        if (!isEnabled()) {
            return;
        }
        getServer().getScheduler().runTask(this, runnable);
    }

    public void runLater(Runnable runnable, long delayTicks) {
        if (!isEnabled()) {
            return;
        }
        getServer().getScheduler().runTaskLater(this, runnable, Math.max(1L, delayTicks));
    }

    // ------------------------------------------------------------------ reload

    /**
     * Re-reads both YAML files off the main thread and swaps the snapshots back on it.
     *
     * <p>Online players are re-evaluated afterwards so a freshly added signature or a
     * tightened profile takes effect immediately. Violations that were already acted on
     * in the current session stay marked, so reloading twice cannot double-escalate a
     * player up the ladder - use {@code /clientpolicy rescan} for a deliberate re-run.</p>
     *
     * @param callback invoked on the main thread once the new snapshots are live
     */
    public void reloadEverything(Runnable callback) {
        runAsync(() -> {
            PluginConfig newConfig = readConfig();
            SignatureRegistry newSignatures = readSignatures();
            runSync(() -> {
                this.config = newConfig;
                this.signatures = newSignatures;
                fingerprints.reevaluateOnlinePlayers(false);
                if (callback != null) {
                    callback.run();
                }
            });
        });
    }

    // ------------------------------------------------------------------ files

    private PluginConfig readConfig() {
        File file = new File(getDataFolder(), CONFIG_FILE);
        return PluginConfig.from(YamlConfiguration.loadConfiguration(file), getLogger());
    }

    private SignatureRegistry readSignatures() {
        File file = new File(getDataFolder(), SIGNATURES_FILE);
        if (!file.isFile()) {
            getLogger().warning(SIGNATURES_FILE + " is missing - nothing can be detected.");
            return SignatureRegistry.empty();
        }
        return SignatureRegistry.load(YamlConfiguration.loadConfiguration(file), getLogger());
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).isFile()) {
            saveResource(name, false);
        }
    }

    /**
     * Copies keys that a plugin update introduced into an existing config.yml.
     *
     * <p>Everything under {@code profiles} is skipped on purpose: those entries are
     * owned by the administrator, and re-adding them would resurrect a profile that
     * was deliberately deleted.</p>
     */
    private void migrateConfig() {
        File file = new File(getDataFolder(), CONFIG_FILE);
        if (!file.isFile()) {
            return;
        }
        InputStream resource = getResource(CONFIG_FILE);
        if (resource == null) {
            return;
        }

        YamlConfiguration defaults;
        try (Reader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not read the bundled config.yml for migration.", ex);
            return;
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || key.equals("profiles") || key.startsWith("profiles.")) {
                continue;
            }
            if (!current.contains(key)) {
                current.set(key, defaults.get(key));
                added.add(key);
            }
        }
        if (!current.isConfigurationSection("profiles")) {
            getLogger().warning("config.yml has no 'profiles' section; add one or every player falls back "
                    + "to an empty blocklist that never triggers.");
        }
        if (added.isEmpty()) {
            return;
        }
        try {
            current.save(file);
            getLogger().info("config.yml updated with " + added.size() + " new key(s): " + String.join(", ", added));
        } catch (IOException ex) {
            getLogger().log(Level.WARNING, "Could not write the migrated config.yml.", ex);
        }
    }

    private void purgeExpiredRecords() {
        int days = config.retentionDays();
        if (days <= 0 || !store.available()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        int removed = store.purgeOlderThan(cutoff);
        if (removed > 0) {
            getLogger().info("Purged " + removed + " detection(s) older than " + days + " day(s).");
        }
    }
}
