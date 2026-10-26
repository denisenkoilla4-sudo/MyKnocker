package com.myknocker.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.myknocker.KnockerMod;
import com.myknocker.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/** Full-screen face + sound, triggered by a packet from the server. Client only. */
@OnlyIn(Dist.CLIENT)
public final class JumpscareClient {
    /** textures/gui/jumpscare.png - a 256x256 picture, replace it with anything you like. */
    private static final ResourceLocation FACE = new ResourceLocation(KnockerMod.MOD_ID, "textures/gui/jumpscare.png");
    private static final int FACE_SIZE = 256;

    private static int ticksLeft;
    private static int totalTicks = 1;

    public static void trigger(int durationTicks) {
        totalTicks = Math.max(1, durationTicks);
        ticksLeft = totalTicks;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.JUMPSCARE.get(), 1.0F, 1.0F));
    }

    public static void tick() {
        if (ticksLeft > 0) {
            ticksLeft--;
        }
    }

    public static final IGuiOverlay OVERLAY = (gui, graphics, partialTick, width, height) -> {
        if (ticksLeft <= 0) {
            return;
        }
        float remaining = ticksLeft - partialTick;
        float fadeWindow = Math.max(1.0F, totalTicks * 0.35F);
        float alpha = Math.max(0.0F, Math.min(1.0F, remaining / fadeWindow));

        int size = height;
        int x = (width - size) / 2 + (int) ((Math.random() - 0.5D) * 10.0D);
        int y = (int) ((Math.random() - 0.5D) * 10.0D);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.fill(0, 0, width, height, ((int) (alpha * 255.0F) << 24));
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.blit(FACE, x, y, size, size, 0.0F, 0.0F, FACE_SIZE, FACE_SIZE, FACE_SIZE, FACE_SIZE);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    };

    private JumpscareClient() {
    }
}
