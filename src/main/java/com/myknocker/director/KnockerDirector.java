package com.myknocker.director;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.myknocker.config.KnockerConfig;
import com.myknocker.entity.KnockerEntity;
import com.myknocker.registry.ModEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides when and where the Knocker shows up. Runs on the server and treats every
 * player separately, so it works for everyone on a multiplayer server.
 */
public class KnockerDirector {
    private final Map<UUID, Long> nextEvent = new HashMap<>();
    private final Map<UUID, Long> nextSleepVisit = new HashMap<>();
    private final Set<UUID> sleepRolled = new HashSet<>();

    // ------------------------------------------------------------------ ticking

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) {
            return;
        }
        if (!(event.level instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() != Level.OVERWORLD || level.getDifficulty() == Difficulty.PEACEFUL) {
            return;
        }
        if (level.players().isEmpty()) {
            return;
        }
        long time = level.getGameTime();
        if (time % 10 == 0) {
            tickSleepers(level, time);
        }
        if (time % 200 == 0) {
            tickEvents(level, time);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        this.nextEvent.remove(id);
        this.nextSleepVisit.remove(id);
        this.sleepRolled.remove(id);
    }

    private void tickEvents(ServerLevel level, long time) {
        boolean night = isNight(level);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            UUID id = player.getUUID();
            long next = this.nextEvent.computeIfAbsent(id, k -> time + 3000L + level.random.nextInt(3000));
            if (time < next || hasKnocker(level, player)) {
                continue;
            }
            double chance = KnockerConfig.EVENT_CHANCE.get() * (night ? 1.0D : KnockerConfig.DAY_CHANCE_MULTIPLIER.get());
            if (level.random.nextDouble() >= chance) {
                continue;
            }
            if (startEvent(level, player, night)) {
                this.nextEvent.put(id, time + cooldownTicks(level));
            }
        }
    }

    private void tickSleepers(ServerLevel level, long time) {
        for (ServerPlayer player : level.players()) {
            UUID id = player.getUUID();
            if (!player.isSleeping()) {
                this.sleepRolled.remove(id);
                continue;
            }
            if (!KnockerConfig.ENABLE_SLEEP_VISIT.get() || player.getSleepTimer() < 20 || this.sleepRolled.contains(id)) {
                continue;
            }
            this.sleepRolled.add(id); // one roll per sleep
            if (time < this.nextSleepVisit.getOrDefault(id, 0L)) {
                continue;
            }
            if (level.random.nextDouble() >= KnockerConfig.SLEEP_VISIT_CHANCE.get() || hasKnocker(level, player)) {
                continue;
            }
            if (spawnSleepVisit(level, player)) {
                this.nextSleepVisit.put(id, time + 24000L + level.random.nextInt(24000));
            }
        }
    }

    private static long cooldownTicks(ServerLevel level) {
        int min = Math.min(KnockerConfig.MIN_COOLDOWN_MINUTES.get(), KnockerConfig.MAX_COOLDOWN_MINUTES.get());
        int max = Math.max(KnockerConfig.MIN_COOLDOWN_MINUTES.get(), KnockerConfig.MAX_COOLDOWN_MINUTES.get());
        long minTicks = min * 1200L;
        long span = (max - min) * 1200L;
        return minTicks + (span > 0 ? (long) (level.random.nextDouble() * span) : 0L);
    }

    private static boolean isNight(ServerLevel level) {
        long t = level.getDayTime() % 24000L;
        return t >= 13000L && t < 23000L;
    }

    // ------------------------------------------------------------------ starting events

    private boolean startEvent(ServerLevel level, ServerPlayer player, boolean night) {
        boolean knock = night && isAtHome(level, player) && KnockerConfig.ENABLE_KNOCKING.get();
        boolean stalk = KnockerConfig.ENABLE_STALKING.get();
        if (!knock && !stalk) {
            return false;
        }
        if (knock && stalk) {
            knock = level.random.nextBoolean();
        }
        boolean attack = KnockerConfig.ENABLE_ATTACKS.get()
                && level.random.nextDouble() < KnockerConfig.ATTACK_CHANCE.get();
        return knock ? spawnKnock(level, player, attack) : spawnStalk(level, player, attack);
    }

    /** "It knows where you live": home = the player's bed / respawn point. */
    private static boolean isAtHome(ServerLevel level, ServerPlayer player) {
        BlockPos home = player.getRespawnPosition();
        return home != null
                && player.getRespawnDimension() == level.dimension()
                && player.blockPosition().distSqr(home) <= 28.0D * 28.0D;
    }

    private boolean spawnStalk(ServerLevel level, ServerPlayer player, boolean attack) {
        double dist = 24.0D + level.random.nextDouble() * 12.0D;
        boolean behind = level.random.nextFloat() < 0.6F;
        BlockPos spot = findSpotAround(level, player, dist, behind);
        return spot != null && create(level, spot, player, KnockerEntity.Mode.STALK, attack) != null;
    }

    private boolean spawnKnock(ServerLevel level, ServerPlayer player, boolean attack) {
        BlockPos playerPos = player.blockPosition();
        BlockPos door = findDoor(level, playerPos, 10);
        BlockPos knockAt = null;
        BlockPos spot = null;
        RandomSource r = level.random;

        if (door != null) {
            knockAt = door;
            double doorDist = door.distSqr(playerPos);
            for (int i = 0; i < 30 && spot == null; i++) {
                double ang = r.nextDouble() * Math.PI * 2.0D;
                double d = 3.0D + r.nextDouble() * 2.0D;
                BlockPos cand = findGround(level, Mth.floor(door.getX() + Math.cos(ang) * d),
                        Mth.floor(door.getZ() + Math.sin(ang) * d), door.getY(), 3);
                if (cand != null && cand.distSqr(playerPos) > doorDist + 4.0D) {
                    spot = cand; // on the far side of the door from the player = outside
                }
            }
        } else {
            spot = findSpotAround(level, player, 9.0D + r.nextDouble() * 3.0D, false);
            if (spot != null) {
                Vec3 dir = new Vec3(spot.getX() + 0.5D - player.getX(), 0.0D, spot.getZ() + 0.5D - player.getZ()).normalize();
                knockAt = BlockPos.containing(player.getX() + dir.x * 2.5D, player.getY() + 1.0D, player.getZ() + dir.z * 2.5D);
            }
        }
        if (spot == null) {
            return false;
        }
        KnockerEntity k = create(level, spot, player, KnockerEntity.Mode.KNOCK, attack);
        if (k == null) {
            return false;
        }
        k.setKnockPos(knockAt);
        return true;
    }

    private boolean spawnSleepVisit(ServerLevel level, ServerPlayer player) {
        boolean attack = KnockerConfig.ENABLE_ATTACKS.get()
                && level.random.nextDouble() < KnockerConfig.ATTACK_CHANCE.get();
        BlockPos bed = player.blockPosition();
        for (int i = 0; i < 30; i++) {
            double ang = level.random.nextDouble() * Math.PI * 2.0D;
            double d = 1.8D + level.random.nextDouble() * 1.2D;
            BlockPos spot = findGround(level, Mth.floor(player.getX() + Math.cos(ang) * d),
                    Mth.floor(player.getZ() + Math.sin(ang) * d), bed.getY(), 2);
            if (spot != null) {
                return create(level, spot, player, KnockerEntity.Mode.SLEEP_VISIT, attack) != null;
            }
        }
        return false;
    }

    private static KnockerEntity create(ServerLevel level, BlockPos pos, ServerPlayer player,
                                        KnockerEntity.Mode mode, boolean attack) {
        KnockerEntity k = ModEntities.KNOCKER.get().create(level);
        if (k == null) {
            return null;
        }
        k.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        k.setTargetPlayer(player.getUUID());
        k.setMode(mode);
        k.planAttack(attack);
        level.addFreshEntity(k);
        return k;
    }

    private static boolean hasKnocker(ServerLevel level, ServerPlayer player) {
        UUID id = player.getUUID();
        List<KnockerEntity> found = level.getEntitiesOfClass(KnockerEntity.class,
                player.getBoundingBox().inflate(192.0D), k -> id.equals(k.getTargetPlayerId()));
        return !found.isEmpty();
    }

    // ------------------------------------------------------------------ position search

    private static BlockPos findSpotAround(ServerLevel level, ServerPlayer player, double dist, boolean behind) {
        RandomSource r = level.random;
        Vec3 look = player.getLookAngle();
        double behindAngle = Math.atan2(-look.z, -look.x);
        for (int i = 0; i < 30; i++) {
            double ang = behind ? behindAngle + (r.nextDouble() - 0.5D) * 2.0D : r.nextDouble() * Math.PI * 2.0D;
            double d = dist + (r.nextDouble() - 0.5D) * 4.0D;
            BlockPos spot = findGround(level, Mth.floor(player.getX() + Math.cos(ang) * d),
                    Mth.floor(player.getZ() + Math.sin(ang) * d), player.getBlockY(), 10);
            if (spot != null) {
                return spot;
            }
        }
        return null;
    }

    /** Looks for a free standing spot near the given height, searching 0, +1, -1, +2, -2 ... */
    private static BlockPos findGround(ServerLevel level, int x, int z, int aroundY, int range) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return null;
        }
        for (int i = 0; i <= range * 2; i++) {
            int off = ((i + 1) / 2) * (i % 2 == 0 ? -1 : 1);
            int y = aroundY + off;
            if (y < level.getMinBuildHeight() + 1 || y > level.getMaxBuildHeight() - 4) {
                continue;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (isStandable(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(below).isEmpty()) {
            return false;
        }
        AABB box = ModEntities.KNOCKER.get().getAABB(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        return level.noCollision(box);
    }

    private static BlockPos findDoor(ServerLevel level, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -3, -radius), center.offset(radius, 3, radius))) {
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.DOORS)) {
                double d = pos.distSqr(center);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ admin command

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("knocker")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("stalk").executes(c -> force(c, KnockerEntity.Mode.STALK)))
                .then(Commands.literal("knock").executes(c -> force(c, KnockerEntity.Mode.KNOCK)))
                .then(Commands.literal("sleep").executes(c -> force(c, KnockerEntity.Mode.SLEEP_VISIT)))
                .then(Commands.literal("hunt").executes(c -> force(c, KnockerEntity.Mode.HUNT)))
                .then(Commands.literal("clear").executes(this::clear)));
    }

    /** /knocker <stalk|knock|sleep|hunt> - starts that event on yourself right now (testing). */
    private int force(CommandContext<CommandSourceStack> ctx, KnockerEntity.Mode mode) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        boolean ok;
        switch (mode) {
            case KNOCK -> ok = spawnKnock(level, player, false);
            case SLEEP_VISIT -> ok = spawnSleepVisit(level, player);
            case HUNT -> {
                BlockPos spot = findSpotAround(level, player, 14.0D, false);
                KnockerEntity k = spot == null ? null : create(level, spot, player, KnockerEntity.Mode.HUNT, true);
                ok = k != null;
            }
            default -> ok = spawnStalk(level, player, false);
        }
        if (ok) {
            ctx.getSource().sendSuccess(() -> Component.literal("Knocker: " + mode.name().toLowerCase()), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal("Knocker: no free spot found, try again"));
        return 0;
    }

    private int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<KnockerEntity> all = player.serverLevel().getEntitiesOfClass(KnockerEntity.class,
                player.getBoundingBox().inflate(256.0D));
        all.forEach(KnockerEntity::poof);
        int n = all.size();
        ctx.getSource().sendSuccess(() -> Component.literal("Removed " + n + " Knocker(s)"), true);
        return n;
    }
}
