package dev.keneq.dsm.state;

import dev.keneq.dsm.DsmTags;
import dev.keneq.dsm.config.DsmClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ThreatTracker {
    private long tickCounter = 0;

    private boolean pveSessionActive = false;
    private long pveDangerHoldUntilTick = -10000;
    private int pveActiveCount = 0;
    private int pveSustainedGroupTicks = 0;
    private long pveGroupHoldUntilTick = -1;
    private long pveEscapeGraceUntilTick = -1;
    private long pveHideStartedTick = -1;
    private final Set<Integer> seenThreatIds = new HashSet<>();

    private boolean pvpSessionActive = false;
    private long pvpDangerHoldUntilTick = -10000;
    private int pvpActiveCount = 0;
    private long pvpEscapeGraceUntilTick = -1;
    private long pvpHideStartedTick = -1;
    private final Map<UUID, Long> recentPvpAttackers = new HashMap<>();

    private Vec3 lastPlayerPos = Vec3.ZERO;
    private long lastDefensiveBlockTick = -10000;

    private float lastKnownHealth = -1.0f;
    private long healingUntilTick = -10000;

    private long jumpscareCooldownUntilTick = -10000;
    private boolean pendingJumpscare = false;

    private boolean bossActive = false;
    private Field bossEventsField;
    private boolean bossReflectionChecked = false;

    public void reset() {
        tickCounter = 0;
        resetPveSession();
        resetPvpSession();
        recentPvpAttackers.clear();
        lastPlayerPos = Vec3.ZERO;
        lastDefensiveBlockTick = -10000;
        lastKnownHealth = -1.0f;
        healingUntilTick = -10000;
        jumpscareCooldownUntilTick = -10000;
        pendingJumpscare = false;
        bossActive = false;
    }

    public void resetPveSession() {
        pveSessionActive = false;
        pveDangerHoldUntilTick = -10000;
        pveActiveCount = 0;
        pveSustainedGroupTicks = 0;
        pveGroupHoldUntilTick = -1;
        pveEscapeGraceUntilTick = -1;
        pveHideStartedTick = -1;
    }

    public void resetPvpSession() {
        pvpSessionActive = false;
        pvpDangerHoldUntilTick = -10000;
        pvpActiveCount = 0;
        pvpEscapeGraceUntilTick = -1;
        pvpHideStartedTick = -1;
    }

    public void onPlayerPlacedBlock(LocalPlayer player) {
        if (player == null) {
            return;
        }
        if (pveSessionActive || pvpSessionActive) {
            lastDefensiveBlockTick = tickCounter;
        }
    }

    public void onPlayerAttackedEntity(Player target) {
        if (target != null) {
            recentPvpAttackers.put(target.getUUID(), tickCounter);
            pvpSessionActive = true;
            long holdTicks = Math.round(DsmClientConfig.VALUES.dangerHoldSeconds.get() * 20.0);
            pvpDangerHoldUntilTick = tickCounter + holdTicks;
            pvpHideStartedTick = -1;
        }
    }

    public void consumeJumpscareTrigger() {
        pendingJumpscare = false;
        long cdTicks = Math.round(DsmClientConfig.VALUES.jumpscareCooldownSeconds.get() * 20.0);
        jumpscareCooldownUntilTick = tickCounter + cdTicks;
    }

    public void update(Minecraft mc, boolean hideTrackFinished) {
        if (mc.level == null || mc.player == null) {
            reset();
            return;
        }
        tickCounter++;
        Level level = mc.level;
        LocalPlayer player = mc.player;

        updateHealthAndHealing(player);
        updateBossStatus(mc, level, player);
        updatePvpAttackers(level, player);

        Vec3 currentPos = player.position();
        Vec3 playerVel = currentPos.subtract(lastPlayerPos);
        lastPlayerPos = currentPos;

        evaluatePve(level, player, playerVel, hideTrackFinished);
        evaluatePvp(level, player, playerVel, hideTrackFinished);
    }

    private void updateHealthAndHealing(LocalPlayer player) {
        float health = player.getHealth();
        if (lastKnownHealth > 0.0f && health > lastKnownHealth + 0.05f && health < player.getMaxHealth()) {
            long hold = Math.round(DsmClientConfig.VALUES.healingHoldSeconds.get() * 20.0);
            healingUntilTick = tickCounter + hold;
        }
        if (player.hasEffect(MobEffects.REGENERATION) && health < player.getMaxHealth()) {
            long hold = Math.round(DsmClientConfig.VALUES.healingHoldSeconds.get() * 20.0);
            healingUntilTick = Math.max(healingUntilTick, tickCounter + hold / 2);
        }
        if (player.isUsingItem() && player.getUseItem().isEdible()
                && health <= player.getMaxHealth() * 0.75f) {
            healingUntilTick = Math.max(healingUntilTick, tickCounter + 20);
        }
        lastKnownHealth = health;
    }

    private void updatePvpAttackers(Level level, LocalPlayer player) {
        long memoryTicks = Math.round(DsmClientConfig.VALUES.pvpCombatMemorySeconds.get() * 20.0);
        recentPvpAttackers.entrySet().removeIf(e -> tickCounter - e.getValue() > memoryTicks);

        long holdTicks = Math.round(DsmClientConfig.VALUES.dangerHoldSeconds.get() * 20.0);
        LivingEntity hurtBy = player.getLastHurtByMob();
        if (hurtBy instanceof Player other && other != player && !other.isSpectator()) {
            recentPvpAttackers.put(other.getUUID(), tickCounter);
            pvpSessionActive = true;
            pvpDangerHoldUntilTick = tickCounter + holdTicks;
            pvpHideStartedTick = -1;
        }

        if (player.hurtTime >= 8) {
            AABB near = player.getBoundingBox().inflate(5.5);
            List<Player> nearbyPlayers = level.getEntitiesOfClass(Player.class, near,
                    p -> p != player && !p.isSpectator() && !player.isAlliedTo(p));
            for (Player other : nearbyPlayers) {
                if (other.swingTime > 0 || other.isSprinting()) {
                    recentPvpAttackers.put(other.getUUID(), tickCounter);
                    pvpSessionActive = true;
                    pvpDangerHoldUntilTick = tickCounter + holdTicks;
                    pvpHideStartedTick = -1;
                }
            }
        }
    }

    private void evaluatePve(Level level, LocalPlayer player, Vec3 playerVel, boolean hideTrackFinished) {
        double radius = DsmClientConfig.VALUES.threatDetectionRadius.get();
        double closeRadius = DsmClientConfig.VALUES.closeAggroRadius.get();
        double jumpscareRadius = DsmClientConfig.VALUES.jumpscareRadius.get();

        AABB box = player.getBoundingBox().inflate(radius);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && !(e instanceof Player) && isPotentialHostile(e));

        int visibleThreatCount = 0;
        Vec3 threatCentroid = Vec3.ZERO;
        Set<Integer> currentlyVisibleIds = new HashSet<>();

        for (LivingEntity entity : candidates) {
            double dist = entity.distanceTo(player);
            if (dist > radius) {
                continue;
            }
            if (!hasRealLineOfSight(level, entity, player)) {
                continue;
            }
            if (!isActivelyThreateningPlayer(entity, player, dist, closeRadius)) {
                continue;
            }

            visibleThreatCount++;
            currentlyVisibleIds.add(entity.getId());
            threatCentroid = threatCentroid.add(entity.position());

            if (!pveSessionActive
                    && dist <= jumpscareRadius
                    && tickCounter >= jumpscareCooldownUntilTick
                    && !seenThreatIds.contains(entity.getId())) {
                boolean behindPlayer = isBehindOrSurprise(player, entity);
                if (behindPlayer || player.hurtTime >= 8 || (entity instanceof Creeper c && c.getSwellDir() > 0)) {
                    pendingJumpscare = true;
                }
            }
        }

        seenThreatIds.clear();
        seenThreatIds.addAll(currentlyVisibleIds);

        pveActiveCount = visibleThreatCount;

        if (visibleThreatCount > 0) {
            pveSessionActive = true;
            long dangerHoldTicks = Math.round(DsmClientConfig.VALUES.dangerHoldSeconds.get() * 20.0);
            pveDangerHoldUntilTick = tickCounter + dangerHoldTicks;
            pveHideStartedTick = -1;
            threatCentroid = threatCentroid.scale(1.0 / visibleThreatCount);

            int groupMin = DsmClientConfig.VALUES.groupThreatMinCount.get();
            if (visibleThreatCount >= groupMin) {
                pveSustainedGroupTicks++;
                long holdTicks = Math.round(DsmClientConfig.VALUES.groupDeescalationHoldSeconds.get() * 20.0);
                pveGroupHoldUntilTick = tickCounter + holdTicks;
            } else {
                pveSustainedGroupTicks = Math.max(0, pveSustainedGroupTicks - 1);
            }

            boolean enoughEnemies = visibleThreatCount >= DsmClientConfig.VALUES.escapeMinEnemies.get();
            boolean lowHp = getHealthRatio(player) <= DsmClientConfig.VALUES.escapeHealthRatio.get();
            boolean fleeingBehavior = isFleeingOrDefensiveEscape(player, playerVel, threatCentroid);
            if (enoughEnemies && lowHp && fleeingBehavior) {
                long grace = Math.round(DsmClientConfig.VALUES.escapeGraceSeconds.get() * 20.0);
                pveEscapeGraceUntilTick = tickCounter + grace;
            } else if (!lowHp) {
                pveEscapeGraceUntilTick = -1;
            }
        } else if (pveSessionActive && tickCounter > pveDangerHoldUntilTick) {
            pveSustainedGroupTicks = 0;

            long ticksAfterHold = tickCounter - pveDangerHoldUntilTick;
            long searchTicks = Math.round(DsmClientConfig.VALUES.searchDurationSeconds.get() * 20.0);
            long hideTicks = Math.round(DsmClientConfig.VALUES.hideDurationSeconds.get() * 20.0);

            if (ticksAfterHold > searchTicks) {
                if (pveHideStartedTick < 0) {
                    pveHideStartedTick = tickCounter;
                }
                long hideElapsed = tickCounter - pveHideStartedTick;
                if (hideTrackFinished || hideElapsed >= hideTicks) {
                    resetPveSession();
                }
            }
        }
    }

    private void evaluatePvp(Level level, LocalPlayer player, Vec3 playerVel, boolean hideTrackFinished) {
        double pvpRadius = DsmClientConfig.VALUES.pvpDetectionRadius.get();
        int visibleAttackers = 0;
        Vec3 attackerCentroid = Vec3.ZERO;

        List<Player> players = level.getEntitiesOfClass(Player.class, player.getBoundingBox().inflate(pvpRadius),
                p -> p != player && p.isAlive() && !p.isSpectator() && !player.isAlliedTo(p));

        boolean crouchingNearUnallied = false;
        double hideRadius = DsmClientConfig.VALUES.pvpHideRadius.get();

        for (Player other : players) {
            double dist = other.distanceTo(player);
            boolean isKnownAttacker = recentPvpAttackers.containsKey(other.getUUID());
            boolean hasLos = hasRealLineOfSight(level, other, player);

            if (isKnownAttacker && hasLos) {
                visibleAttackers++;
                attackerCentroid = attackerCentroid.add(other.position());
            } else if (!DsmClientConfig.VALUES.pvpHideRequiresPriorCombat.get()
                    && dist <= hideRadius
                    && player.isCrouching()
                    && !hasLos) {
                crouchingNearUnallied = true;
            }
        }

        pvpActiveCount = visibleAttackers;

        if (visibleAttackers > 0) {
            pvpSessionActive = true;
            long dangerHoldTicks = Math.round(DsmClientConfig.VALUES.dangerHoldSeconds.get() * 20.0);
            pvpDangerHoldUntilTick = tickCounter + dangerHoldTicks;
            pvpHideStartedTick = -1;
            attackerCentroid = attackerCentroid.scale(1.0 / visibleAttackers);

            boolean enoughPlayers = visibleAttackers >= DsmClientConfig.VALUES.pvpEscapeMinEnemies.get();
            boolean lowHp = getHealthRatio(player) <= DsmClientConfig.VALUES.escapeHealthRatio.get();
            boolean fleeing = isFleeingOrDefensiveEscape(player, playerVel, attackerCentroid);
            if (enoughPlayers && lowHp && fleeing) {
                long grace = Math.round(DsmClientConfig.VALUES.escapeGraceSeconds.get() * 20.0);
                pvpEscapeGraceUntilTick = tickCounter + grace;
            } else if (!lowHp) {
                pvpEscapeGraceUntilTick = -1;
            }
        } else if (pvpSessionActive) {
            if (tickCounter > pvpDangerHoldUntilTick) {
                long ticksAfterHold = tickCounter - pvpDangerHoldUntilTick;
                long searchTicks = Math.round(DsmClientConfig.VALUES.searchDurationSeconds.get() * 20.0);
                long hideTicks = Math.round(DsmClientConfig.VALUES.hideDurationSeconds.get() * 20.0);

                if (ticksAfterHold > searchTicks) {
                    if (pvpHideStartedTick < 0) {
                        pvpHideStartedTick = tickCounter;
                    }
                    long hideElapsed = tickCounter - pvpHideStartedTick;
                    if (hideTrackFinished || hideElapsed >= hideTicks) {
                        resetPvpSession();
                    }
                }
            }
        } else if (crouchingNearUnallied) {
            pvpHideStartedTick = tickCounter;
        } else {
            pvpHideStartedTick = -1;
        }
    }

    private boolean isFleeingOrDefensiveEscape(LocalPlayer player, Vec3 vel, Vec3 threatCenter) {
        if (player.isSprinting()) {
            return true;
        }
        if (tickCounter - lastDefensiveBlockTick <= 50) {
            return true;
        }
        if (vel.y > 0.25 && !player.onGround()) {
            return true;
        }
        Vec3 away = player.position().subtract(threatCenter);
        Vec3 awayHoriz = new Vec3(away.x, 0.0, away.z);
        Vec3 velHoriz = new Vec3(vel.x, 0.0, vel.z);
        if (awayHoriz.lengthSqr() > 0.01 && velHoriz.lengthSqr() > 0.008) {
            double dot = awayHoriz.normalize().dot(velHoriz.normalize());
            return dot > 0.35;
        }
        return false;
    }

    private static boolean isPotentialHostile(LivingEntity entity) {
        if (entity.getType().is(DsmTags.HOSTILES)) {
            return true;
        }
        if (entity instanceof NeutralMob neutral) {
            return neutral.isAngry() || (entity instanceof Mob m && m.isAggressive());
        }
        return entity instanceof Enemy;
    }

    private static boolean isActivelyThreateningPlayer(LivingEntity entity, LocalPlayer player, double dist, double closeRadius) {
        if (player.getLastHurtByMob() == entity || player.getLastHurtMob() == entity) {
            return true;
        }
        if (entity instanceof Creeper creeper && creeper.getSwellDir() > 0) {
            return true;
        }
        if (entity instanceof EnderMan enderman) {
            return enderman.isCreepy() || enderman.isAggressive();
        }
        if (entity instanceof Spider spider && !spider.isAggressive() && dist > 6.0) {
            return false;
        }
        if (entity instanceof Warden) {
            return true;
        }
        if (entity instanceof Mob mob && mob.isAggressive()) {
            return true;
        }
        if (dist <= closeRadius) {
            Vec3 look = entity.getLookAngle();
            Vec3 toPlayer = player.getEyePosition().subtract(entity.getEyePosition()).normalize();
            return look.dot(toPlayer) > 0.25;
        }
        return false;
    }

    private static boolean hasRealLineOfSight(Level level, LivingEntity from, LocalPlayer to) {
        Vec3 start = from.getEyePosition();
        Vec3 end = to.getEyePosition();
        HitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, from));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static boolean isBehindOrSurprise(LocalPlayer player, LivingEntity entity) {
        Vec3 look = player.getLookAngle();
        Vec3 toEntity = entity.getEyePosition().subtract(player.getEyePosition()).normalize();
        return look.dot(toEntity) < 0.25;
    }

    private void updateBossStatus(Minecraft mc, Level level, LocalPlayer player) {
        Map<?, ?> events = getBossOverlayEvents(mc.gui.getBossOverlay());
        boolean hasBar = events != null && !events.isEmpty();
        if (!hasBar) {
            bossActive = false;
            return;
        }
        if (!DsmClientConfig.VALUES.bossBarRequiresHostileNear.get()) {
            bossActive = true;
            return;
        }
        double radius = DsmClientConfig.VALUES.bossDetectionRadius.get();
        List<LivingEntity> nearHostiles = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(radius),
                e -> e.isAlive() && (e instanceof Enemy || e.getType().is(DsmTags.HOSTILES)));
        bossActive = !nearHostiles.isEmpty();
    }

    private Map<?, ?> getBossOverlayEvents(BossHealthOverlay overlay) {
        if (overlay == null) {
            return null;
        }
        if (!bossReflectionChecked) {
            bossReflectionChecked = true;
            for (Field f : BossHealthOverlay.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    bossEventsField = f;
                    break;
                }
            }
        }
        if (bossEventsField == null) {
            return null;
        }
        try {
            Object val = bossEventsField.get(overlay);
            if (val instanceof Map<?, ?> map) {
                for (Object v : map.values()) {
                    if (v instanceof LerpingBossEvent) {
                        return map;
                    }
                }
                return map;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static float getHealthRatio(LocalPlayer player) {
        float max = Math.max(1.0f, player.getMaxHealth());
        return player.getHealth() / max;
    }

    public boolean isPveSessionActive() {
        return pveSessionActive;
    }

    public int getPveActiveCount() {
        return pveActiveCount;
    }

    public boolean isPveDangerActive() {
        return pveSessionActive && (pveActiveCount > 0 || tickCounter <= pveDangerHoldUntilTick);
    }

    public boolean isPveEscapeActive() {
        return pveSessionActive && tickCounter <= pveEscapeGraceUntilTick;
    }

    public boolean isPveGroupActive() {
        if (!pveSessionActive || !isPveDangerActive()) {
            return false;
        }
        int confirmTicks = DsmClientConfig.VALUES.groupEscalationConfirmTicks.get();
        return pveSustainedGroupTicks >= confirmTicks || tickCounter <= pveGroupHoldUntilTick;
    }

    public boolean isPveSearchActive() {
        if (!pveSessionActive || isPveDangerActive()) {
            return false;
        }
        long ticksAfterHold = tickCounter - pveDangerHoldUntilTick;
        long searchTicks = Math.round(DsmClientConfig.VALUES.searchDurationSeconds.get() * 20.0);
        return ticksAfterHold <= searchTicks;
    }

    public boolean isPveHideActive() {
        return pveSessionActive && !isPveDangerActive() && !isPveSearchActive() && pveHideStartedTick >= 0;
    }

    public boolean isPvpSessionActive() {
        return pvpSessionActive;
    }

    public int getPvpActiveCount() {
        return pvpActiveCount;
    }

    public boolean isPvpDangerActive() {
        return pvpSessionActive && (pvpActiveCount > 0 || tickCounter <= pvpDangerHoldUntilTick);
    }

    public boolean isPvpEscapeActive() {
        return pvpSessionActive && tickCounter <= pvpEscapeGraceUntilTick;
    }

    public boolean isPvpGroupActive() {
        return pvpSessionActive && pvpActiveCount >= DsmClientConfig.VALUES.pvpGroupMinCount.get();
    }

    public boolean isPvpSearchActive() {
        if (!pvpSessionActive || isPvpDangerActive()) {
            return false;
        }
        long ticksAfterHold = tickCounter - pvpDangerHoldUntilTick;
        long searchTicks = Math.round(DsmClientConfig.VALUES.searchDurationSeconds.get() * 20.0);
        return ticksAfterHold <= searchTicks;
    }

    public boolean isPvpHideActive() {
        if (pvpSessionActive && !isPvpDangerActive() && !isPvpSearchActive() && pvpHideStartedTick >= 0) {
            return true;
        }
        return !pvpSessionActive && pvpHideStartedTick >= 0;
    }

    public boolean isBossActive() {
        return bossActive;
    }

    public boolean isPendingJumpscare() {
        return pendingJumpscare;
    }

    public boolean isHealingActive() {
        return tickCounter <= healingUntilTick;
    }

    public long getRemainingJumpscareCooldownSeconds() {
        long diff = jumpscareCooldownUntilTick - tickCounter;
        return Math.max(0L, Math.round(diff / 20.0));
    }

    public String getThreatSummary() {
        if (!pveSessionActive) {
            return "none";
        }
        if (pveActiveCount > 0) {
            return pveActiveCount + " active";
        }
        if (isPveDangerActive()) {
            return "hold";
        }
        return isPveSearchActive() ? "searching" : (isPveHideActive() ? "hiding" : "fading");
    }

    public String getPvpSummary() {
        if (!pvpSessionActive) {
            return isPvpHideActive() ? "sneak" : "none";
        }
        if (pvpActiveCount > 0) {
            return pvpActiveCount + " active";
        }
        if (isPvpDangerActive()) {
            return "hold";
        }
        return isPvpSearchActive() ? "searching" : "hiding";
    }
}