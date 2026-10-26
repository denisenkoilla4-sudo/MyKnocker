package com.myknocker;

import com.myknocker.config.KnockerConfig;
import com.myknocker.director.KnockerDirector;
import com.myknocker.entity.KnockerEntity;
import com.myknocker.network.KnockerNetwork;
import com.myknocker.registry.ModEntities;
import com.myknocker.registry.ModItems;
import com.myknocker.registry.ModSounds;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(KnockerMod.MOD_ID)
public class KnockerMod {
    public static final String MOD_ID = "myknocker";

    public KnockerMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModEntities.ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModSounds.SOUNDS.register(modBus);

        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onAttributes);
        modBus.addListener(this::onCreativeTab);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, KnockerConfig.SPEC);

        MinecraftForge.EVENT_BUS.register(new KnockerDirector());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(KnockerNetwork::register);
    }

    private void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.KNOCKER.get(), KnockerEntity.createAttributes().build());
    }

    private void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.KNOCKER_SPAWN_EGG);
        }
    }
}
