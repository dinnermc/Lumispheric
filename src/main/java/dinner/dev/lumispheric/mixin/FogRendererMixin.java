package dinner.dev.lumispheric.mixin;

import dinner.dev.lumispheric.SkyRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    @Shadow
    private static float fogRed;

    @Shadow
    private static float fogGreen;

    @Shadow
    private static float fogBlue;

    @Inject(method = "setupColor", at = @At("RETURN"))
    private static void lumispheric$matchHorizonFog(
        Camera camera, float partialTick, ClientLevel level, int renderDistance, float darkenWorldAmount, CallbackInfo callback
    ) {
        if (!SkyRenderer.shouldRender(level) || camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        net.minecraft.world.phys.Vec3 color = SkyRenderer.horizonFogColor(
            level, camera.getPosition().y
        );
        fogRed = (float) color.x;
        fogGreen = (float) color.y;
        fogBlue = (float) color.z;
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
    }
}
