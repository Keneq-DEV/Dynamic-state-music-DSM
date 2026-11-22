package dev.keneq.dsm.audio;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

public final class DsmSoundInstance extends AbstractTickableSoundInstance {
    private float currentFade = 1.0f;
    private float targetFade = 1.0f;
    private float fadeStepPerTick = 0.05f;
    private float masterVolume = 1.0f;
    private boolean stopWhenFadedOut = false;
    private boolean pausedLocally = false;
    private int activeTicks = 0;

    public DsmSoundInstance(SoundEvent soundEvent, boolean looping, float initialFade, float masterVolume) {
        super(soundEvent, SoundSource.MUSIC, RandomSource.create());
        this.looping = looping;
        this.delay = 0;
        this.attenuation = Attenuation.NONE;
        this.relative = true;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
        this.pitch = 1.0f;
        this.currentFade = Mth.clamp(initialFade, 0.0001f, 1.0f);
        this.targetFade = 1.0f;
        this.masterVolume = Mth.clamp(masterVolume, 0.0f, 1.0f);
        updateEffectiveVolume();
    }

    public void setTargetFade(float target, double durationSeconds, float masterVolume) {
        this.masterVolume = Mth.clamp(masterVolume, 0.0f, 1.0f);
        this.targetFade = Mth.clamp(target, 0.0f, 1.0f);
        if (this.targetFade > 0.0f) {
            this.stopWhenFadedOut = false;
        }
        if (durationSeconds <= 0.02) {
            this.currentFade = this.targetFade;
            this.fadeStepPerTick = 1.0f;
        } else {
            float totalTicks = (float) Math.max(1.0, durationSeconds * 20.0);
            float diff = Math.abs(this.targetFade - this.currentFade);
            this.fadeStepPerTick = Math.max(0.002f, diff / totalTicks);
        }
        updateEffectiveVolume();
    }

    public void markStopOnFadeOut(double durationSeconds, float masterVolume) {
        this.stopWhenFadedOut = true;
        this.pausedLocally = false;
        this.targetFade = 0.0f;
        this.masterVolume = Mth.clamp(masterVolume, 0.0f, 1.0f);
        if (durationSeconds <= 0.02) {
            this.currentFade = 0.0f;
            this.fadeStepPerTick = 1.0f;
        } else {
            float totalTicks = (float) Math.max(1.0, durationSeconds * 20.0);
            this.fadeStepPerTick = Math.max(0.002f, this.currentFade / totalTicks);
        }
        updateEffectiveVolume();
    }

    public void setMasterVolume(float masterVolume) {
        this.masterVolume = Mth.clamp(masterVolume, 0.0f, 1.0f);
        updateEffectiveVolume();
    }

    public void setPausedLocally(boolean pausedLocally) {
        this.pausedLocally = pausedLocally;
    }

    public boolean isFadingOutToStop() {
        return stopWhenFadedOut;
    }

    public float getCurrentFade() {
        return currentFade;
    }

    public int getActiveTicks() {
        return activeTicks;
    }

    public void forceStop() {
        this.stop();
    }

    public void manualTick() {
        if (isStopped()) {
            return;
        }
        if (!pausedLocally) {
            activeTicks++;
        }
        if (currentFade < targetFade) {
            currentFade = Math.min(targetFade, currentFade + fadeStepPerTick);
        } else if (currentFade > targetFade) {
            currentFade = Math.max(targetFade, currentFade - fadeStepPerTick);
        }
        updateEffectiveVolume();

        if (stopWhenFadedOut && currentFade <= 0.001f) {
            this.stop();
        }
    }

    private void updateEffectiveVolume() {
        float vol = currentFade * masterVolume;
        this.volume = stopWhenFadedOut && currentFade <= 0.001f ? 0.0f : Math.max(0.0001f, vol);
    }

    @Override
    public void tick() {
        updateEffectiveVolume();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return true;
    }
}