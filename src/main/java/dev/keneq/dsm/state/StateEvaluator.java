package dev.keneq.dsm.state;

import dev.keneq.dsm.audio.MusicEngine;
import dev.keneq.dsm.config.DsmClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

public final class StateEvaluator {
    private final ThreatTracker threatTracker;
    private final MusicEngine musicEngine;

    private MusicState lastEvaluatedState = MusicState.BIOME;
    private int lastEvaluatedLevel = 0;
    private ResourceLocation lastEvaluatedBiome = null;
    private boolean holdingJumpscare = false;

    public StateEvaluator(ThreatTracker threatTracker, MusicEngine musicEngine) {
        this.threatTracker = threatTracker;
        this.musicEngine = musicEngine;
    }

    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            threatTracker.reset();
            musicEngine.resetAll(mc);
            holdingJumpscare = false;
            lastEvaluatedState = MusicState.BIOME;
            lastEvaluatedLevel = 0;
            lastEvaluatedBiome = null;
            return;
        }

        LocalPlayer player = mc.player;
        boolean gamePaused = mc.isPaused();
        if (!gamePaused) {
            threatTracker.update(mc, musicEngine.isHideFinished());
        }

        EvaluationResult eval = evaluate(mc, player);
        if (eval.state() != MusicState.HIDE && eval.state() != MusicState.PVP_HIDE) {
            musicEngine.resetHideFinished();
        }

        lastEvaluatedState = eval.state();
        lastEvaluatedLevel = eval.level();
        lastEvaluatedBiome = eval.biomeId();

        musicEngine.updateTarget(mc, eval.state(), eval.level(), eval.biomeId());
        musicEngine.tick(mc);
    }

    private EvaluationResult evaluate(Minecraft mc, LocalPlayer player) {
        ResourceLocation biomeId = mc.level.getBiome(player.blockPosition())
                .unwrapKey()
                .map(ResourceKey::location)
                .orElse(null);

        if (player.isDeadOrDying()
                || player.getHealth() <= 0.0F
                || mc.screen instanceof DeathScreen) {
            holdingJumpscare = false;
            return new EvaluationResult(MusicState.DEATH, 0, biomeId);
        }

        Screen screen = mc.screen;
        if (screen instanceof PauseScreen || (mc.isPaused() && screen != null)) {
            return new EvaluationResult(MusicState.MENU_PAUSE, 0, biomeId);
        }

        if (holdingJumpscare) {
            if (musicEngine.isJumpscareFinished()) {
                holdingJumpscare = false;
            } else {
                return new EvaluationResult(MusicState.JUMPSCARE, 0, biomeId);
            }
        }
        if (threatTracker.isPendingJumpscare() && !threatTracker.isBossActive()) {
            threatTracker.consumeJumpscareTrigger();
            holdingJumpscare = true;
            return new EvaluationResult(MusicState.JUMPSCARE, 0, biomeId);
        }

        boolean isNearDeath = player.getHealth() <= DsmClientConfig.VALUES.nearDeathHearts.get() * 2.0;
        boolean isLowHealth = ThreatTracker.getHealthRatio(player) <= DsmClientConfig.VALUES.lowHealthRatio.get();

        if (threatTracker.isBossActive()) {
            if (isNearDeath && DsmClientConfig.VALUES.nearDeathOverridesBoss.get()) {
                return new EvaluationResult(MusicState.NEAR_DEATH, 0, biomeId);
            }
            return new EvaluationResult(MusicState.BOSS, 0, biomeId);
        }

        if (isNearDeath) {
            return new EvaluationResult(MusicState.NEAR_DEATH, 0, biomeId);
        }

        boolean useLevels = DsmClientConfig.VALUES.useThreatLevels.get();

        if (threatTracker.isPvpSessionActive() || threatTracker.isPvpHideActive()) {
            if (threatTracker.isPvpEscapeActive()) {
                return new EvaluationResult(MusicState.PVP_ESCAPE, 0, biomeId);
            }
            if (threatTracker.isPvpDangerActive()) {
                if (threatTracker.isPvpGroupActive()) {
                    return new EvaluationResult(MusicState.PVP_GROUP_DANGER, useLevels ? 1 : 0, biomeId);
                }
                return new EvaluationResult(MusicState.PVP_DANGER, 0, biomeId);
            }
            if (threatTracker.isPvpSearchActive()) {
                return new EvaluationResult(MusicState.PVP_SEARCH, 0, biomeId);
            }
            if (threatTracker.isPvpHideActive()) {
                return new EvaluationResult(MusicState.PVP_HIDE, 0, biomeId);
            }
        }

        if (threatTracker.isPveSessionActive()) {
            if (threatTracker.isPveEscapeActive()) {
                return new EvaluationResult(MusicState.ESCAPE, 0, biomeId);
            }
            if (threatTracker.isPveDangerActive()) {
                if (threatTracker.isPveGroupActive()) {
                    return new EvaluationResult(MusicState.GROUP_DANGER, useLevels ? 1 : 0, biomeId);
                }
                int level = 0;
                if (useLevels) {
                    int groupMin = DsmClientConfig.VALUES.groupThreatMinCount.get();
                    level = threatTracker.getPveActiveCount() >= groupMin ? 1 : 0;
                }
                return new EvaluationResult(MusicState.DANGER, level, biomeId);
            }
            if (threatTracker.isPveSearchActive()) {
                return new EvaluationResult(MusicState.SEARCH, 0, biomeId);
            }
            if (threatTracker.isPveHideActive()) {
                return new EvaluationResult(MusicState.HIDE, 0, biomeId);
            }
        }

        if (isLowHealth) {
            return new EvaluationResult(MusicState.LOW_HEALTH, 0, biomeId);
        }
        if (threatTracker.isHealingActive()) {
            return new EvaluationResult(MusicState.HEALING, 0, biomeId);
        }

        if (screen instanceof AbstractContainerScreen<?>) {
            return new EvaluationResult(MusicState.MENU_INVENTORY, 0, biomeId);
        }

        return new EvaluationResult(MusicState.BIOME, 0, biomeId);
    }

    public MusicState getLastEvaluatedState() {
        return lastEvaluatedState;
    }

    public int getLastEvaluatedLevel() {
        return lastEvaluatedLevel;
    }

    public ResourceLocation getLastEvaluatedBiome() {
        return lastEvaluatedBiome;
    }

    private record EvaluationResult(MusicState state, int level, ResourceLocation biomeId) {
    }
}