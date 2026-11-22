package dev.keneq.dsm.audio;

import dev.keneq.dsm.DsmMod;
import dev.keneq.dsm.config.TrackListConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrackPlaylistPlayer {
    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.*?)(\\d+)$");

    private final Map<String, Cursor> cursors = new HashMap<>();
    private final Set<String> warnedMissing = new HashSet<>();
    private final Map<ResourceLocation, Boolean> oggExistsCache = new HashMap<>();
    private final Random random = new Random();

    public List<ResourceLocation> resolveValidTracks(TrackListConfig list, Minecraft mc) {
        if (list == null || list.isEmpty() || mc == null) {
            return List.of();
        }
        SoundManager sm = mc.getSoundManager();
        Set<ResourceLocation> collected = new LinkedHashSet<>();

        for (String rawPattern : list.trackPatterns()) {
            if (rawPattern.endsWith("*")) {
                String prefixRaw = rawPattern.substring(0, rawPattern.length() - 1);
                ResourceLocation prefixId = ResourceLocation.tryParse(prefixRaw);
                if (prefixId == null) {
                    if (warnedMissing.add(rawPattern)) {
                        DsmMod.LOGGER.warn("[DSM] Invalid wildcard sound pattern '{}' in '{}'", rawPattern, list.key());
                    }
                    continue;
                }
                List<ResourceLocation> matches = new ArrayList<>();
                for (ResourceLocation available : sm.getAvailableSounds()) {
                    if (available.getNamespace().equals(prefixId.getNamespace())
                            && available.getPath().startsWith(prefixId.getPath())
                            && hasPlayableOgg(mc, sm, available)) {
                        matches.add(available);
                    }
                }
                matches.sort(naturalOrder());
                collected.addAll(matches);
            } else {
                ResourceLocation id = ResourceLocation.tryParse(rawPattern);
                if (id == null) {
                    if (warnedMissing.add(rawPattern)) {
                        DsmMod.LOGGER.warn("[DSM] Invalid track id '{}' in '{}'", rawPattern, list.key());
                    }
                    continue;
                }
                if (hasPlayableOgg(mc, sm, id)) {
                    collected.add(id);
                } else if (warnedMissing.add(rawPattern)) {
                    DsmMod.LOGGER.warn("[DSM] Sound '{}' not found or missing .ogg on disk for '{}'.", id, list.key());
                }
            }
        }

        return List.copyOf(collected);
    }

    public Optional<ResourceLocation> nextTrack(TrackListConfig list, Minecraft mc, List<ResourceLocation> valid) {
        if (list == null || valid == null || valid.isEmpty()) {
            return Optional.empty();
        }
        if (valid.size() == 1) {
            ResourceLocation single = valid.get(0);
            cursors.computeIfAbsent(list.key(), k -> new Cursor()).lastPlayed = single;
            return Optional.of(single);
        }

        Cursor cursor = cursors.computeIfAbsent(list.key(), k -> new Cursor());
        ResourceLocation chosen;

        if (list.mode() == TrackListConfig.PlayMode.SEQUENCE) {
            int startIndex = 0;
            if (cursor.lastPlayed != null) {
                int lastIdx = valid.indexOf(cursor.lastPlayed);
                if (lastIdx >= 0) {
                    startIndex = (lastIdx + 1) % valid.size();
                } else {
                    startIndex = (cursor.sequenceIndex + 1) % valid.size();
                }
            }
            cursor.sequenceIndex = startIndex;
            chosen = valid.get(startIndex);
        } else {
            cursor.shuffleQueue.removeIf(id -> !valid.contains(id));
            if (cursor.shuffleQueue.isEmpty()) {
                cursor.shuffleQueue.addAll(valid);
                Collections.shuffle(cursor.shuffleQueue, random);
                if (cursor.lastPlayed != null && cursor.shuffleQueue.size() > 1
                        && cursor.shuffleQueue.get(0).equals(cursor.lastPlayed)) {
                    Collections.swap(cursor.shuffleQueue, 0, cursor.shuffleQueue.size() - 1);
                }
            }
            chosen = cursor.shuffleQueue.remove(0);
        }

        cursor.lastPlayed = chosen;
        return Optional.of(chosen);
    }

    public void resetCaches() {
        warnedMissing.clear();
        oggExistsCache.clear();
    }

    private boolean hasPlayableOgg(Minecraft mc, SoundManager sm, ResourceLocation soundId) {
        Boolean cached = oggExistsCache.get(soundId);
        if (cached != null) {
            return cached;
        }
        WeighedSoundEvents weighed = sm.getSoundEvent(soundId);
        if (weighed == null) {
            oggExistsCache.put(soundId, false);
            return false;
        }
        Sound sample = weighed.getSound(RandomSource.create());
        if (sample == null || sample == SoundManager.EMPTY_SOUND) {
            oggExistsCache.put(soundId, false);
            return false;
        }
        boolean exists = mc.getResourceManager().getResource(sample.getPath()).isPresent();
        oggExistsCache.put(soundId, exists);
        return exists;
    }

    private static Comparator<ResourceLocation> naturalOrder() {
        return (a, b) -> {
            String sa = a.toString();
            String sb = b.toString();
            Matcher ma = TRAILING_NUMBER.matcher(sa);
            Matcher mb = TRAILING_NUMBER.matcher(sb);
            if (ma.matches() && mb.matches() && ma.group(1).equals(mb.group(1))) {
                try {
                    return Integer.compare(Integer.parseInt(ma.group(2)), Integer.parseInt(mb.group(2)));
                } catch (NumberFormatException ignored) {
                }
            }
            return sa.compareTo(sb);
        };
    }

    private static final class Cursor {
        int sequenceIndex = -1;
        final List<ResourceLocation> shuffleQueue = new ArrayList<>();
        ResourceLocation lastPlayed;
    }
}