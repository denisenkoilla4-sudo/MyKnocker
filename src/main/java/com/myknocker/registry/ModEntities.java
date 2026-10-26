package com.myknocker.registry;

import com.myknocker.KnockerMod;
import com.myknocker.KnockerSize;
import com.myknocker.entity.KnockerEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, KnockerMod.MOD_ID);

    public static final RegistryObject<EntityType<KnockerEntity>> KNOCKER = ENTITIES.register("knocker",
            () -> EntityType.Builder.<KnockerEntity>of(KnockerEntity::new, MobCategory.MONSTER)
                    .sized(KnockerSize.WIDTH, KnockerSize.HEIGHT)
                    .clientTrackingRange(10)
                    .updateInterval(2)
                    .build(new ResourceLocation(KnockerMod.MOD_ID, "knocker").toString()));

    private ModEntities() {
    }
}
