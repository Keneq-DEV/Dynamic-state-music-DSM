package dev.keneq.dsm.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.keneq.dsm.audio.MusicEngine;
import dev.keneq.dsm.config.TrackMapConfig;
import dev.keneq.dsm.state.StateEvaluator;
import dev.keneq.dsm.state.ThreatTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class DsmCommand {
    private DsmCommand() {
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            TrackMapConfig trackMap,
            MusicEngine musicEngine,
            ThreatTracker threatTracker,
            StateEvaluator stateEvaluator
    ) {
        dispatcher.register(Commands.literal("dsm")
                .then(Commands.literal("state").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null || mc.player == null) {
                        ctx.getSource().sendSuccess(() -> Component.translatable("dsm.command.not_tracking"), false);
                        return 1;
                    }
                    ResourceLocation biome = stateEvaluator.getLastEvaluatedBiome();
                    String biomeStr = biome != null ? biome.toString() : "unknown";
                    ctx.getSource().sendSuccess(() -> Component.translatable(
                            "dsm.command.state",
                            stateEvaluator.getLastEvaluatedState().getId(),
                            stateEvaluator.getLastEvaluatedLevel(),
                            biomeStr,
                            musicEngine.getCurrentTrackLabel(),
                            musicEngine.getFadeStatusLabel()
                    ), false);
                    ctx.getSource().sendSuccess(() -> Component.translatable(
                            "dsm.command.state.threat",
                            threatTracker.getThreatSummary(),
                            threatTracker.getPvpSummary(),
                            String.valueOf(threatTracker.isBossActive()),
                            String.valueOf(threatTracker.getRemainingJumpscareCooldownSeconds())
                    ), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    TrackMapConfig.ReloadResult res = trackMap.loadOrCreate();
                    if (res.ok()) {
                        musicEngine.onReload(Minecraft.getInstance());
                        ctx.getSource().sendSuccess(() -> Component.translatable(
                                "dsm.command.reload.ok",
                                res.biomeCount(),
                                res.stateCount()
                        ), false);
                        return 1;
                    } else {
                        ctx.getSource().sendFailure(Component.translatable("dsm.command.reload.fail", res.error()));
                        return 0;
                    }
                }))
        );
    }
}