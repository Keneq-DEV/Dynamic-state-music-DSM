package dev.keneq.dsm.audio;

import com.mojang.blaze3d.audio.Channel;
import dev.keneq.dsm.DsmMod;
import dev.keneq.dsm.config.DsmClientConfig;
import dev.keneq.dsm.config.TrackListConfig;
import dev.keneq.dsm.config.TrackMapConfig;
import dev.keneq.dsm.state.MusicState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class MusicEngine {
    private static final int MIN_ALIVE_TICKS_BEFORE_END = 10;

    private final TrackMapConfig trackMap;
    private final TrackPlaylistPlayer playlistPlayer = new TrackPlaylistPlayer();
    private final Map<ResourceLocation, Double> durationCache = new ConcurrentHashMap<>();
    private final Set<ResourceLocation> durationPending = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedMissingStates = new HashSet<>();

    private boolean soundsAutoDiscovered = false;

    private MusicState desiredState = MusicState.BIOME;
    private int desiredLevel = 0;
    private ResourceLocation desiredBiome = null;
    private boolean controllingAudio = false;

    private ActiveEntry current = null;
    private ActiveEntry fadingOut = null;
    private SuspendedEntry suspended = null;

    private int gapTicksRemaining = 0;
    private boolean jumpscareFinished = false;
    private boolean hideFinished = false;
    private boolean lastPausedFlag = false;

    private Field soundEngineField;
    private Field instanceToChannelField;
    private Field soundRegistryField;
    private boolean reflectionChecked = false;

    public MusicEngine(TrackMapConfig trackMap) {
        this.trackMap = trackMap;
    }

    public void onReload(Minecraft mc) {
        warnedMissingStates.clear();
        playlistPlayer.resetCaches();
        durationCache.clear();
        soundsAutoDiscovered = false;
        ensureAutoDiscoveredSounds(mc);
    }

    public void resetAll(Minecraft mc) {
        stopImmediate(current, mc);
        stopImmediate(fadingOut, mc);
        if (suspended != null) {
            stopImmediate(suspended.entry, mc);
            suspended = null;
        }
        current = null;
        fadingOut = null;
        gapTicksRemaining = 0;
        jumpscareFinished = false;
        hideFinished = false;
        controllingAudio = false;
        desiredState = MusicState.BIOME;
        desiredLevel = 0;
        desiredBiome = null;
    }

    public void updateTarget(Minecraft mc, MusicState state, int level, ResourceLocation biomeId) {
        if (mc.level == null || mc.player == null) {
            if (current != null || fadingOut != null || suspended != null || controllingAudio) {
                resetAll(mc);
            }
            return;
        }

        ensureAutoDiscoveredSounds(mc);

        Optional<TrackListConfig> targetListOpt = resolveTrackList(state, level, biomeId);
        if (targetListOpt.isEmpty()) {
            if (state == MusicState.BIOME) {
                if (current != null) {
                    beginFadeOut(current, DsmClientConfig.VALUES.crossfadeSeconds.get());
                    current = null;
                }
                if (suspended != null) {
                    stopImmediate(suspended.entry, mc);
                    suspended = null;
                }
                controllingAudio = false;
                desiredState = state;
                desiredLevel = level;
                desiredBiome = biomeId;
                gapTicksRemaining = 0;
                return;
            }

            String warnKey = state.getId() + ":" + level;
            if (warnedMissingStates.add(warnKey)) {
                DsmMod.LOGGER.warn("[DSM] No tracks configured for state '{}' (level {}). Remaining silent.", state.getId(), level);
            }
            controllingAudio = DsmClientConfig.VALUES.muteVanillaMusic.get();
            if (current != null) {
                beginFadeOut(current, DsmClientConfig.VALUES.quickCrossfadeSeconds.get());
                current = null;
            }
            desiredState = state;
            desiredLevel = level;
            desiredBiome = biomeId;
            return;
        }

        TrackListConfig targetList = targetListOpt.get();
        controllingAudio = DsmClientConfig.VALUES.muteVanillaMusic.get();
        if (controllingAudio) {
            mc.getMusicManager().stopPlaying();
        }

        if (current != null && current.state == MusicState.DANGER && state == MusicState.DANGER
                && isCurrentActiveOnEngine(mc, current)) {
            desiredState = state;
            desiredLevel = level;
            desiredBiome = biomeId;
            return;
        }

        if (current != null && current.state == MusicState.DANGER && state == MusicState.GROUP_DANGER
                && isCurrentActiveOnEngine(mc, current)) {
            double remaining = getRemainingSeconds(current);
            double threshold = DsmClientConfig.VALUES.finishTrackNearEndSeconds.get();
            if (remaining > 0.0 && remaining <= threshold) {
                desiredState = state;
                desiredLevel = level;
                desiredBiome = biomeId;
                return;
            }
        }

        boolean samePlaylist = current != null && current.playlistKey.equals(targetList.key());
        boolean sameStateAndBiome = desiredState == state && (state != MusicState.BIOME || (desiredBiome != null && desiredBiome.equals(biomeId)));

        desiredState = state;
        desiredLevel = level;
        desiredBiome = biomeId;

        if (samePlaylist && current != null && isCurrentActiveOnEngine(mc, current)) {
            return;
        }

        if (current == null && fadingOut != null && fadingOut.playlistKey.equals(targetList.key())
                && isCurrentActiveOnEngine(mc, fadingOut)) {
            current = fadingOut;
            fadingOut = null;
            current.instance.setPausedLocally(false);
            setChannelPaused(mc, current.instance, false);
            double fadeIn = state.isInstantAttack() ? 0.25 : DsmClientConfig.VALUES.crossfadeSeconds.get();
            current.instance.setTargetFade(1.0f, fadeIn, getMasterVolume());
            gapTicksRemaining = 0;
            return;
        }

        if (!state.isMenu() && suspended != null && suspended.entry.playlistKey.equals(targetList.key())
                && isCurrentActiveOnEngine(mc, suspended.entry)) {
            if (current != null) {
                beginFadeOut(current, DsmClientConfig.VALUES.menuFadeSeconds.get());
            }
            current = suspended.entry;
            suspended = null;
            current.instance.setPausedLocally(false);
            setChannelPaused(mc, current.instance, false);
            current.instance.setTargetFade(1.0f, DsmClientConfig.VALUES.menuFadeSeconds.get(), getMasterVolume());
            gapTicksRemaining = 0;
            return;
        }

        if (state.isMenu()) {
            if (current != null && !current.state.isMenu() && isCurrentActiveOnEngine(mc, current)) {
                if (suspended != null) {
                    stopImmediate(suspended.entry, mc);
                }
                current.instance.setPausedLocally(true);
                setChannelPaused(mc, current.instance, true);
                suspended = new SuspendedEntry(current);
                current = null;
            } else if (current != null) {
                beginFadeOut(current, DsmClientConfig.VALUES.menuFadeSeconds.get());
                current = null;
            }
            startNewTrack(mc, state, level, targetList, DsmClientConfig.VALUES.menuFadeSeconds.get());
            return;
        }

        if (suspended != null && !suspended.entry.playlistKey.equals(targetList.key())) {
            stopImmediate(suspended.entry, mc);
            suspended = null;
        }

        if (!sameStateAndBiome || current == null || !isCurrentActiveOnEngine(mc, current)) {
            if (state == MusicState.JUMPSCARE) {
                jumpscareFinished = false;
            }
            if (state == MusicState.HIDE || state == MusicState.PVP_HIDE) {
                hideFinished = false;
            }
            double fadeSecs = state.isInstantAttack()
                    ? DsmClientConfig.VALUES.quickCrossfadeSeconds.get()
                    : DsmClientConfig.VALUES.crossfadeSeconds.get();
            if (current != null) {
                beginFadeOut(current, fadeSecs);
                current = null;
            }
            startNewTrack(mc, state, level, targetList, fadeSecs);
        }
    }

    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return;
        }

        boolean isPaused = mc.isPaused();
        float masterVol = getMasterVolume();

        if (lastPausedFlag && !isPaused && suspended != null) {
            setChannelPaused(mc, suspended.entry.instance, true);
        }
        lastPausedFlag = isPaused;

        if (fadingOut != null) {
            fadingOut.instance.setMasterVolume(masterVol);
            fadingOut.instance.manualTick();
            pushChannelVolumeAndStream(mc, fadingOut.instance, isPaused);
            if (fadingOut.instance.isStopped()
                    || (fadingOut.instance.getActiveTicks() >= MIN_ALIVE_TICKS_BEFORE_END && !mc.getSoundManager().isActive(fadingOut.instance))) {
                stopImmediate(fadingOut, mc);
                fadingOut = null;
            }
        }

        if (current != null) {
            current.instance.setMasterVolume(masterVol);
            current.instance.manualTick();
            pushChannelVolumeAndStream(mc, current.instance, isPaused);

            if ((current.state == MusicState.HIDE || current.state == MusicState.PVP_HIDE)
                    && !current.instance.isFadingOutToStop()) {
                double elapsed = current.instance.getActiveTicks() / 20.0;
                double limit = DsmClientConfig.VALUES.hideDurationSeconds.get();
                double fadeOut = DsmClientConfig.VALUES.hideFadeOutSeconds.get();
                if (elapsed >= Math.max(1.0, limit - fadeOut)) {
                    current.instance.markStopOnFadeOut(fadeOut, masterVol);
                }
            }

            boolean activeOnSoundEngine = mc.getSoundManager().isActive(current.instance);
            boolean finished = current.instance.isStopped()
                    || (current.instance.getActiveTicks() >= MIN_ALIVE_TICKS_BEFORE_END && !activeOnSoundEngine);

            if (finished) {
                MusicState finishedState = current.state;
                current = null;
                if (finishedState == MusicState.JUMPSCARE) {
                    jumpscareFinished = true;
                } else if (finishedState == MusicState.HIDE || finishedState == MusicState.PVP_HIDE) {
                    hideFinished = true;
                } else if (finishedState == desiredState) {
                    Optional<TrackListConfig> listOpt = resolveTrackList(desiredState, desiredLevel, desiredBiome);
                    if (listOpt.isPresent()) {
                        gapTicksRemaining = (int) Math.round(listOpt.get().gapSeconds() * 20.0);
                        if (gapTicksRemaining <= 0) {
                            startNewTrack(mc, desiredState, desiredLevel, listOpt.get(), 0.05);
                        }
                    }
                }
            }
        } else if (gapTicksRemaining > 0) {
            gapTicksRemaining--;
            if (gapTicksRemaining == 0) {
                Optional<TrackListConfig> listOpt = resolveTrackList(desiredState, desiredLevel, desiredBiome);
                listOpt.ifPresent(list -> startNewTrack(mc, desiredState, desiredLevel, list, 0.25));
            }
        }
    }

    public boolean shouldMuteVanillaMusic() {
        return controllingAudio && DsmClientConfig.VALUES.muteVanillaMusic.get();
    }

    public boolean isJumpscareFinished() {
        return jumpscareFinished;
    }

    public boolean isHideFinished() {
        return hideFinished;
    }

    public void resetHideFinished() {
        hideFinished = false;
    }

    public String getCurrentTrackLabel() {
        if (current != null) {
            return current.trackId.toString();
        }
        if (gapTicksRemaining > 0) {
            return "gap(" + String.format("%.1fs", gapTicksRemaining / 20.0) + ")";
        }
        return "none";
    }

    public String getFadeStatusLabel() {
        if (current == null) {
            return "0%";
        }
        return Math.round(current.instance.getCurrentFade() * 100.0f) + "%";
    }

    private Optional<TrackListConfig> resolveTrackList(MusicState state, int level, ResourceLocation biomeId) {
        if (state == MusicState.BIOME) {
            return trackMap.getBiomeTracks(biomeId);
        }
        return trackMap.getStateTracks(state, level, DsmClientConfig.VALUES.useThreatLevels.get());
    }

    private void startNewTrack(Minecraft mc, MusicState state, int level, TrackListConfig list, double fadeInSeconds) {
        SoundManager sm = mc.getSoundManager();
        List<ResourceLocation> valid = playlistPlayer.resolveValidTracks(list, mc);
        Optional<ResourceLocation> chosenOpt = playlistPlayer.nextTrack(list, mc, valid);
        if (chosenOpt.isEmpty()) {
            String warnKey = state.getId() + ":empty:" + list.key();
            if (warnedMissingStates.add(warnKey)) {
                DsmMod.LOGGER.warn("[DSM] No playable .ogg tracks resolved for '{}'. Remaining silent.", list.key());
            }
            return;
        }
        ResourceLocation trackId = chosenOpt.get();
        SoundEvent event = SoundEvent.createVariableRangeEvent(trackId);

        boolean singleTrack = valid.size() == 1;
        boolean shouldLoop = singleTrack
                && list.gapSeconds() <= 0.0
                && state != MusicState.JUMPSCARE
                && state != MusicState.HIDE
                && state != MusicState.PVP_HIDE
                && state != MusicState.DEATH;

        float initialFade = state.isInstantAttack() ? 0.65f : 0.25f;
        double attackDuration = state.isInstantAttack() ? Math.min(0.25, fadeInSeconds) : Math.min(1.0, fadeInSeconds);

        DsmSoundInstance instance = new DsmSoundInstance(event, shouldLoop, initialFade, getMasterVolume());
        instance.setTargetFade(1.0f, attackDuration, getMasterVolume());

        sm.play(instance);
        current = new ActiveEntry(state, level, list.key(), trackId, instance);
        gapTicksRemaining = 0;

        requestDurationAsync(mc, sm, trackId);
    }

    private void beginFadeOut(ActiveEntry entry, double seconds) {
        if (entry == null) {
            return;
        }
        if (fadingOut != null && fadingOut != entry) {
            stopImmediate(fadingOut, Minecraft.getInstance());
        }
        fadingOut = entry;
        fadingOut.instance.setPausedLocally(false);
        setChannelPaused(Minecraft.getInstance(), fadingOut.instance, false);
        fadingOut.instance.markStopOnFadeOut(seconds, getMasterVolume());
    }

    private void stopImmediate(ActiveEntry entry, Minecraft mc) {
        if (entry == null) {
            return;
        }
        entry.instance.forceStop();
        if (mc != null && mc.getSoundManager() != null) {
            mc.getSoundManager().stop(entry.instance);
        }
    }

    private boolean isCurrentActiveOnEngine(Minecraft mc, ActiveEntry entry) {
        if (entry == null || entry.instance.isStopped()) {
            return false;
        }
        if (entry.instance.getActiveTicks() < MIN_ALIVE_TICKS_BEFORE_END) {
            return true;
        }
        return mc.getSoundManager().isActive(entry.instance);
    }

    private float getMasterVolume() {
        return DsmClientConfig.VALUES.masterMusicVolume.get().floatValue();
    }

    private double getRemainingSeconds(ActiveEntry entry) {
        Double total = durationCache.get(entry.trackId);
        if (total == null || total <= 0.0) {
            return -1.0;
        }
        double elapsed = entry.instance.getActiveTicks() / 20.0;
        return Math.max(0.0, total - (elapsed % total));
    }

    private void requestDurationAsync(Minecraft mc, SoundManager sm, ResourceLocation soundId) {
        if (durationCache.containsKey(soundId) || !durationPending.add(soundId)) {
            return;
        }
        WeighedSoundEvents weighed = sm.getSoundEvent(soundId);
        if (weighed == null) {
            durationPending.remove(soundId);
            return;
        }
        Sound sound = weighed.getSound(RandomSource.create());
        ResourceLocation oggLocation = sound.getPath();
        CompletableFuture.runAsync(() -> {
            try {
                Optional<Resource> resOpt = mc.getResourceManager().getResource(oggLocation);
                if (resOpt.isPresent()) {
                    try (InputStream in = resOpt.get().open()) {
                        byte[] data = in.readAllBytes();
                        double secs = parseOggVorbisDuration(data);
                        if (secs > 0.0) {
                            durationCache.put(soundId, secs);
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                durationPending.remove(soundId);
            }
        });
    }

    private static double parseOggVorbisDuration(byte[] data) {
        if (data == null || data.length < 64) {
            return -1.0;
        }
        int sampleRate = -1;
        int limit = Math.min(data.length - 16, 4096);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0x01 && data[i + 1] == 'v' && data[i + 2] == 'o' && data[i + 3] == 'r'
                    && data[i + 4] == 'b' && data[i + 5] == 'i' && data[i + 6] == 's') {
                if (i + 16 <= data.length) {
                    sampleRate = ByteBuffer.wrap(data, i + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                }
                break;
            }
        }
        if (sampleRate <= 0) {
            return -1.0;
        }
        for (int i = data.length - 14; i >= 0; i--) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
                long granulePos = ByteBuffer.wrap(data, i + 6, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                if (granulePos > 0) {
                    return (double) granulePos / (double) sampleRate;
                }
            }
        }
        return -1.0;
    }

    @SuppressWarnings("unchecked")
    private void ensureAutoDiscoveredSounds(Minecraft mc) {
        if (soundsAutoDiscovered || mc == null || mc.getSoundManager() == null || mc.getResourceManager() == null) {
            return;
        }
        SoundManager sm = mc.getSoundManager();
        Map<ResourceLocation, WeighedSoundEvents> registry = findSoundRegistry(sm);
        if (registry == null) {
            soundsAutoDiscovered = true;
            return;
        }

        Map<ResourceLocation, Resource> oggs = mc.getResourceManager().listResources("sounds/music",
                loc -> loc.getNamespace().equals(DsmMod.MOD_ID) && loc.getPath().endsWith(".ogg"));

        int added = 0;
        for (ResourceLocation loc : oggs.keySet()) {
            String path = loc.getPath();
            if (!path.startsWith("sounds/") || !path.endsWith(".ogg")) {
                continue;
            }
            String soundPath = path.substring("sounds/".length(), path.length() - ".ogg".length());
            int lastSlash = soundPath.lastIndexOf('/');
            String fileName = lastSlash >= 0 ? soundPath.substring(lastSlash + 1) : soundPath;
            if (fileName.isEmpty()) {
                continue;
            }
            ResourceLocation soundId = ResourceLocation.tryParse(DsmMod.MOD_ID + ":" + fileName);
            if (soundId == null) {
                continue;
            }
            if (!registry.containsKey(soundId)) {
                WeighedSoundEvents weighed = new WeighedSoundEvents(soundId, null);
                Sound sound = new Sound(
                        DsmMod.MOD_ID + ":" + soundPath,
                        ConstantFloat.of(1.0F),
                        ConstantFloat.of(1.0F),
                        1,
                        Sound.Type.FILE,
                        true,
                        false,
                        16
                );
                weighed.addSound(sound);
                registry.put(soundId, weighed);
                added++;
            }
        }
        soundsAutoDiscovered = true;
        if (added > 0) {
            DsmMod.LOGGER.info("[DSM] Auto-discovered and registered {} extra .ogg sound(s) from assets/{}/sounds/music/.", added, DsmMod.MOD_ID);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<ResourceLocation, WeighedSoundEvents> findSoundRegistry(SoundManager sm) {
        if (soundRegistryField != null) {
            try {
                return (Map<ResourceLocation, WeighedSoundEvents>) soundRegistryField.get(sm);
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            for (Field f : SoundManager.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object obj = f.get(sm);
                    if (obj instanceof Map<?, ?> map) {
                        soundRegistryField = f;
                        return (Map<ResourceLocation, WeighedSoundEvents>) map;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void ensureReflection(SoundManager sm) {
        if (reflectionChecked) {
            return;
        }
        reflectionChecked = true;
        try {
            for (Field f : SoundManager.class.getDeclaredFields()) {
                if (f.getType() == SoundEngine.class) {
                    f.setAccessible(true);
                    soundEngineField = f;
                    break;
                }
            }
            if (soundEngineField != null) {
                for (Field f : SoundEngine.class.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(soundEngineField.get(sm));
                        if (val instanceof Map<?, ?> map) {
                            instanceToChannelField = f;
                            if (!map.isEmpty()) {
                                Object sample = map.values().iterator().next();
                                if (sample instanceof ChannelAccess.ChannelHandle) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            soundEngineField = null;
            instanceToChannelField = null;
        }
    }

    private ChannelAccess.ChannelHandle findChannelHandle(Minecraft mc, SoundInstance instance) {
        if (mc == null || mc.getSoundManager() == null || instance == null) {
            return null;
        }
        SoundManager sm = mc.getSoundManager();
        ensureReflection(sm);
        if (soundEngineField == null || instanceToChannelField == null) {
            return null;
        }
        try {
            Object engine = soundEngineField.get(sm);
            if (engine == null) {
                return null;
            }
            for (Field f : SoundEngine.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object obj = f.get(engine);
                    if (obj instanceof Map<?, ?> map) {
                        Object handle = map.get(instance);
                        if (handle instanceof ChannelAccess.ChannelHandle ch) {
                            instanceToChannelField = f;
                            return ch;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void setChannelPaused(Minecraft mc, SoundInstance instance, boolean pause) {
        ChannelAccess.ChannelHandle handle = findChannelHandle(mc, instance);
        if (handle != null) {
            handle.execute(pause ? Channel::pause : Channel::unpause);
        }
    }

    private void pushChannelVolumeAndStream(Minecraft mc, DsmSoundInstance instance, boolean gamePaused) {
        ChannelAccess.ChannelHandle handle = findChannelHandle(mc, instance);
        if (handle != null) {
            float vol = instance.getVolume();
            handle.execute(channel -> {
                channel.setVolume(vol);
                if (gamePaused) {
                    channel.updateStream();
                }
            });
        }
    }

    private record ActiveEntry(
            MusicState state,
            int level,
            String playlistKey,
            ResourceLocation trackId,
            DsmSoundInstance instance
    ) {
    }

    private record SuspendedEntry(ActiveEntry entry) {
    }
}