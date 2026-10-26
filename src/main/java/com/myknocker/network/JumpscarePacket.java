package com.myknocker.network;

import com.myknocker.client.JumpscareClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: show the jumpscare overlay to one player. */
public class JumpscarePacket {
    private final int durationTicks;

    public JumpscarePacket(int durationTicks) {
        this.durationTicks = durationTicks;
    }

    public static void encode(JumpscarePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.durationTicks);
    }

    public static JumpscarePacket decode(FriendlyByteBuf buf) {
        return new JumpscarePacket(buf.readVarInt());
    }

    public static void handle(JumpscarePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> JumpscareClient.trigger(msg.durationTicks)));
        ctx.get().setPacketHandled(true);
    }
}
