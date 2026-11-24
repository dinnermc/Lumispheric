package dinner.dev.lumispheric.mixin;

import dinner.dev.lumispheric.SkyRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(DimensionSpecialEffects.EndEffects.class)
public abstract class EndEffectsMixin extends DimensionSpecialEffects {
    private EndEffectsMixin() {
        super(Float.NaN, false, SkyType.END, true, false);
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
        if (!SkyRenderer.shouldRenderEnd(level)) {
            return;
        }

        // Recompute pure block-light contribution (pixelX = blockLight 0..15) so we can cleanly
        // replace vanilla's muddy green-grey (0.99, 1.12, 1.0) End ambient with cosmic violet light.
        float block = LightTexture.getBrightness(level.dimensionType(), pixelX) * blockLightRedFlicker;
        float blockG = block * ((block * 0.6F + 0.4F) * 0.6F + 0.4F);
        float blockB = block * (block * block * 0.6F + 0.4F);

        // Luminous lavender-amethyst ambient bounce from the Eye of the Universe overhead,
        // keeping End Stone bright and clearly readable while letting warm torches/crystals pop.
        float r = Math.min(1.0F, block * 0.78F + 0.34F * (1.0F - block * 0.54F));
        float g = Math.min(1.0F, blockG * 0.80F + 0.21F * (1.0F - blockG * 0.54F));
        float b = Math.min(1.0F, blockB * 0.76F + 0.48F * (1.0F - blockB * 0.54F));
        colors.set(r, g, b);
    }
}
