package com.myknocker.client;

import com.myknocker.KnockerMod;
import com.myknocker.entity.KnockerEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/** Points GeckoLib at the three editable asset files. */
public class KnockerModel extends GeoModel<KnockerEntity> {
    private static final ResourceLocation MODEL = new ResourceLocation(KnockerMod.MOD_ID, "geo/knocker.geo.json");
    private static final ResourceLocation TEXTURE = new ResourceLocation(KnockerMod.MOD_ID, "textures/entity/knocker.png");
    private static final ResourceLocation ANIMATION = new ResourceLocation(KnockerMod.MOD_ID, "animations/knocker.animation.json");

    @Override
    public ResourceLocation getModelResource(KnockerEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(KnockerEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(KnockerEntity animatable) {
        return ANIMATION;
    }
}
