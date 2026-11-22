package dev.keneq.dsm.client;

import dev.keneq.dsm.audio.DsmSoundInstance;
import dev.keneq.dsm.audio.MusicEngine;
import dev.keneq.dsm.command.DsmCommand;
import dev.keneq.dsm.config.TrackMapConfig;
import dev.keneq.dsm.state.StateEvaluator;
import dev.keneq.dsm.state.ThreatTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

public final class DsmClientEvents {
    private static TrackMapConfig trackMap;
    private static MusicEngine musicEngine;
    private static ThreatTracker threatTracker;
    private static StateEvaluator stateEvaluator;
    private static boolean initialized = false;

    private DsmClientEvents() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        trackMap = new TrackMapConfig();
        trackMap.loadOrCreate();
        musicEngine = new MusicEngine(trackMap);
        threatTracker = new ThreatTracker();
        stateEvaluator = new StateEvaluator(threatTracker, musicEngine);

        MinecraftForge.EVENT_BUS.addListener(DsmClientEvents::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(DsmClientEvents::onPlaySound);
        MinecraftForge.EVENT_BUS.addListener(DsmClientEvents::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(DsmClientEvents::onLoggingOut);
        MinecraftForge.EVENT_BUS.addListener(DsmClientEvents::onRightClickBlock);
        MinecraftForge.EVENT_BUS.addListener(DsmClientEvents::onAttackEntity);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        stateEvaluator.tick(Minecraft.getInstance());
    }

    private static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null) {
            return;
        }
        if (sound.getSource() != SoundSource.MUSIC || sound instanceof DsmSoundInstance) {
            return;
        }
        if (musicEngine != null && musicEngine.shouldMuteVanillaMusic()) {
            event.setSound(null);
        }
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        DsmCommand.register(event.getDispatcher(), trackMap, musicEngine, threatTracker, stateEvaluator);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (threatTracker != null) {
            threatTracker.reset();
        }
        if (musicEngine != null) {
            musicEngine.resetAll(Minecraft.getInstance());
        }
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof LocalPlayer localPlayer && threatTracker != null) {
            threatTracker.onPlayerPlacedBlock(localPlayer);
        }
    }

    private static void onAttackEntity(AttackEntityEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof LocalPlayer && event.getTarget() instanceof Player target && threatTracker != null) {
            threatTracker.onPlayerAttackedEntity(target);
        }
    }
}