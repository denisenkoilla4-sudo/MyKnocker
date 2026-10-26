package com.myknocker.registry;

import com.myknocker.KnockerMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, KnockerMod.MOD_ID);

    /** Creative-mode spawn egg, handy for testing. The mod itself spawns the Knocker through events. */
    public static final RegistryObject<Item> KNOCKER_SPAWN_EGG = ITEMS.register("knocker_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.KNOCKER, 0x161616, 0x7A0B0B, new Item.Properties()));

    private ModItems() {
    }
}
