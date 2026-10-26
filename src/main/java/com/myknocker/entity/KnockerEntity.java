package com.myknocker.entity;

import com.myknocker.KnockerSize;
import com.myknocker.config.KnockerConfig;
import com.myknocker.network.KnockerNetwork;
import com.myknocker.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * The stalker. One entity, several behaviours ("modes"), all driven from the server:
 * <ul>
 *   <li>STALK - follows its target, freezes when looked at, creeps closer when not looked at</li>
 *   <li>KNOCK - stands outside the target's home and knocks on a door/wall</li>
 *   <li>SLEEP_VISIT - stands next to the bed of a sleeping target, scares on wake-up</li>
 *   <li>HUNT - chases and hits the target</li>
 *   <li>LEAVING - short pause after a scare, then vanishes</li>
 * </ul>
 */
public class KnockerEntity extends Monster implements GeoEntity {

    public enum Mode {
        STALK, KNOCK, SLEEP_VISIT, HUNT, LEAVING;

        public static Mode byId(int id) {
            Mode[] all = values();
            return all[Mth.clamp(id, 0, all.length - 1)];
        }
    }

    private static final EntityDataAccessor<Integer> DATA_MODE =
            SynchedEntityData.defineId(KnockerEntity.class, EntityDataSerializers.INT);

    // Animation names must match assets/myknocker/animations/knocker.animation.json
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation ANIM_WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ANIM_RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation ANIM_ATTACK = RawAnimation.begin().thenPlay("attack");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private UUID targetPlayerId;
    private BlockPos knockPos;
    private boolean attackPlanned;
    private boolean scared;

    private int lifeTicks;
    private int modeTicks;
    private int lookedAtTicks;
    private int closeTicks;

    // knocking state
    private int knocksWanted;
    private int knocksDone;
    private int knockBurst;
    private int burstTimer;
    private int pauseTimer;

    public KnockerEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 0;
        this.knocksWanted = 3 + this.random.nextInt(4);
        this.pauseTimer = 60;
        if (this.getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 80.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.30D)
                .add(Attributes.ATTACK_DAMAGE, 7.0D)
                .add(Attributes.FOLLOW_RANGE, 64.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.8D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new HuntMeleeGoal(this));
        this.goalSelector.addGoal(2, new OpenDoorGoal(this, false));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_MODE, Mode.STALK.ordinal());
    }

    // ------------------------------------------------------------------ accessors

    public Mode getMode() {
        return Mode.byId(this.entityData.get(DATA_MODE));
    }

    public void setMode(Mode mode) {
        this.entityData.set(DATA_MODE, mode.ordinal());
        this.modeTicks = 0;
    }

    public UUID getTargetPlayerId() {
        return this.targetPlayerId;
    }

    public void setTargetPlayer(UUID id) {
        this.targetPlayerId = id;
    }

    public void setKnockPos(BlockPos pos) {
        this.knockPos = pos;
    }

    public void planAttack(boolean attack) {
        this.attackPlanned = attack;
    }

    private ServerPlayer getTargetPlayer() {
        if (this.targetPlayerId == null) {
            return null;
        }
        Player p = this.level().getPlayerByUUID(this.targetPlayerId);
        return p instanceof ServerPlayer sp ? sp : null;
    }

    // ------------------------------------------------------------------ server logic

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide) {
            serverTick();
        }
    }

    private void serverTick() {
        this.lifeTicks++;
        this.modeTicks++;

        ServerPlayer target = getTargetPlayer();
        if (target == null && this.targetPlayerId == null) {
            // spawned by egg or /summon: adopt the nearest player
            Player nearest = this.level().getNearestPlayer(this, 64.0D);
            if (nearest instanceof ServerPlayer sp && !sp.isSpectator()) {
                this.targetPlayerId = sp.getUUID();
                target = sp;
            }
        }
        if (target == null || !target.isAlive() || target.isSpectator() || target.level() != this.level()) {
            if (this.lifeTicks > 100) {
                poof();
            }
            return;
        }

        if (getMode() != Mode.HUNT && this.lifeTicks > KnockerConfig.MAX_LIFETIME_SECONDS.get() * 20) {
            poof();
            return;
        }

        double dist = this.distanceTo(target);
        boolean seen = isSeenBy(target);
        if (seen) {
            this.lookedAtTicks++;
        } else {
            this.lookedAtTicks = Math.max(0, this.lookedAtTicks - 2);
        }

        switch (getMode()) {
            case STALK -> tickStalk(target, dist, seen);
            case KNOCK -> tickKnock(target, dist, seen);
            case SLEEP_VISIT -> tickSleepVisit(target);
            case HUNT -> tickHunt(target);
            case LEAVING -> tickLeaving(target);
        }
    }

    private void tickStalk(ServerPlayer target, double dist, boolean seen) {
        if (this.tickCount % 140 == 0 && dist < 28.0D) {
            this.playSound(ModSounds.BREATH.get(), 0.6F, 0.9F);
        }

        if (seen) {
            // it freezes while you look at it
            this.getNavigation().stop();
            faceTarget(target);
            if (!this.scared && dist < 10.0D) {
                jumpscare(target);
            } else if (this.lookedAtTicks > 90) {
                poof();
            }
            return;
        }

        if (dist > 4.5D) {
            this.closeTicks = 0;
            if (this.tickCount % 10 == 0 || this.getNavigation().isDone()) {
                this.getNavigation().moveTo(target, 0.85D);
            }
        } else {
            // right behind you
            this.getNavigation().stop();
            faceTarget(target);
            this.closeTicks++;
            if (this.closeTicks > 100) {
                if (this.attackPlanned && KnockerConfig.ENABLE_ATTACKS.get()) {
                    jumpscare(target);
                } else {
                    poof();
                }
            }
        }
    }

    private void tickKnock(ServerPlayer target, double dist, boolean seen) {
        this.getNavigation().stop();
        faceTarget(target);

        if (this.knockBurst > 0) {
            if (--this.burstTimer <= 0) {
                playKnock();
                this.knockBurst--;
                this.burstTimer = 6 + this.random.nextInt(4);
                if (this.knockBurst == 0) {
                    this.knocksDone++;
                }
            }
        } else if (--this.pauseTimer <= 0) {
            this.knockBurst = 3;
            this.burstTimer = 1;
            this.pauseTimer = 50 + this.random.nextInt(90);
        }

        if (seen && dist < 20.0D) {
            if (!this.scared && dist < 12.0D && this.lookedAtTicks > 20) {
                jumpscare(target); // caught it staring through the window
                return;
            }
            if (this.lookedAtTicks > 140) {
                poof();
                return;
            }
        }

        if (this.knocksDone >= this.knocksWanted && this.knockBurst == 0) {
            if (this.attackPlanned && KnockerConfig.ENABLE_ATTACKS.get()) {
                jumpscare(target);
            } else {
                setMode(Mode.LEAVING);
            }
        }
    }

    private void tickSleepVisit(ServerPlayer target) {
        this.getNavigation().stop();
        faceTarget(target);

        if (!target.isSleeping()) {
            // you woke up and it is standing right there
            if (!this.scared) {
                jumpscare(target);
            } else {
                setMode(Mode.LEAVING);
            }
            return;
        }
        if (this.tickCount % 80 == 0) {
            this.playSound(ModSounds.BREATH.get(), 0.7F, 0.85F);
        }
        if (this.modeTicks > 2400) {
            poof();
        }
    }

    private void tickHunt(ServerPlayer target) {
        if (this.getTarget() != target) {
            this.setTarget(target);
        }
        if (target.isDeadOrDying()) {
            this.setTarget(null);
            setMode(Mode.LEAVING);
            return;
        }
        if (this.modeTicks > KnockerConfig.HUNT_SECONDS.get() * 20) {
            this.setTarget(null);
            poof();
        }
    }

    private void tickLeaving(ServerPlayer target) {
        this.getNavigation().stop();
        faceTarget(target);
        if (this.modeTicks > 30) {
            poof();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** True when the player is looking roughly at this entity and has a clear line of sight. */
    private boolean isSeenBy(ServerPlayer player) {
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 to = new Vec3(this.getX() - player.getX(),
                this.getEyeY() - player.getEyeY(),
                this.getZ() - player.getZ());
        double len = to.length();
        if (len < 0.001D) {
            return true;
        }
        double dot = look.dot(to.scale(1.0D / len));
        return dot > 0.90D && player.hasLineOfSight(this);
    }

    private void faceTarget(Entity target) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        this.setYRot(yaw);
        this.setYHeadRot(yaw);
        this.setYBodyRot(yaw);
    }

    private void playKnock() {
        BlockPos at = this.knockPos != null ? this.knockPos : this.blockPosition();
        this.level().playSound(null, at, ModSounds.KNOCK.get(), SoundSource.HOSTILE,
                3.0F, 0.9F + this.random.nextFloat() * 0.2F);
    }

    private void jumpscare(ServerPlayer target) {
        this.scared = true;
        this.getNavigation().stop();
        if (KnockerConfig.ENABLE_JUMPSCARE.get()) {
            KnockerNetwork.sendJumpscare(target, 36);
            // everyone except the target hears it in 3D; the target hears it through the packet
            this.level().playSound(target, this.getX(), this.getY(), this.getZ(),
                    ModSounds.JUMPSCARE.get(), SoundSource.HOSTILE, 2.0F, 1.0F);
        }
        if (this.attackPlanned && KnockerConfig.ENABLE_ATTACKS.get()) {
            setMode(Mode.HUNT);
            this.setTarget(target);
        } else {
            setMode(Mode.LEAVING);
        }
    }

    /** Vanishes with a puff of smoke. */
    public void poof() {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE,
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    24, 0.3D, 0.8D, 0.3D, 0.02D);
        }
        this.discard();
    }

    // ------------------------------------------------------------------ vanilla overrides

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && source.getEntity() instanceof ServerPlayer attacker
                && getMode() != Mode.HUNT && KnockerConfig.ENABLE_ATTACKS.get()) {
            this.targetPlayerId = attacker.getUUID();
            this.attackPlanned = true;
            setMode(Mode.HUNT);
            this.setTarget(attacker);
        }
        return hurt;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) {
        return KnockerSize.HEIGHT * 0.92F;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.targetPlayerId != null) {
            tag.putUUID("TargetPlayer", this.targetPlayerId);
        }
        if (this.knockPos != null) {
            tag.putLong("KnockPos", this.knockPos.asLong());
        }
        tag.putInt("KnockerMode", getMode().ordinal());
        tag.putInt("LifeTicks", this.lifeTicks);
        tag.putBoolean("Scared", this.scared);
        tag.putBoolean("AttackPlanned", this.attackPlanned);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("TargetPlayer")) {
            this.targetPlayerId = tag.getUUID("TargetPlayer");
        }
        if (tag.contains("KnockPos")) {
            this.knockPos = BlockPos.of(tag.getLong("KnockPos"));
        }
        this.entityData.set(DATA_MODE, tag.getInt("KnockerMode"));
        this.lifeTicks = tag.getInt("LifeTicks");
        this.scared = tag.getBoolean("Scared");
        this.attackPlanned = tag.getBoolean("AttackPlanned");
    }

    // ------------------------------------------------------------------ GeckoLib

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, this::animationPredicate));
    }

    private PlayState animationPredicate(AnimationState<KnockerEntity> state) {
        if (this.swinging) {
            return state.setAndContinue(ANIM_ATTACK);
        }
        if (state.isMoving()) {
            return state.setAndContinue(getMode() == Mode.HUNT ? ANIM_RUN : ANIM_WALK);
        }
        return state.setAndContinue(ANIM_IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    // ------------------------------------------------------------------ goals

    /** Melee attack that is only active while hunting. */
    private static class HuntMeleeGoal extends MeleeAttackGoal {
        private final KnockerEntity knocker;

        HuntMeleeGoal(KnockerEntity knocker) {
            super(knocker, 1.2D, true);
            this.knocker = knocker;
        }

        @Override
        public boolean canUse() {
            return this.knocker.getMode() == Mode.HUNT && super.canUse();
        }

        @Override
        public boolean canContinueToUse() {
            return this.knocker.getMode() == Mode.HUNT && super.canContinueToUse();
        }
    }
}
