# ClientPolicy

Server-side client mod fingerprinting for Paper, with a transparent, tiered policy engine.

ClientPolicy identifies the client mods that **announce themselves** to your server, compares them against a policy profile you define, and applies a `LOG → WARN → KICK` ladder. Players can read the rules themselves with `/clientpolicy rules`, so nobody gets kicked by a rule they were never shown.

No client-side mod is required. Vanilla, Fabric, Forge and NeoForge clients all work.

---

## Read this first: what ClientPolicy is not

**ClientPolicy is not an anti-cheat.**

Detection works by looking at three things the client volunteers:

1. the plugin messaging channels it registers,
2. its `minecraft:brand` string,
3. the presence of a mod loader handshake.

A mod that never talks to the server is invisible to this method. **X-ray, killaura, reach, freecam, auto-clickers and similar cheats register no channels and cannot be detected here.** Anything claiming otherwise, on this method, is guessing.

What ClientPolicy *does* see reliably: Litematica, Xaero's Minimap/World Map, JourneyMap, VoxelMap, WorldEditCUI, Baritone, ReplayMod, Distant Horizons, Simple Voice Chat, Vivecraft, LabyMod, Lunar Client and anything else you teach it.

This limit is repeated in `config.yml`, in `/clientpolicy check`, and in `/clientpolicy rules`, on purpose. A policy tool that lets admins believe they have cheat detection is worse than no tool at all.

---

## Installation

1. Drop `ClientPolicy-<version>.jar` into `plugins/`.
2. Start the server. `config.yml`, `signatures.yml` and `data.db` are created in `plugins/ClientPolicy/`.
3. **Verify the signatures before you trust them** — see the next section.
4. Edit your profiles in `config.yml`, then run `/clientpolicy reload`.

Requirements:

| | |
|---|---|
| Server | Paper 1.21 – 1.21.11 (one jar covers the whole line; no NMS, no reflection) |
| Java | 21 |
| Dependencies | `org.xerial:sqlite-jdbc`, downloaded at runtime by Paper's library loader — nothing to install |

If your server cannot reach Maven Central, the SQLite driver will not load. ClientPolicy still detects and enforces normally; only `/clientpolicy history` and the cross-session violation counter are disabled, and a warning is printed at startup.

---

## Step one: verify your signatures

Channel names change between Minecraft versions and mod releases. The shipped `signatures.yml` is a **starting set, not ground truth.** Confirm every entry you care about:

1. Join with the mod installed on your own client.
2. Run `/clientpolicy inspect <yourname>`. It prints the raw channel list, the brand, which signatures matched and which channels matched nothing.
3. Copy the channels listed under *Unrecognised channels* into a `signatures.yml` entry.
4. `/clientpolicy reload`.

Do this before switching any profile to `ALLOWLIST`. In allowlist mode, an unverified signature list means kicking players for channels you simply never taught the plugin about.

`/clientpolicy check` is the same view limited to your own client, and is safe to leave available to everyone — it is what makes the policy auditable by the players it applies to.

---

## Commands

Root command `/clientpolicy`, alias `/cp`.

| Command | Description | Permission |
|---|---|---|
| `/clientpolicy rules` | The allowed/denied mod list of *your* profile, grouped by category | `clientpolicy.rules` (everyone) |
| `/clientpolicy check` | What the server currently sees on your own client | `clientpolicy.check` (everyone) |
| `/clientpolicy inspect <player>` | Raw channels, brand, matched signatures, scan state | `clientpolicy.admin` (op) |
| `/clientpolicy history <player> [limit]` | Past detections and actions (default 10, max 100) | `clientpolicy.admin` |
| `/clientpolicy profile <player>` | Which profile a player resolved to, and why | `clientpolicy.admin` |
| `/clientpolicy rescan <player>` | Re-apply the policy immediately, ladder included | `clientpolicy.admin` |
| `/clientpolicy reload` | Reload `config.yml` and `signatures.yml` | `clientpolicy.admin` |

`history` also works for offline players, as long as their name is in the server's local player cache — the lookup never blocks the main thread on a Mojang API call.

### Permissions

| Node | Default | Meaning |
|---|---|---|
| `clientpolicy.rules` | everyone | Use `/clientpolicy rules` |
| `clientpolicy.check` | everyone | Use `/clientpolicy check` |
| `clientpolicy.admin` | op | inspect, history, profile, rescan, reload |
| `clientpolicy.bypass` | nobody | Exempt from every action. **Detections are still recorded and staff are still notified.** |
| `clientpolicy.notify` | op | Receive a chat notification on every violation |
| `clientpolicy.profile.<name>` | nobody | Bind a player to the profile `<name>` |

Give `clientpolicy.profile.<name>` only through a permissions plugin. Note that a player holding two profile permissions gets whichever the plugin finds first — assign exactly one per player or per group.

---

## Configuration

### `config.yml`

#### `detection`

| Key | Default | Meaning |
|---|---|---|
| `scan-delay-ticks` | `60` | Ticks between join and the first evaluation. Clients register channels a few ticks after joining; 60 ticks = 3s is a safe margin. |
| `rescan-window-seconds` | `120` | After the first scan, every newly registered channel triggers another evaluation for this long. This is what stops a mod from dodging enforcement by registering late. A final pass closes the window. |
| `track-brand` | `true` | Read and evaluate `minecraft:brand`. |
| `brand-unknown-action` | `LOG` | Applied at the **end** of the rescan window if the brand is still unreadable. A brand that is merely slow to arrive never triggers this. `ALLOW`, `LOG`, `WARN` or `KICK`. |

Channels are never forgotten once registered. A client that registers and immediately unregisters has still revealed the mod.

#### `policy`

| Key | Default | Meaning |
|---|---|---|
| `default-profile` | `default` | Profile used when a player holds no `clientpolicy.profile.<name>` permission. |
| `unknown-channel-action` | `LOG` | Action for a channel matching no signature at all. **Keep this soft.** It is the safety valve that stops `ALLOWLIST` profiles from kicking people over harmless channels. |
| `ignore-channels` | see file | Never evaluated. Supports `*` and `?` wildcards. Server and proxy plumbing belongs here. |

#### `enforcement`

All messages are [MiniMessage](https://docs.advntr.dev/minimessage/format.html). Placeholders available in `kick-message`, `warn-message` and `notify-format`:

`<player>`, `<mod>`, `<mod_id>`, `<channels>`, `<category>`, `<profile>`, `<reason>`, `<action>`, `<count>`

`<count>` is the player's current hit count for that specific mod, and `<reason>` is one of `DENIED`, `NOT_ALLOWED`, `UNKNOWN_CHANNEL`, `UNKNOWN_BRAND`.

Set `notify-staff: false` to silence the `clientpolicy.notify` broadcast without removing the permission.

Only `WARN` and `KICK` are broadcast. `LOG`-level detections go to the console and to `/clientpolicy history` only — since `unknown-channel-action` defaults to `LOG` and one modded client easily brings a dozen uncatalogued channels, broadcasting those would make the notify permission unusable.

#### `profiles`

```yaml
profiles:
  default:
    mode: BLOCKLIST          # or ALLOWLIST
    allow: [litematica, sodium, iris, worldeditcui, replaymod]
    deny:  [xaeros-minimap, journeymap, voxelmap, baritone]
    ladder:
      first: WARN
      after: KICK
      escalate-after: 2      # hits 1-2 WARN, hit 3 onwards KICK
      counter-reset-hours: 24
```

- `BLOCKLIST` — only ids in `deny` are violations. Safe default.
- `ALLOWLIST` — anything not in `allow` is a violation. Only use this after verifying your signatures.
- The ids are the keys of `signatures.yml`.
- `escalate-after: N` means hits `1..N` use `first` and hit `N+1` onwards uses `after`. `escalate-after: 0` applies `after` immediately.
- `counter-reset-hours` is idle time: a player who behaves for that long starts again at `first`. `0` means the counter never resets.

The counter is **per player, per mod, and survives restarts** — it lives in SQLite, not in memory. Within one session a mod is acted on once, so the ladder counts logins, not rescans.

A profile named `builder` is activated by the `clientpolicy.profile.builder` permission. Add your own the same way; the permission node is always `clientpolicy.profile.<key>`.

> **Register new profile permissions with a default of `false`.** Bukkit answers `hasPermission()` with `true` for *any* node it has never heard of when the caller is an operator. So if you add a `staff` profile to `config.yml` but never declare `clientpolicy.profile.staff` anywhere, every op silently resolves to it. Declare the node in your permissions plugin (or add it to `plugin.yml` with `default: false`) at the same time you add the profile, and confirm with `/clientpolicy profile <player>`.

#### `storage` and `messages`

`storage.retention-days` (default `30`) deletes detections older than that on startup; `0` disables cleanup. `messages` holds the command feedback strings, including `prefix` and the `disclaimer` line appended to `rules` and `check`.

### `signatures.yml`

```yaml
signatures:
  litematica:
    display: "Litematica"          # optional, defaults to the id
    category: SCHEMATIC            # MINIMAP | SCHEMATIC | AUTOMATION | PERFORMANCE | UTILITY | OTHER
    channels: ["litematica:*"]     # '*' and '?' wildcards
    brand: "(?i)^lunarclient.*"    # optional regex against minecraft:brand
```

An entry with neither `channels` nor `brand` can never match. That is legal and useful: `sodium` and `iris` ship that way so profiles can reference them, and `/clientpolicy rules` labels them **"not detectable"** rather than implying they are enforced.

---

## Upgrading

New `config.yml` keys added by a plugin update are merged into your existing file automatically, with a log line naming them. Your `profiles` section is never touched — a profile you deleted stays deleted.

`signatures.yml` is **not** merged on update, because it is a curated file. When a release adds signatures, the changelog names them so you can copy the ones you want.

`/clientpolicy reload` re-reads both files and re-evaluates everyone online, so a newly added signature applies at once. It deliberately does not re-punish violations already acted on in the current session — reloading three times cannot walk a player up the ladder. Use `/clientpolicy rescan <player>` when you do want a clean re-run.

---

## Performance and threading

- All SQLite access runs on async tasks. The main thread only ever touches the in-memory fingerprint map.
- Channel-to-signature lookups are memoised and wildcards are compiled once at load, so a 100-player join wave does not re-walk every pattern.
- Late channel registrations are coalesced with a one-second debounce: a mod registering twenty channels at once produces one evaluation, not twenty.
- Sessions are dropped on quit, with a five-minute sweep as a safety net for connections that end without a quit event.

---

## Troubleshooting

**A mod is not detected.** It probably registers no channels, or the channel name changed. Run `/clientpolicy inspect <player>` and look at *Unrecognised channels*. If that list is empty, the mod is silent and this method cannot see it.

**Vanilla players are being flagged.** Check `policy.unknown-channel-action` and your `ignore-channels` list. A plugin on your own server can register channels that appear on the client side too — add those to `ignore-channels`.

**`/clientpolicy history` says the database is unavailable.** The SQLite driver could not be downloaded. Check the startup log; enforcement still works without it, but the violation counter resets on restart.

**Nothing happens at all.** Confirm the player's profile with `/clientpolicy profile <player>` — they may be resolving to a profile whose `deny` list is empty, or holding `clientpolicy.bypass`.

---

## License and credits

Author: **XenbleDev**
