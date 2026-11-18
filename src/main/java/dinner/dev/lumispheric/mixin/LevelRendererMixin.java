package dinner.dev.lumispheric.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dinner.dev.lumispheric.Config;
import dinner.dev.lumispheric.CelestialModel;
import dinner.dev.lumispheric.LumisphericClient;
import dinner.dev.lumispheric.SkyRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    private static final double HIGH_ALTITUDE_WEATHER_THRESHOLD = 1024.0;

    @Shadow
    private VertexBuffer skyBuffer;

    @Shadow
    private VertexBuffer starBuffer;

    @Shadow
    private boolean generateClouds;

    private float lumispheric$lastCloudDistance = Float.NaN;

    @Inject(method = "renderClouds", at = @At("HEAD"), require = 1)
    private void lumispheric$invalidateCloudMeshForDistanceChange(
        PoseStack poseStack, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        double camX, double camY, double camZ, CallbackInfo callback
    ) {
        if (SkyRenderer.shouldRender(Minecraft.getInstance().level)
            && Float.compare(lumispheric$lastCloudDistance, Config.cloudDistance) != 0) {
            generateClouds = true;
            lumispheric$lastCloudDistance = Config.cloudDistance;
        }
    }

    @ModifyConstant(
        method = "buildClouds", constant = @Constant(intValue = -3, ordinal = 0), require = 1, expect = 1
    )
    private int lumispheric$extendFancyCloudsLeft(int original) {
        return lumispheric$expandedCloudBound(original, -5);
    }

    @ModifyConstant(
        method = "buildClouds", constant = @Constant(intValue = -3, ordinal = 1), require = 1, expect = 1
    )
    private int lumispheric$extendFancyCloudsBack(int original) {
        return lumispheric$expandedCloudBound(original, -5);
    }

    @ModifyConstant(
        method = "buildClouds", constant = @Constant(intValue = 4, ordinal = 1), require = 1, expect = 1
    )
    private int lumispheric$extendFancyCloudsRight(int original) {
        return lumispheric$expandedCloudBound(original, 6);
    }

    @ModifyConstant(
        method = "buildClouds", constant = @Constant(intValue = 4, ordinal = 2), require = 1, expect = 1
    )
    private int lumispheric$extendFancyCloudsForward(int original) {
        return lumispheric$expandedCloudBound(original, 6);
    }

    @ModifyConstant(
        method = "buildClouds", constant = @Constant(intValue = -32, ordinal = 0), require = 1, expect = 1
    )
    private int lumispheric$extendFastCloudsLeft(int original) {
        return lumispheric$expandedCloudBound(original, -48);
    }

    @ModifyConstant(
        method = "buildClouds", constant = @Constant(intValue = -32, ordinal = 1), require = 1, expect = 1
    )
    private int lumispheric$extendFastCloudsBack(int original) {
        return lumispheric$expandedCloudBound(original, -48);
    }

    @ModifyConstant(
        method = "buildClouds", constant = @Constant(intValue = 32, ordinal = 1), require = 1, expect = 1
    )
    private int lumispheric$extendFastCloudsRight(int original) {
        return lumispheric$expandedCloudBound(original, 48);
    }

    @ModifyConstant(
        method = "buildClouds", constant = @Constant(intValue = 32, ordinal = 2), require = 1, expect = 1
    )
    private int lumispheric$extendFastCloudsForward(int original) {
        return lumispheric$expandedCloudBound(original, 48);
    }

    private static int lumispheric$expandedCloudBound(int original, int expanded) {
        return SkyRenderer.shouldRender(Minecraft.getInstance().level)
            ? original + Math.round((expanded - original) * Config.cloudDistance)
            : original;
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true, require = 1)
    private void lumispheric$stopHighAltitudePrecipitation(
        LightTexture lightTexture, float partialTick, double camX, double camY, double camZ, CallbackInfo callback
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (camY > HIGH_ALTITUDE_WEATHER_THRESHOLD && SkyRenderer.shouldRender(level)) {
            callback.cancel();
        }
    }

    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMoonPhase()I"
        )
    )
    private int lumispheric$useAstronomicalMoonPhase(ClientLevel level) {
        return SkyRenderer.shouldRender(level) ? SkyRenderer.moonPhase(level) : level.getMoonPhase();
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            ordinal = 2
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$positionSunVertex0(
        Args args, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean foggy, Runnable setupFog
    ) {
        lumispheric$positionCelestialVertex(args, partialTick, true);
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            ordinal = 3
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$positionSunVertex1(
        Args args, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean foggy, Runnable setupFog
    ) {
        lumispheric$positionCelestialVertex(args, partialTick, true);
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            ordinal = 4
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$positionSunVertex2(
        Args args, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean foggy, Runnable setupFog
    ) {
        lumispheric$positionCelestialVertex(args, partialTick, true);
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            ordinal = 5
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$positionSunVertex3(
        Args args, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean foggy, Runnable setupFog
    ) {
        lumispheric$positionCelestialVertex(args, partialTick, true);
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            ordinal = 6
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$positionMoonVertex0(
        Args args, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean foggy, Runnable setupFog
    ) {
        lumispheric$positionCelestialVertex(args, partialTick, false);
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            ordinal = 7
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$positionMoonVertex1(
        Args args, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean foggy, Runnable setupFog
    ) {
        lumispheric$positionCelestialVertex(args, partialTick, false);
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            ordinal = 8
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$positionMoonVertex2(
        Args args, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean foggy, Runnable setupFog
    ) {
        lumispheric$positionCelestialVertex(args, partialTick, false);
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(Lorg/joml/Matrix4f;FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
            ordinal = 9
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$positionMoonVertex3(
        Args args, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean foggy, Runnable setupFog
    ) {
        lumispheric$positionCelestialVertex(args, partialTick, false);
    }

    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V",
            ordinal = 1
        )
    )
    private void lumispheric$dimSunDuringEclipse(MeshData mesh) {
        ClientLevel level = Minecraft.getInstance().level;
        float eclipse = level == null || !SkyRenderer.shouldRender(level)
            ? 0.0F
            : SkyRenderer.eclipseStrength(level) * (1.0F - level.getRainLevel(1.0F));
        if (eclipse <= 0.0F) {
            BufferUploader.drawWithShader(mesh);
            return;
        }

        float alpha = lumispheric$rainOpacity(level.getRainLevel(1.0F));
        float brightness = 1.0F - eclipse * 0.94F;
        RenderSystem.setShaderColor(brightness, brightness * 0.92F, brightness * 0.82F, alpha);
        try {
            BufferUploader.drawWithShader(mesh);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        }
    }

    private static float lumispheric$rainOpacity(float rain) {
        return 1.0F - Math.max(0.0F, Math.min(1.0F, rain)) * 0.65F;
    }

    private static void lumispheric$positionCelestialVertex(Args args, float partialTick, boolean sun) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!SkyRenderer.shouldRender(level)) {
            return;
        }

        double astronomicalTime = level.getDayTime() + partialTick;
        double latitude = CelestialModel.latitudeRadians();
        CelestialModel.Direction direction = sun
            ? CelestialModel.sunDirection(astronomicalTime, latitude)
            : CelestialModel.moonDirection(astronomicalTime, latitude);
        float centerY = sun ? 100.0F : -100.0F;
        org.joml.Vector3f positioned = CelestialModel.transformQuadVertex(
            (float) args.get(1),
            (float) args.get(2),
            (float) args.get(3),
            direction,
            level.getTimeOfDay(partialTick),
            centerY
        );
        args.set(1, positioned.x);
        args.set(2, positioned.y);
        args.set(3, positioned.z);
    }

    @Redirect(
        method = "renderClouds",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V"
        )
    )
    private void lumispheric$renderCloudPass(
        VertexBuffer buffer, Matrix4f pose, Matrix4f projection, ShaderInstance shader
    ) {
        lumispheric$renderCloudPassWithAlphaFade(buffer, pose, projection, shader);
    }

    private void lumispheric$renderCloudPassWithAlphaFade(
        VertexBuffer buffer, Matrix4f pose, Matrix4f projection, ShaderInstance shader
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!SkyRenderer.shouldRender(level)) {
            buffer.drawWithShader(pose, projection, shader);
            return;
        }

        float terrainFogStart = RenderSystem.getShaderFogStart();
        float terrainFogEnd = RenderSystem.getShaderFogEnd();
        float terrainFogRange = Math.max(32.0F, terrainFogEnd - terrainFogStart);
        float distanceScale = 0.65F + Config.cloudDistance * 0.35F;
        float cloudFogStart = terrainFogEnd + Math.max(32.0F, terrainFogRange * 0.5F);
        float cloudFogEnd = cloudFogStart + Math.max(128.0F, terrainFogRange * 3.0F) * distanceScale;

        RenderSystem.setShaderFogStart(cloudFogStart);
        RenderSystem.setShaderFogEnd(cloudFogEnd);
        try {
            float rain = level.getRainLevel(1.0F);
            float thunder = level.getThunderLevel(1.0F);
            ShaderInstance cloudShader = LumisphericClient.getCloudShader(rain, thunder);
            buffer.drawWithShader(pose, projection, cloudShader);
        } finally {
            RenderSystem.setShaderFogStart(terrainFogStart);
            RenderSystem.setShaderFogEnd(terrainFogEnd);
        }
    }

    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"
        )
    )
    private float[] lumispheric$replaceVanillaSunriseFan(DimensionSpecialEffects effects, float dayTime, float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (SkyRenderer.shouldRender(level)) {
            return null;
        }
        return effects.getSunriseColor(dayTime, partialTick);
    }

    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V"
        )
    )
    private void lumispheric$renderSkyAndStars(
        VertexBuffer buffer, Matrix4f pose, Matrix4f projection, ShaderInstance shader,
        Matrix4f methodPose, Matrix4f methodProjection, float partialTick, Camera camera,
        boolean foggy, Runnable setupFog
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (SkyRenderer.shouldRender(level)) {
            if (buffer == skyBuffer) {
                SkyRenderer.render(pose, projection, partialTick, level);
                return;
            }
            if (buffer == starBuffer) {
                SkyRenderer.renderStars(methodPose, methodProjection, level, partialTick);
                return;
            }
        }
        buffer.drawWithShader(pose, projection, shader);
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V",
            ordinal = 2
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$keepCelestialBodiesVisibleInRain(
        Args args,
        Matrix4f methodPose,
        Matrix4f methodProjection,
        float partialTick,
        Camera camera,
        boolean foggy,
        Runnable setupFog
    ) {
        if (!SkyRenderer.shouldRender(Minecraft.getInstance().level)) {
            return;
        }
        float vanillaOpacity = (float) args.get(3);
        args.set(3, lumispheric$rainOpacity(1.0F - vanillaOpacity));
    }

    @ModifyArgs(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V",
            ordinal = 3
        )
    )
    private void lumispheric$adjustStarBrightness(
        Args args,
        Matrix4f methodPose,
        Matrix4f methodProjection,
        float partialTick,
        Camera camera,
        boolean foggy,
        Runnable setupFog
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!SkyRenderer.shouldRender(level)) {
            return;
        }
        float brightness = Config.starBrightness;
        brightness *= SkyRenderer.starVisibility(level);
        for (int i = 0; i < 4; i++) {
            args.set(i, (float) args.get(i) * brightness);
        }
    }
}
