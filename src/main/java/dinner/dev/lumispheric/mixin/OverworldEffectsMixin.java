package dinner.dev.lumispheric.mixin;

import dinner.dev.lumispheric.SkyRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DimensionSpecialEffects.OverworldEffects.class)
public abstract class OverworldEffectsMixin extends DimensionSpecialEffects {
    private OverworldEffectsMixin() {
        super(192.0F, true, SkyType.NORMAL, false, false);
    }

    @Override
    public void adjustLightmapColors(
        ClientLevel level,
        float partialTicks,
        float skyDarken,
        float blockLightRedFlicker,
        float skyLight,
        int pixelX,
        int pixelY,
        Vector3f colors
    ) {
        SkyRenderer.applyOverworldNightLightmap(
            level,
            partialTicks,
            blockLightRedFlicker,
            pixelX,
            pixelY,
            colors
        );
    }
}
