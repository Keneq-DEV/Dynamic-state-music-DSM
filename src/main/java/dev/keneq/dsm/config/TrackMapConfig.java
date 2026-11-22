package dev.keneq.dsm.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.keneq.dsm.DsmMod;
import dev.keneq.dsm.state.MusicState;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class TrackMapConfig {
    private static final String DEFAULT_RESOURCE = "/dsm/default_tracks.toml";

    private final Map<ResourceLocation, TrackListConfig> biomeTracks = new HashMap<>();
    private final Map<MusicState, StateTrackSet> stateTracks = new EnumMap<>(MusicState.class);

    private int biomeListCount;
    private int stateListCount;
    private boolean warnedCalmIgnored;

    public static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve(DsmMod.MOD_ID).resolve("tracks.toml");
    }

    public synchronized ReloadResult loadOrCreate() {
        Path path = getConfigPath();
        try {
            ensureDefaultFileExists(path);
        } catch (IOException e) {
            return ReloadResult.failure("Failed to create default tracks.toml: " + e.getMessage());
        }

        Map<ResourceLocation, TrackListConfig> nextBiomes = new HashMap<>();
        Map<MusicState, StateTrackSet> nextStates = new EnumMap<>(MusicState.class);
        int nextBiomeCount = 0;
        int nextStateCount = 0;

        try (CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance()).sync().build()) {
            config.load();

            Object biomeObj = config.get("biome");
            if (biomeObj instanceof Config biomeRoot) {
                for (Config.Entry nsEntry : biomeRoot.entrySet()) {
                    String namespace = nsEntry.getKey();
                    if (nsEntry.getValue() instanceof Config pathRoot) {
                        for (Config.Entry pathEntry : pathRoot.entrySet()) {
                            String biomePath = pathEntry.getKey();
                            if (pathEntry.getValue() instanceof Config section) {
                                ResourceLocation biomeId = ResourceLocation.tryParse(namespace + ":" + biomePath);
                                if (biomeId == null) {
                                    DsmMod.LOGGER.warn("[DSM] Invalid biome id in tracks.toml: {}:{}", namespace, biomePath);
                                    continue;
                                }
                                TrackListConfig list = parseTrackList("biome:" + biomeId, section);
                                if (!list.isEmpty()) {
                                    nextBiomes.put(biomeId, list);
                                    nextBiomeCount++;
                                }
                            }
                        }
                    }
                }
            }

            Object statesObj = config.get("states");
            if (statesObj instanceof Config statesRoot) {
                if (statesRoot.contains("calm") && !warnedCalmIgnored) {
                    warnedCalmIgnored = true;
                    DsmMod.LOGGER.info("[DSM] [states.calm] found in tracks.toml and ignored; unmapped peaceful biomes fall back to vanilla Minecraft music.");
                }

                for (MusicState state : MusicState.values()) {
                    if (state == MusicState.BIOME) {
                        continue;
                    }
                    Object stateObj = statesRoot.get(state.getId());
                    if (!(stateObj instanceof Config stateConfig)) {
                        continue;
                    }

                    TrackListConfig direct = null;
                    TreeMap<Integer, TrackListConfig> levels = new TreeMap<>();

                    if (stateConfig.contains("tracks")) {
                        direct = parseTrackList("state:" + state.getId(), stateConfig);
                        if (!direct.isEmpty()) {
                            nextStateCount++;
                        } else {
                            direct = null;
                        }
                    }

                    for (Config.Entry entry : stateConfig.entrySet()) {
                        String key = entry.getKey();
                        if (key.startsWith("level_") && entry.getValue() instanceof Config levelCfg) {
                            try {
                                int level = Integer.parseInt(key.substring("level_".length()));
                                TrackListConfig lvlList = parseTrackList("state:" + state.getId() + ":level_" + level, levelCfg);
                                if (!lvlList.isEmpty()) {
                                    levels.put(level, lvlList);
                                    nextStateCount++;
                                }
                            } catch (NumberFormatException ignored) {
                                DsmMod.LOGGER.warn("[DSM] Invalid level key '{}' in [states.{}]", key, state.getId());
                            }
                        }
                    }

                    TrackListConfig combined = buildCombined(state, direct, levels);
                    if (direct != null || !levels.isEmpty()) {
                        nextStates.put(state, new StateTrackSet(direct, levels, combined));
                    }
                }
            }
        } catch (Exception e) {
            DsmMod.LOGGER.error("[DSM] Failed to load tracks.toml", e);
            return ReloadResult.failure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }

        this.biomeTracks.clear();
        this.biomeTracks.putAll(nextBiomes);
        this.stateTracks.clear();
        this.stateTracks.putAll(nextStates);
        this.biomeListCount = nextBiomeCount;
        this.stateListCount = nextStateCount;

        DsmMod.LOGGER.info("[DSM] Loaded track map: {} biome list(s), {} state list(s)", nextBiomeCount, nextStateCount);
        return ReloadResult.success(nextBiomeCount, nextStateCount);
    }

    private static TrackListConfig buildCombined(MusicState state, TrackListConfig direct, TreeMap<Integer, TrackListConfig> levels) {
        if (direct != null && levels.isEmpty()) {
            return direct;
        }
        Set<String> merged = new LinkedHashSet<>();
        TrackListConfig.PlayMode mode = TrackListConfig.PlayMode.SHUFFLE;
        double gap = 0.0;
        if (direct != null) {
            merged.addAll(direct.trackPatterns());
            mode = direct.mode();
            gap = direct.gapSeconds();
        }
        for (TrackListConfig lvl : levels.values()) {
            merged.addAll(lvl.trackPatterns());
            if (direct == null) {
                mode = lvl.mode();
                gap = lvl.gapSeconds();
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        return new TrackListConfig("state:" + state.getId(), List.copyOf(merged), mode, gap);
    }

    private static void ensureDefaultFileExists(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        try (InputStream in = TrackMapConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing embedded resource " + DEFAULT_RESOURCE);
            }
            Files.copy(in, path);
        }
    }

    private static TrackListConfig parseTrackList(String key, Config section) {
        List<String> patterns = new ArrayList<>();
        Object rawTracks = section.get("tracks");
        if (rawTracks instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        patterns.add(trimmed);
                    }
                }
            }
        }
        TrackListConfig.PlayMode mode = TrackListConfig.PlayMode.fromString(section.get("mode"));
        double gap = 0.0;
        Object rawGap = section.get("gap_seconds");
        if (rawGap instanceof Number num) {
            gap = Math.max(0.0, num.doubleValue());
        }
        return new TrackListConfig(key, List.copyOf(patterns), mode, gap);
    }

    public synchronized Optional<TrackListConfig> getBiomeTracks(ResourceLocation biomeId) {
        if (biomeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(biomeTracks.get(biomeId));
    }

    public synchronized Optional<TrackListConfig> getStateTracks(MusicState state, int level, boolean useLevels) {
        Optional<TrackListConfig> direct = lookupStateTracks(state, level, useLevels);
        if (direct.isPresent()) {
            return direct;
        }
        if (state == MusicState.GROUP_DANGER) {
            return lookupStateTracks(MusicState.DANGER, Math.max(1, level), useLevels);
        }
        if (state == MusicState.PVP_GROUP_DANGER) {
            return lookupStateTracks(MusicState.PVP_DANGER, Math.max(1, level), useLevels);
        }
        return Optional.empty();
    }

    private Optional<TrackListConfig> lookupStateTracks(MusicState state, int level, boolean useLevels) {
        StateTrackSet set = stateTracks.get(state);
        if (set == null) {
            return Optional.empty();
        }
        if (!useLevels) {
            if (set.direct() != null) {
                return Optional.of(set.direct());
            }
            return Optional.ofNullable(set.combined());
        }
        if (!set.levels().isEmpty()) {
            Map.Entry<Integer, TrackListConfig> floor = set.levels().floorEntry(level);
            if (floor != null) {
                return Optional.of(floor.getValue());
            }
            Map.Entry<Integer, TrackListConfig> first = set.levels().firstEntry();
            if (first != null) {
                return Optional.of(first.getValue());
            }
        }
        return Optional.ofNullable(set.direct());
    }

    public synchronized int getBiomeListCount() {
        return biomeListCount;
    }

    public synchronized int getStateListCount() {
        return stateListCount;
    }

    private record StateTrackSet(
            TrackListConfig direct,
            TreeMap<Integer, TrackListConfig> levels,
            TrackListConfig combined
    ) {
    }

    public record ReloadResult(boolean ok, int biomeCount, int stateCount, String error) {
        public static ReloadResult success(int biomes, int states) {
            return new ReloadResult(true, biomes, states, "");
        }

        public static ReloadResult failure(String error) {
            return new ReloadResult(false, 0, 0, error);
        }
    }
}