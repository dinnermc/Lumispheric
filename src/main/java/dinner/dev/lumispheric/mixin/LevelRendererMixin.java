package dinner.dev.lumispheric.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
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
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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

import java.util.ArrayList;
import java.util.List;

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

    @Redirect(
        method = "renderClouds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getCloudHeight()F"
        ),
        require = 1
    )
    private float lumispheric$placeCloudDeckAtHighAltitude(DimensionSpecialEffects effects) {
        float vanillaHeight = effects.getCloudHeight();
        ClientLevel level = Minecraft.getInstance().level;
        if (!SkyRenderer.shouldRender(level)) {
            return vanillaHeight;
        }
        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        return SkyRenderer.cloudDeckHeight(vanillaHeight, cameraY);
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 1)
    private void lumispheric$hideVanillaCloudDeckAtHighAltitude(
        PoseStack poseStack, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        double camX, double camY, double camZ, CallbackInfo callback
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (SkyRenderer.shouldRender(level) && SkyRenderer.cloudDeckOpacity(camY) <= 0.001F) {
            callback.cancel();
        }
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
    private void lumispheric$renderSunWithHorizonFade(
        MeshData mesh,
        Matrix4f frustumMatrix,
        Matrix4f projectionMatrix,
        float partialTick,
        Camera camera,
        boolean foggy,
        Runnable setupFog
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!SkyRenderer.shouldRender(level)) {
            BufferUploader.drawWithShader(mesh);
            return;
        }

        double cameraY = camera.getPosition().y;
        float weather = SkyRenderer.weatherStrength(level, cameraY, partialTick);
        float alpha = lumispheric$weatherOpacity(weather);
        float eclipse = SkyRenderer.eclipseStrength(level) * (1.0F - weather);
        float brightness = 1.0F - eclipse * 0.94F;
        float spaceBlend = SkyRenderer.spaceTransition(cameraY);
        try {
            lumispheric$drawCelestialQuad(
                frustumMatrix, partialTick, cameraY, true,
                brightness, brightness, brightness,
                alpha
            );
        } finally {
            mesh.close();
        }
    }

    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V",
            ordinal = 2
        ),
        require = 1,
        expect = 1
    )
    private void lumispheric$renderMoonWithHorizonFade(
        MeshData mesh,
        Matrix4f frustumMatrix,
        Matrix4f projectionMatrix,
        float partialTick,
        Camera camera,
        boolean foggy,
        Runnable setupFog
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!SkyRenderer.shouldRender(level)) {
            BufferUploader.drawWithShader(mesh);
            return;
        }

        double cameraY = camera.getPosition().y;
        float alpha = lumispheric$weatherOpacity(SkyRenderer.weatherStrength(level, cameraY, partialTick));
        try {
            lumispheric$drawCelestialQuad(
                frustumMatrix, partialTick, cameraY, false, 1.0F, 1.0F, 1.0F, alpha
            );
        } finally {
            mesh.close();
        }
    }

    private static void lumispheric$drawCelestialQuad(
        Matrix4f frustumMatrix,
        float partialTick,
        double cameraY,
        boolean sun,
        float red,
        float green,
        float blue,
        float alpha
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        double astronomicalTime = level.getDayTime() + partialTick;
        double latitude = CelestialModel.latitudeRadians();
        CelestialModel.Direction direction = sun
            ? CelestialModel.sunDirection(astronomicalTime, latitude)
            : CelestialModel.moonDirection(astronomicalTime, latitude);
        float skyAngle = level.getTimeOfDay(partialTick);
        float centerY = sun ? 100.0F : -100.0F;
        CelestialModel.QuadVertex[] quad;
        if (sun) {
            float halfSize = 30.0F * Config.celestialSize;
            quad = new CelestialModel.QuadVertex[] {
                lumispheric$celestialVertex(-halfSize, 100.0F, -halfSize, 0.0F, 0.0F,
                    direction, skyAngle, centerY),
                lumispheric$celestialVertex(halfSize, 100.0F, -halfSize, 1.0F, 0.0F,
                    direction, skyAngle, centerY),
                lumispheric$celestialVertex(halfSize, 100.0F, halfSize, 1.0F, 1.0F,
                    direction, skyAngle, centerY),
                lumispheric$celestialVertex(-halfSize, 100.0F, halfSize, 0.0F, 1.0F,
                    direction, skyAngle, centerY)
            };
        } else {
            float halfSize = 20.0F * Config.celestialSize;
            int phase = SkyRenderer.moonPhase(level);
            int column = phase % 4;
            int row = phase / 4 % 2;
            float u0 = column / 4.0F;
            float v0 = row / 2.0F;
            float u1 = (column + 1) / 4.0F;
            float v1 = (row + 1) / 2.0F;
            quad = new CelestialModel.QuadVertex[] {
                lumispheric$celestialVertex(-halfSize, -100.0F, halfSize, u1, v1,
                    direction, skyAngle, centerY),
                lumispheric$celestialVertex(halfSize, -100.0F, halfSize, u0, v1,
                    direction, skyAngle, centerY),
                lumispheric$celestialVertex(halfSize, -100.0F, -halfSize, u0, v0,
                    direction, skyAngle, centerY),
                lumispheric$celestialVertex(-halfSize, -100.0F, -halfSize, u1, v0,
                    direction, skyAngle, centerY)
            };
        }

        float glowScale = sun ? 1.32F : 1.42F;
        CelestialModel.QuadVertex[] glowQuad = lumispheric$scaleCelestialQuad(
            quad, glowScale, skyAngle
        );
        float horizon = SkyRenderer.celestialHorizonLevel(cameraY);
        float fadeTop = SkyRenderer.celestialHorizonFadeTop(cameraY);
        boolean bodyVisible = lumispheric$hasSkyVisibility(quad, horizon);
        boolean glowVisible = lumispheric$hasSkyVisibility(glowQuad, horizon);
        if (!bodyVisible && !glowVisible) {
            return;
        }
        float moonDaylight = sun
            ? 0.0F
            : SkyRenderer.surfaceDaylightStrength(
                (float) CelestialModel.sunDirection(astronomicalTime, latitude).up(),
                level.getStarBrightness(partialTick), cameraY
            );
        float moonDayBrightness = 1.0F - moonDaylight * 0.995F;
        float spaceBlend = SkyRenderer.spaceTransition(cameraY);
        float solarBodyBrightness = Mth.lerp(spaceBlend, 0.80F, 1.0F);
        CelestialModel.LunarEclipse lunarEclipse = sun
            ? new CelestialModel.LunarEclipse(0.0F, 0.0F, 0.0F, 0.0F)
            : CelestialModel.lunarEclipse(astronomicalTime, latitude);

        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(frustumMatrix);
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(skyAngle * 360.0F));
        Matrix4f matrix = poseStack.last().pose();

        if (bodyVisible) {
            lumispheric$drawCelestialPass(
                quad, matrix, horizon, fadeTop, cameraY,
                red * (sun ? solarBodyBrightness : 1.0F),
                green * (sun ? solarBodyBrightness : 1.0F),
                blue * (sun ? solarBodyBrightness : 1.0F),
                alpha, 0.0F, false, sun ? 1.0F : moonDayBrightness,
                sun ? 0.0F : lunarEclipse.shadowOffsetX(),
                sun ? 0.0F : lunarEclipse.shadowOffsetY(), !sun
            );
        }
        if (glowVisible) {
            float glowRed = sun ? red : Mth.lerp(lunarEclipse.umbra(), 0.78F, 0.62F);
            float glowGreen = sun
                ? red
                : Mth.lerp(lunarEclipse.umbra(), 0.84F, 0.18F);
            float glowBlue = sun
                ? red
                : Mth.lerp(lunarEclipse.umbra(), 0.95F, 0.06F);
            float glowIntensity = sun
                ? Mth.lerp(spaceBlend, 0.006F, 0.0025F)
                : 0.015F * CelestialModel.moonIllumination(astronomicalTime)
                    * (1.0F - moonDaylight)
                    * (1.0F - lunarEclipse.penumbra() * 0.35F - lunarEclipse.umbra() * 0.55F);
            if (glowIntensity > 0.001F) {
                lumispheric$drawCelestialPass(
                    glowQuad, matrix, horizon, fadeTop, cameraY,
                    glowRed, glowGreen, glowBlue, alpha * glowIntensity,
                    glowScale, true, 1.0F, 0.0F, 0.0F, !sun
                );
            }
        }
    }

    private static CelestialModel.QuadVertex[] lumispheric$scaleCelestialQuad(
        CelestialModel.QuadVertex[] quad, float scale, float skyAngle
    ) {
        float centerX = (quad[0].x() + quad[2].x()) * 0.5F;
        float centerY = (quad[0].y() + quad[2].y()) * 0.5F;
        float centerZ = (quad[0].z() + quad[2].z()) * 0.5F;
        CelestialModel.QuadVertex[] scaled = new CelestialModel.QuadVertex[quad.length];
        for (int i = 0; i < quad.length; i++) {
            float x = centerX + (quad[i].x() - centerX) * scale;
            float y = centerY + (quad[i].y() - centerY) * scale;
            float z = centerZ + (quad[i].z() - centerZ) * scale;
            Vector3f position = new Vector3f(x, y, z);
            scaled[i] = new CelestialModel.QuadVertex(
                x, y, z, quad[i].u(), quad[i].v(), CelestialModel.localUpFromPreSky(position, skyAngle)
            );
        }
        return scaled;
    }

    private static boolean lumispheric$hasSkyVisibility(
        CelestialModel.QuadVertex[] quad, float horizon
    ) {
        for (CelestialModel.QuadVertex vertex : quad) {
            if (vertex.localUp() > horizon) {
                return true;
            }
        }
        return false;
    }

    private static void lumispheric$drawCelestialPass(
        CelestialModel.QuadVertex[] quad,
        Matrix4f matrix,
        float horizon,
        float fadeTop,
        double cameraY,
        float red,
        float green,
        float blue,
        float alpha,
        float glowScale,
        boolean additive,
        float vertexAlphaScale,
        float lunarShadowOffsetX,
        float lunarShadowOffsetY,
        boolean moonPass
    ) {
        BufferBuilder builder = Tesselator.getInstance().begin(
            VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR
        );
        lumispheric$emitCelestialTriangle(
            builder, matrix, quad[0], quad[1], quad[2], horizon, fadeTop, cameraY,
            vertexAlphaScale
        );
        lumispheric$emitCelestialTriangle(
            builder, matrix, quad[0], quad[2], quad[3], horizon, fadeTop, cameraY,
            vertexAlphaScale
        );

        if (additive) {
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
            );
        }
        try {
            RenderSystem.setShaderColor(red, green, blue, alpha);
            ShaderInstance shader = LumisphericClient.getCelestialShader(
                red, green, blue, alpha, glowScale, lunarShadowOffsetX, lunarShadowOffsetY, moonPass
            );
            RenderSystem.setShader(() -> shader);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private static CelestialModel.QuadVertex lumispheric$celestialVertex(
        float x,
        float y,
        float z,
        float u,
        float v,
        CelestialModel.Direction direction,
        float skyAngle,
        float centerY
    ) {
        Vector3f position = CelestialModel.transformQuadVertex(x, y, z, direction, skyAngle, centerY);
        float localUp = CelestialModel.localUpFromPreSky(position, skyAngle);
        return new CelestialModel.QuadVertex(position.x, position.y, position.z, u, v, localUp);
    }

    private static void lumispheric$emitCelestialTriangle(
        BufferBuilder builder,
        Matrix4f matrix,
        CelestialModel.QuadVertex first,
        CelestialModel.QuadVertex second,
        CelestialModel.QuadVertex third,
        float horizon,
        float fadeTop,
        double cameraY,
        float vertexAlphaScale
    ) {
        List<CelestialModel.QuadVertex> triangle = List.of(first, second, third);
        List<CelestialModel.QuadVertex> fadeBand = lumispheric$clipPolygon(
            lumispheric$clipPolygon(triangle, horizon, true), fadeTop, false
        );
        lumispheric$emitPolygon(builder, matrix, fadeBand, cameraY, true, vertexAlphaScale);

        List<CelestialModel.QuadVertex> opaqueRegion = lumispheric$clipPolygon(triangle, fadeTop, true);
        lumispheric$emitPolygon(builder, matrix, opaqueRegion, cameraY, false, vertexAlphaScale);
    }

    private static List<CelestialModel.QuadVertex> lumispheric$clipPolygon(
        List<CelestialModel.QuadVertex> polygon, float boundary, boolean keepAbove
    ) {
        if (polygon.isEmpty()) {
            return polygon;
        }

        List<CelestialModel.QuadVertex> clipped = new ArrayList<>(polygon.size() + 1);
        CelestialModel.QuadVertex previous = polygon.get(polygon.size() - 1);
        boolean previousInside = keepAbove ? previous.localUp() >= boundary : previous.localUp() <= boundary;
        for (CelestialModel.QuadVertex current : polygon) {
            boolean currentInside = keepAbove ? current.localUp() >= boundary : current.localUp() <= boundary;
            if (previousInside && currentInside) {
                clipped.add(current);
            } else if (previousInside) {
                clipped.add(lumispheric$intersectCelestialEdge(previous, current, boundary));
            } else if (currentInside) {
                clipped.add(lumispheric$intersectCelestialEdge(previous, current, boundary));
                clipped.add(current);
            }
            previous = current;
            previousInside = currentInside;
        }
        return clipped;
    }

    private static CelestialModel.QuadVertex lumispheric$intersectCelestialEdge(
        CelestialModel.QuadVertex start, CelestialModel.QuadVertex end, float boundary
    ) {
        float amount = (boundary - start.localUp()) / (end.localUp() - start.localUp());
        return new CelestialModel.QuadVertex(
            Mth.lerp(amount, start.x(), end.x()),
            Mth.lerp(amount, start.y(), end.y()),
            Mth.lerp(amount, start.z(), end.z()),
            Mth.lerp(amount, start.u(), end.u()),
            Mth.lerp(amount, start.v(), end.v()),
            boundary
        );
    }

    private static void lumispheric$emitPolygon(
        BufferBuilder builder,
        Matrix4f matrix,
        List<CelestialModel.QuadVertex> polygon,
        double cameraY,
        boolean fadeBand,
        float vertexAlphaScale
    ) {
        if (polygon.size() < 3) {
            return;
        }

        CelestialModel.QuadVertex first = polygon.get(0);
        for (int i = 1; i < polygon.size() - 1; i++) {
            lumispheric$emitVertex(builder, matrix, first, cameraY, fadeBand, vertexAlphaScale);
            lumispheric$emitVertex(builder, matrix, polygon.get(i), cameraY, fadeBand, vertexAlphaScale);
            lumispheric$emitVertex(
                builder, matrix, polygon.get(i + 1), cameraY, fadeBand, vertexAlphaScale
            );
        }
    }

    private static void lumispheric$emitVertex(
        BufferBuilder builder,
        Matrix4f matrix,
        CelestialModel.QuadVertex vertex,
        double cameraY,
        boolean fadeBand,
        float vertexAlphaScale
    ) {
        float opacity = (fadeBand
            ? SkyRenderer.celestialHorizonOpacity(vertex.localUp(), cameraY)
            : 1.0F) * vertexAlphaScale;
        builder.addVertex(matrix, vertex.x(), vertex.y(), vertex.z())
            .setUv(vertex.u(), vertex.v())
            .setColor(1.0F, 1.0F, 1.0F, opacity);
    }

    private static float lumispheric$weatherOpacity(float weather) {
        return 1.0F - Math.max(0.0F, Math.min(1.0F, weather)) * 0.65F;
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
        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
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
        VertexBuffer buffer, Matrix4f pose, Matrix4f projection, ShaderInstance shader,
        PoseStack methodPoseStack, Matrix4f methodFrustumMatrix, Matrix4f methodProjectionMatrix,
        float partialTick, double camX, double camY, double camZ
    ) {
        lumispheric$renderCloudPassWithAlphaFade(buffer, pose, projection, shader, partialTick);
    }

    private void lumispheric$renderCloudPassWithAlphaFade(
        VertexBuffer buffer, Matrix4f pose, Matrix4f projection, ShaderInstance shader, float partialTick
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
            double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
            float weatherFade = SkyRenderer.weatherFade(cameraY);
            float rain = level.getRainLevel(1.0F) * weatherFade;
            float thunder = level.getThunderLevel(1.0F) * weatherFade;
            CelestialModel.Direction sun = CelestialModel.sunDirection(
                level.getDayTime() + partialTick, CelestialModel.latitudeRadians()
            );
            ShaderInstance cloudShader = LumisphericClient.getCloudShader(
                rain, thunder, sun, SkyRenderer.cloudDeckOpacity(cameraY)
            );
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

    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"
        ),
        require = 1
    )
    private float lumispheric$allowDayStarsAtHighAltitude(ClientLevel level, float partialTick) {
        float vanillaBrightness = level.getStarBrightness(partialTick);
        if (!SkyRenderer.shouldRender(level)) {
            return vanillaBrightness;
        }

        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        return cameraY >= 2_111.0 ? Math.max(vanillaBrightness, 0.01F) : vanillaBrightness;
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
        args.set(3, lumispheric$weatherOpacity(SkyRenderer.weatherStrength(
            Minecraft.getInstance().level,
            camera.getPosition().y,
            partialTick
        )));
    }

}
