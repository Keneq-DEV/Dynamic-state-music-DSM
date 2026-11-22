## Dynamic State Music (DSM)

**Dynamic State Music (DSM)** is a smart, client-side dynamic music engine for Minecraft Forge 1.20.1 that reacts in real time to what is happening to you in-game — using your own `.ogg` music tracks via Resource Packs or custom configs.

> **Note:** DSM is **100% client-side**. You can join vanilla or modded servers without installing it on the server.
> **Note on audio:** DSM does **not** include music by default — you add your own `.ogg` tracks (e.g. `danger_0.ogg`, `danger_1.ogg`, `hide_0.ogg`, biome themes, etc.) and DSM handles everything automatically.

---

## Key Features

- **Per-Biome Music + Vanilla Fallback**
  - Assign custom track lists to any biome (`[biome.<namespace>.<name>]`), including biomes from other mods.
  - If you explore a peaceful biome that is **not** mapped in your config, DSM steps aside and lets normal Minecraft music play.

- **Smart Combat & Threat Detection (PvE)**
  - **No strip-mining false positives:** Uses real line-of-sight checks so mobs trapped behind stone walls or deep in closed caves won't falsely trigger combat music.
  - **No infinite search loops:** Loss-of-sight timers (`search` → `hide` → back to biome) count down strictly from real contact, never getting stuck just because a mob is still alive behind a wall.
  - **No track restarts or mid-song cuts:** Adding more enemies mid-fight won't restart your `danger` track. Tracks play through cleanly and rotate across multiple files (`danger_0`, `danger_1`, ..., `danger_X`), remembering the last track played so the next fight sounds fresh.
  - **Intelligent `escape`:** Recognizes when you are genuinely running for your life (multiple enemies + low health + sprinting, pillaring up, or boxing yourself in with blocks).
  - **Smart `hide` cap:** Short tracks play completely and fade out; long tracks smoothly fade out after your configured limit.

- **Full PvP Support**
  - Dedicated states when fighting other players: `pvp_danger`, `pvp_group_danger`, `pvp_escape`, `pvp_search`, and `pvp_hide` (respects teams/allies).

- **Player Conditions, Bosses & Jumpscares**
  - `low_health`, `near_death`, `healing`, and `death`.
  - `boss` state triggered by active Boss Bars.
  - `jumpscare` stinger for sudden close ambushes, with a configurable cooldown.

- **Pause & Inventory Ambience with Exact Resume**
  - Plays custom ambience in `menu_pause` and `menu_inventory`, and **resumes your previous gameplay track right where it left off** when returning to the world.

- **Smooth Audio Engine & Vanilla Compatibility**
  - Instant, responsive entry on danger/boss/jumpscare/death with smooth crossfades and zero overlapping duplicate tracks.
  - Silences vanilla background music while DSM is active (toggleable in config) — **never touches Music Discs or Cave/Ambient sounds**.
  - Supports `sequence` or `shuffle` (no repeats until exhausted) and custom `gap_seconds` between tracks.
  - Auto-discovers numbered `.ogg` tracks (`_0` to `_X` with no limit) and never crashes if a state has no tracks configured.

---

## Configuration & Commands

- **`config/dsm/tracks.toml`** — Map biomes and states to sound IDs or wildcards (e.g. `"dsm:danger_*"`, `"dsm:hide_*"`), playback mode (`sequence` / `shuffle`), and `gap_seconds`.
- **`config/dsm-client.toml`** — Customize detection radii, group thresholds, escape/search/hide timers, health thresholds, crossfade speeds, optional `useThreatLevels`, and vanilla music muting.
- **Entity Tag (`data/dsm/tags/entity_types/hostiles.json`)** — Add custom/modded entities to `dsm:hostiles` via datapack.
- **Client Commands:**
  - `/dsm state` — Inspect the active state, biome, playing track, fade level, threat status, PvP, boss, and jumpscare cooldown.
  - `/dsm reload` — Hot-reload `tracks.toml` and rescan tracks without restarting the game.