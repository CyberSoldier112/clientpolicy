# Changelog

All notable changes to ClientPolicy are documented here.
This project follows [Semantic Versioning](https://semver.org/).

## [1.0.0] — 2026-08-07

First public release. Paper 1.21 – 1.21.11, Java 21.

### Added

**Fingerprint engine**
- Collects plugin messaging channels, `minecraft:brand` and the mod loader handshake (Fabric / Forge / NeoForge / vanilla).
- Channel registrations are buffered from the login event onwards, so anything a client registers before `PlayerJoinEvent` is kept instead of lost.
- First scan runs `scan-delay-ticks` after join; for the next `rescan-window-seconds` every newly registered channel triggers a debounced re-evaluation, so a mod cannot dodge enforcement by registering late. A final pass closes the window.
- The brand is re-read on every scan, because it is frequently still null at join time. Only the final pass may act on a brand that never arrived.
- Unregistered channels are never forgotten — registering and immediately unregistering still reveals the mod.

**Signature database**
- `signatures.yml` maps a mod id to channel patterns (`*` and `?` wildcards), an optional brand regex, and a category.
- Patterns are compiled once at load and channel lookups are memoised.
- Entries with no channels and no brand are legal and are reported as **"not detectable"** rather than silently implying enforcement.
- Starting set: Litematica, Syncmatica, WorldEdit CUI, Xaero's Minimap/World Map, JourneyMap, VoxelMap, Baritone, ReplayMod, Carpet, Vivecraft, Simple Voice Chat, LabyMod, Lunar Client, Distant Horizons, plus documentation-only entries for Sodium and Iris.

**Policy and enforcement**
- `ALLOWLIST` / `BLOCKLIST` profiles, selected per player via `clientpolicy.profile.<name>`, falling back to `policy.default-profile`.
- `LOG → WARN → KICK` ladder per profile, with `escalate-after` and `counter-reset-hours`.
- Violation counters are per player, per mod, and stored in SQLite, so they survive a restart. Within one session a mod is acted on once, which makes the ladder count logins rather than rescans.
- `clientpolicy.bypass` exempts a player from every action while still recording the detection and notifying staff.
- Staff notifications cover `WARN` and `KICK` only; `LOG` detections go to the console and `/clientpolicy history`, so the default `unknown-channel-action: LOG` cannot spam staff on every modded join.
- Separate `unknown-channel-action` for channels matching no signature, so `ALLOWLIST` profiles do not kick over channels the admin never catalogued.
- Separate `brand-unknown-action` for an unreadable client brand.

**Commands** (`/clientpolicy`, alias `/cp`), all with tab completion
- `rules` — your own profile's allowed/denied list, grouped by category.
- `check` — what the server currently sees on your own client.
- `inspect <player>` — raw channels, brand, matched signatures, unrecognised channels, scan state.
- `history <player> [limit]` — past detections; works for offline players via the local player cache, never blocking on a Mojang lookup.
- `profile <player>` — the resolved profile and the reason it was chosen.
- `rescan <player>` — deliberate clean re-run of the policy.
- `reload` — re-reads both YAML files and re-evaluates everyone online.

**Storage**
- SQLite at `plugins/ClientPolicy/data.db`, `detections` and `violation_counters` tables, WAL mode.
- The driver is fetched at runtime through the `libraries:` block of `plugin.yml`, so nothing is shaded into the jar.
- All access is async. Missing driver degrades gracefully: detection and enforcement keep working, history and cross-restart counters do not.
- `storage.retention-days` purges old rows on startup.

**Configuration**
- Every message is MiniMessage, with per-message placeholder documentation in `config.yml`.
- Config keys introduced by an update are merged into an existing `config.yml` automatically, and the added keys are logged.

### Notes

- **ClientPolicy is not an anti-cheat.** Mods that never talk to the server — x-ray, killaura, reach, freecam — register no channels and cannot be detected by this method. The limitation is stated in the README, in `config.yml`, and in the output of `/clientpolicy rules` and `/clientpolicy check`.
- The shipped `signatures.yml` is a starting set. Channel names change between Minecraft and mod versions; verify with `/clientpolicy inspect` before switching a profile to `ALLOWLIST`.
- Config migration deliberately skips the `profiles` section, so a profile you deleted is not resurrected by an update.
- `/clientpolicy reload` does not re-punish violations already acted on in the current session, so reloading repeatedly cannot escalate a player up the ladder.
