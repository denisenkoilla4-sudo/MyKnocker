package com.myknocker.registry;

import com.myknocker.KnockerMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Sound events. What they actually play is defined in assets/myknocker/sounds.json
 * (by default vanilla sounds - drop your own .ogg files in and point sounds.json at them).
 */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, KnockerMod.MOD_ID);

    public static final RegistryObject<SoundEvent> KNOCK = register("knock");
    public static final RegistryObject<SoundEvent> JUMPSCARE = register("jumpscare");
    public static final RegistryObject<SoundEvent> BREATH = register("breath");

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(KnockerMod.MOD_ID, name)));
    }

    private ModSounds() {
    }
}
