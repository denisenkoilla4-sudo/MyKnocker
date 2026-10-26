package com.myknocker.client;

import com.myknocker.entity.KnockerEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class KnockerRenderer extends GeoEntityRenderer<KnockerEntity> {
    public KnockerRenderer(EntityRendererProvider.Context context) {
        super(context, new KnockerModel());
        this.shadowRadius = 0.5F;
    }
}
