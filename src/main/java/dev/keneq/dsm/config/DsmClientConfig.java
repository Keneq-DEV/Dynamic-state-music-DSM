package dev.keneq.dsm.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class DsmClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final Values VALUES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        VALUES = new Values(builder);
        SPEC = builder.build();
    }

    private DsmClientConfig() {
    }

    public static final class Values {
        public final ForgeConfigSpec.BooleanValue muteVanillaMusic;
        public final ForgeConfigSpec.DoubleValue masterMusicVolume;
        public final ForgeConfigSpec.DoubleValue crossfadeSeconds;
        public final ForgeConfigSpec.DoubleValue quickCrossfadeSeconds;
        public final ForgeConfigSpec.DoubleValue menuFadeSeconds;

        public final ForgeConfigSpec.BooleanValue useThreatLevels;
        public final ForgeConfigSpec.DoubleValue threatDetectionRadius;
        public final ForgeConfigSpec.DoubleValue closeAggroRadius;
        public final ForgeConfigSpec.DoubleValue dangerHoldSeconds;
        public final ForgeConfigSpec.IntValue groupThreatMinCount;
        public final ForgeConfigSpec.IntValue groupEscalationConfirmTicks;
        public final ForgeConfigSpec.DoubleValue groupDeescalationHoldSeconds;
        public final ForgeConfigSpec.DoubleValue finishTrackNearEndSeconds;

        public final ForgeConfigSpec.IntValue escapeMinEnemies;
        public final ForgeConfigSpec.DoubleValue escapeHealthRatio;
        public final ForgeConfigSpec.DoubleValue escapeGraceSeconds;
        public final ForgeConfigSpec.DoubleValue searchDurationSeconds;
        public final ForgeConfigSpec.DoubleValue hideDurationSeconds;
        public final ForgeConfigSpec.DoubleValue hideFadeOutSeconds;

        public final ForgeConfigSpec.DoubleValue pvpDetectionRadius;
        public final ForgeConfigSpec.DoubleValue pvpCombatMemorySeconds;
        public final ForgeConfigSpec.IntValue pvpGroupMinCount;
        public final ForgeConfigSpec.IntValue pvpEscapeMinEnemies;
        public final ForgeConfigSpec.DoubleValue pvpHideRadius;
        public final ForgeConfigSpec.BooleanValue pvpHideRequiresPriorCombat;

        public final ForgeConfigSpec.DoubleValue lowHealthRatio;
        public final ForgeConfigSpec.DoubleValue nearDeathHearts;
        public final ForgeConfigSpec.BooleanValue nearDeathOverridesBoss;
        public final ForgeConfigSpec.DoubleValue healingHoldSeconds;

        public final ForgeConfigSpec.DoubleValue bossDetectionRadius;
        public final ForgeConfigSpec.BooleanValue bossBarRequiresHostileNear;

        public final ForgeConfigSpec.DoubleValue jumpscareRadius;
        public final ForgeConfigSpec.DoubleValue jumpscareCooldownSeconds;

        Values(ForgeConfigSpec.Builder b) {
            b.push("audio");
            muteVanillaMusic = b
                    .comment("Mute vanilla Minecraft music while DSM controls audio (mapped biomes or active states). Unmapped peaceful biomes still play vanilla music; discs and cave/ambient sounds are never touched.")
                    .define("muteVanillaMusic", true);
            masterMusicVolume = b
                    .comment("Master multiplier for all DSM music tracks (0.0 - 1.0).")
                    .defineInRange("masterMusicVolume", 1.0, 0.0, 1.0);
            crossfadeSeconds = b
                    .comment("Normal smooth crossfade duration in seconds.")
                    .defineInRange("crossfadeSeconds", 1.5, 0.1, 10.0);
            quickCrossfadeSeconds = b
                    .comment("Fast crossfade duration in seconds for reactive transitions (danger, boss, jumpscare, death).")
                    .defineInRange("quickCrossfadeSeconds", 0.35, 0.05, 3.0);
            menuFadeSeconds = b
                    .comment("Fade duration in seconds when entering or leaving menu_pause / menu_inventory.")
                    .defineInRange("menuFadeSeconds", 0.5, 0.05, 5.0);
            b.pop();

            b.push("combat_pve");
            useThreatLevels = b
                    .comment("If false (default), danger uses a single combined pool of all danger_* tracks (like step_0..step_X) without splitting by level_0/level_1. If true, uses level_0/level_1 based on enemy count.")
                    .define("useThreatLevels", false);
            threatDetectionRadius = b
                    .comment("Maximum radius (blocks) to evaluate hostile mobs with real line of sight.")
                    .defineInRange("threatDetectionRadius", 28.0, 8.0, 64.0);
            closeAggroRadius = b
                    .comment("Close radius (blocks) where a visible hostile facing the player counts as active threat.")
                    .defineInRange("closeAggroRadius", 12.0, 4.0, 32.0);
            dangerHoldSeconds = b
                    .comment("Seconds to retain active danger state after briefly losing line of sight before transitioning to search (prevents restarting danger between enemies).")
                    .defineInRange("dangerHoldSeconds", 2.0, 0.0, 10.0);
            groupThreatMinCount = b
                    .comment("Minimum number of active hostiles to count as group_danger (or level_1 when useThreatLevels = true).")
                    .defineInRange("groupThreatMinCount", 4, 2, 20);
            groupEscalationConfirmTicks = b
                    .comment("Ticks of sustained group threat before escalating from danger to group_danger (20 ticks = 1s).")
                    .defineInRange("groupEscalationConfirmTicks", 25, 0, 200);
            groupDeescalationHoldSeconds = b
                    .comment("Seconds to hold group_danger after mob count drops below groupThreatMinCount (prevents flickering).")
                    .defineInRange("groupDeescalationHoldSeconds", 4.0, 0.5, 30.0);
            finishTrackNearEndSeconds = b
                    .comment("When upgrading danger -> group_danger, if the current danger track has <= this many seconds left, let it finish naturally instead of cutting.")
                    .defineInRange("finishTrackNearEndSeconds", 5.0, 0.0, 15.0);
            escapeMinEnemies = b
                    .comment("Minimum active hostiles required to trigger PvE escape.")
                    .defineInRange("escapeMinEnemies", 2, 1, 20);
            escapeHealthRatio = b
                    .comment("Health ratio (0.0 - 1.0) below or equal to which fleeing triggers escape.")
                    .defineInRange("escapeHealthRatio", 0.40, 0.05, 0.90);
            escapeGraceSeconds = b
                    .comment("Grace seconds to keep escape active during brief pauses (e.g., pillaring up, boxing in, quick eating).")
                    .defineInRange("escapeGraceSeconds", 3.5, 0.5, 15.0);
            searchDurationSeconds = b
                    .comment("Seconds in search after dangerHold expires before transitioning to hide.")
                    .defineInRange("searchDurationSeconds", 5.0, 0.5, 60.0);
            hideDurationSeconds = b
                    .comment("Maximum seconds for hide before smoothly fading out. Shorter tracks play completely and fade out.")
                    .defineInRange("hideDurationSeconds", 12.0, 1.0, 60.0);
            hideFadeOutSeconds = b
                    .comment("Smooth fade-out duration in seconds when hide ends.")
                    .defineInRange("hideFadeOutSeconds", 2.5, 0.2, 10.0);
            b.pop();

            b.push("combat_pvp");
            pvpDetectionRadius = b
                    .comment("Radius (blocks) to track enemy players in a PvP session.")
                    .defineInRange("pvpDetectionRadius", 32.0, 8.0, 64.0);
            pvpCombatMemorySeconds = b
                    .comment("Seconds after PvP hit before decaying if out of sight.")
                    .defineInRange("pvpCombatMemorySeconds", 18.0, 3.0, 60.0);
            pvpGroupMinCount = b
                    .comment("Minimum enemy players in PvP for pvp_group_danger.")
                    .defineInRange("pvpGroupMinCount", 2, 2, 10);
            pvpEscapeMinEnemies = b
                    .comment("Minimum enemy players required for pvp_escape.")
                    .defineInRange("pvpEscapeMinEnemies", 1, 1, 10);
            pvpHideRadius = b
                    .comment("Radius (blocks) for pvp_hide when crouching/concealed near non-allied players.")
                    .defineInRange("pvpHideRadius", 20.0, 4.0, 48.0);
            pvpHideRequiresPriorCombat = b
                    .comment("If true, pvp_hide only triggers after active PvP combat; if false, sneaking near unallied players also triggers it.")
                    .define("pvpHideRequiresPriorCombat", false);
            b.pop();

            b.push("player_boss_jumpscare");
            lowHealthRatio = b
                    .comment("Health ratio (0.0 - 1.0) for low_health.")
                    .defineInRange("lowHealthRatio", 0.30, 0.05, 0.80);
            nearDeathHearts = b
                    .comment("Health in hearts at or below which near_death triggers (1.5 hearts = 3.0 HP).")
                    .defineInRange("nearDeathHearts", 1.5, 0.5, 5.0);
            nearDeathOverridesBoss = b
                    .comment("If false, boss music stays prioritized as the climax even at near_death; if true, near_death interrupts boss.")
                    .define("nearDeathOverridesBoss", false);
            healingHoldSeconds = b
                    .comment("Seconds to sustain healing state after active regeneration / healing.")
                    .defineInRange("healingHoldSeconds", 4.0, 0.5, 20.0);
            bossDetectionRadius = b
                    .comment("Radius (blocks) when bossBarRequiresHostileNear is enabled.")
                    .defineInRange("bossDetectionRadius", 64.0, 16.0, 128.0);
            bossBarRequiresHostileNear = b
                    .comment("Enable if you play on servers with decorative HUD boss bars so boss state only fires when a living hostile/boss is nearby.")
                    .define("bossBarRequiresHostileNear", false);
            jumpscareRadius = b
                    .comment("Radius (blocks) for sudden close ambush jumpscare detection.")
                    .defineInRange("jumpscareRadius", 6.5, 2.0, 16.0);
            jumpscareCooldownSeconds = b
                    .comment("Cooldown in seconds between jumpscares.")
                    .defineInRange("jumpscareCooldownSeconds", 45.0, 5.0, 300.0);
            b.pop();
        }
    }
}