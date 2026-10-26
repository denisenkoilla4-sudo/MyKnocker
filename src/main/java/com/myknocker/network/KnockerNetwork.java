package com.myknocker.network;

import com.myknocker.KnockerMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class KnockerNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(KnockerMod.MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        CHANNEL.registerMessage(0, JumpscarePacket.class,
                JumpscarePacket::encode, JumpscarePacket::decode, JumpscarePacket::handle);
    }

    public static void sendJumpscare(ServerPlayer player, int durationTicks) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new JumpscarePacket(durationTicks));
    }

    private KnockerNetwork() {
    }
}
