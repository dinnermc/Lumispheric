package dinner.dev.lumispheric.mixin;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import dinner.dev.lumispheric.SkyRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
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
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }

        if (SkyRenderer.shouldRenderEnd(level)) {
            Vec3 endColor = SkyRenderer.endFogColor();
            fogRed = (float) endColor.x;
            fogGreen = (float) endColor.y;
            fogBlue = (float) endColor.z;
            RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
            return;
        }

        if (!SkyRenderer.shouldRender(level)) {
            return;
        }

        Vector3f look = camera.getLookVector();
        Vec3 color = SkyRenderer.horizonFogColor(
            level, camera.getPosition().y, new Vec3(look.x, look.y, look.z)
        );
        fogRed = (float) color.x;
        fogGreen = (float) color.y;
        fogBlue = (float) color.z;
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
    }

    @Inject(method = "setupFog", at = @At("RETURN"))
    private static void lumispheric$addEndAtmosphericDepth(
        Camera camera,
        FogRenderer.FogMode fogMode,
        float farPlaneDistance,
        boolean shouldCreateFog,
        float partialTick,
        CallbackInfo callback
    ) {
        if (fogMode != FogRenderer.FogMode.FOG_TERRAIN || camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (!SkyRenderer.shouldRenderEnd(level)) {
            return;
        }

        // Gentle cosmic violet aerial perspective so obsidian towers and outer islands gain depth
        // without obscuring close combat (preserves closer fog from Blindness/Darkness via Math.min).
        float targetStart = Math.max(24.0F, farPlaneDistance * 0.24F);
        float targetEnd = Math.max(targetStart + 32.0F, farPlaneDistance * 0.98F);
        if (RenderSystem.getShaderFogStart() > targetStart) {
            RenderSystem.setShaderFogStart(targetStart);
            RenderSystem.setShaderFogEnd(Math.min(RenderSystem.getShaderFogEnd(), targetEnd));
            RenderSystem.setShaderFogShape(FogShape.SPHERE);
        }
    }
}
