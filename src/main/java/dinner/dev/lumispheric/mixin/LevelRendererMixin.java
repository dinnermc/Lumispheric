package dinner.dev.lumispheric.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dinner.dev.lumispheric.CelestialModel;
import dinner.dev.lumispheric.Config;
import dinner.dev.lumispheric.LumisphericClient;
import dinner.dev.lumispheric.SkyRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.ArrayList;
import java.util.List;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    private static final double HIGH_ALTITUDE_WEATHER_THRESHOLD = 1024.0;
    private static final double OVERWORLD_SEA_LEVEL = 63.0;
    private static final Vector3f UNIT_Z = new Vector3f(0.0F, 0.0F, 1.0F);

    @Shadow
    private VertexBuffer skyBuffer;

    @Shadow
    private VertexBuffer starBuffer;

    @Shadow
    private boolean generateClouds;

    private float lumispheric$lastCloudDistance = Float.NaN;
    private Matrix4f lumispheric$skyProjectionMatrix;
    private float lumispheric$skyPartialTick;

    @Inject(method = "renderSky", at = @At("HEAD"), require = 1)
    private void lumispheric$captureSkyFrameParams(
        Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean foggy, Runnable setupFog, CallbackInfo callback
    ) {
        this.lumispheric$skyProjectionMatrix = projectionMatrix;
        this.lumispheric$skyPartialTick = partialTick;
    }

    @Inject(method = "renderEndSky", at = @At("HEAD"), cancellable = true, require = 1)
    private void lumispheric$renderCustomEndSky(PoseStack poseStack, CallbackInfo callback) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!SkyRenderer.shouldRenderEnd(level)) {
            return;
        }
        Matrix4f projection = this.lumispheric$skyProjectionMatrix != null
            ? this.lumispheric$skyProjectionMatrix
            : RenderSystem.getProjectionMatrix();
        SkyRenderer.renderEnd(poseStack.last().pose(), projection, this.lumispheric$skyPartialTick, level);
        callback.cancel();
    }

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
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float weather = SkyRenderer.weatherStrength(level, cameraY, partialTick);
        float alpha = lumispheric$weatherOpacity(weather);
        SkyRenderer.FrameAstronomy astro = SkyRenderer.frameAstronomy(level, partialTick);
        float sunUp = (float) astro.sun().up();
        float eclipse = astro.eclipseStrength() * (1.0F - weather);
        float brightness = 1.0F - eclipse * 0.90F;
        Vector3f tint = SkyRenderer.solarTransmission(sunUp, altitude);
        try {
            lumispheric$drawCelestialQuad(
                frustumMatrix, partialTick, cameraY, true,
                brightness * tint.x, brightness * tint.y, brightness * tint.z,
                alpha, eclipse, astro
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
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float alpha = lumispheric$weatherOpacity(SkyRenderer.weatherStrength(level, cameraY, partialTick));
        SkyRenderer.FrameAstronomy astro = SkyRenderer.frameAstronomy(level, partialTick);
        float moonUp = (float) astro.moon().up();
        Vector3f moonExtinction = SkyRenderer.solarTransmission(moonUp, altitude);
        float moonRed = Mth.lerp(0.65F, 1.0F, moonExtinction.x);
        float moonGreen = Mth.lerp(0.65F, 1.0F, moonExtinction.y);
        float moonBlue = Mth.lerp(0.65F, 1.0F, moonExtinction.z);
        try {
            lumispheric$drawCelestialQuad(
                frustumMatrix, partialTick, cameraY, false, moonRed, moonGreen, moonBlue, alpha, 0.0F, astro
            );
            lumispheric$drawPlanets(frustumMatrix, projectionMatrix, partialTick, cameraY, astro);
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
        float alpha,
        float solarEclipse,
        SkyRenderer.FrameAstronomy astro
    ) {
        ClientLevel level = Minecraft.getInstance().level;
        CelestialModel.Direction sunDir = astro.sun();
        CelestialModel.Direction direction = sun ? sunDir : astro.moon();
        float skyAngle = level.getTimeOfDay(partialTick);
        CelestialModel.BillboardBasis basis = CelestialModel.billboardBasis(direction);

        CelestialModel.QuadVertex[] quad;
        if (sun) {
            float halfSize = 30.0F * Config.celestialSize;
            quad = CelestialModel.buildOrientedQuad(
                direction, halfSize, 0.0F, 0.0F, 1.0F, 1.0F, skyAngle, basis
            );
        } else {
            float halfSize = 20.0F * Config.celestialSize;
            quad = CelestialModel.buildOrientedQuad(
                direction, halfSize, 0.0F, 0.0F, 0.25F, 0.5F, skyAngle, basis
            );
        }

        float glowScale = sun ? 1.32F : 1.42F;
        CelestialModel.QuadVertex[] glowQuad = lumispheric$scaleCelestialQuad(quad, glowScale, skyAngle);
        float horizon = SkyRenderer.celestialHorizonLevel(cameraY);
        float fadeTop = SkyRenderer.celestialHorizonFadeTop(cameraY);
        boolean bodyVisible = lumispheric$hasSkyVisibility(quad, horizon);
        boolean glowVisible = lumispheric$hasSkyVisibility(glowQuad, horizon);
        if (!bodyVisible && !glowVisible) {
            return;
        }

        float moonDaylight = sun
            ? 0.0F
            : SkyRenderer.surfaceDaylightStrength((float) sunDir.up(), cameraY);
        float moonIllum = astro.moonIllumination();
        float moonDayBrightness = 1.0F - moonDaylight * Mth.lerp(moonIllum, 0.95F, 0.68F);
        float earthshine = sun
            ? 0.0F
            : 0.055F * (1.0F - moonIllum * 0.85F) * (1.0F - moonDaylight);
        float spaceBlend = SkyRenderer.spaceTransition(cameraY);
        float solarBodyBrightness = Mth.lerp(spaceBlend, 0.82F, 1.0F);
        CelestialModel.LunarEclipse lunarEclipse = sun
            ? CelestialModel.NO_LUNAR_ECLIPSE
            : astro.lunarEclipse();
        Vector3f bodyLightDir = sun
            ? UNIT_Z
            : CelestialModel.lightInBillboardFrame(sunDir, basis);
        float pixelatedStyle = Config.pixelatedStyle ? 1.0F : 0.0F;

        Matrix4f matrix = new Matrix4f(frustumMatrix)
            .rotateY((float) Math.toRadians(-90.0))
            .rotateX((float) Math.toRadians(skyAngle * 360.0));

        if (bodyVisible) {
            lumispheric$drawCelestialPass(
                quad, matrix, horizon, fadeTop, cameraY,
                red * (sun ? solarBodyBrightness : 1.0F),
                green * (sun ? solarBodyBrightness : 1.0F),
                blue * (sun ? solarBodyBrightness : 1.0F),
                alpha, 0.0F, false, sun ? 1.0F : moonDayBrightness,
                sun ? 0.0F : lunarEclipse.shadowOffsetX(),
                sun ? 0.0F : lunarEclipse.shadowOffsetY(),
                !sun, bodyLightDir, pixelatedStyle, earthshine, sun ? spaceBlend : 0.0F, solarEclipse
            );
        }
        if (glowVisible) {
            float glowRed = sun ? Mth.lerp(spaceBlend, red, 1.0F) : Mth.lerp(lunarEclipse.umbra(), 0.78F * red, 0.62F);
            float glowGreen = sun ? Mth.lerp(spaceBlend, green, 1.0F) : Mth.lerp(lunarEclipse.umbra(), 0.84F * green, 0.18F);
            float glowBlue = sun ? Mth.lerp(spaceBlend, blue, 1.0F) : Mth.lerp(lunarEclipse.umbra(), 0.95F * blue, 0.06F);
            float glowIntensity = sun
                ? Mth.lerp(spaceBlend, 0.008F, 0.0045F)
                : 0.018F * moonIllum
                    * (1.0F - moonDaylight)
                    * (1.0F - lunarEclipse.penumbra() * 0.35F - lunarEclipse.umbra() * 0.55F);
            if (glowIntensity > 0.001F) {
                lumispheric$drawCelestialPass(
                    glowQuad, matrix, horizon, fadeTop, cameraY,
                    glowRed, glowGreen, glowBlue, alpha * glowIntensity,
                    glowScale, true, 1.0F, 0.0F, 0.0F, !sun,
                    bodyLightDir, pixelatedStyle, 0.0F, sun ? spaceBlend : 0.0F, 0.0F
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

    private static void lumispheric$drawPlanets(
        Matrix4f frustumMatrix,
        Matrix4f projectionMatrix,
        float partialTick,
        double cameraY,
        SkyRenderer.FrameAstronomy astro
    ) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !SkyRenderer.shouldRender(level)) {
            return;
        }

        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float skyAngle = level.getTimeOfDay(partialTick);
        float horizon = SkyRenderer.celestialHorizonLevel(cameraY);
        float fadeTop = SkyRenderer.celestialHorizonFadeTop(cameraY);
        float weather = SkyRenderer.weatherStrength(level, cameraY, partialTick);
        float weatherAlpha = lumispheric$weatherOpacity(weather);

        float sunUp = (float) astro.sun().up();
        float nightStrength = SkyRenderer.astronomicalNightStrength(sunUp, altitude);
        float spaceBlend = SkyRenderer.spaceTransition(cameraY);
        float eclipse = astro.eclipseStrength() * (1.0F - weather);

        boolean scoping = mc.player != null && mc.player.isScoping();
        float projZoom = Mth.clamp((projectionMatrix.m11() - 3.5F) / 6.5F, 0.0F, 1.0F);
        float zoomLevel = scoping ? 1.0F : projZoom;
        float telescopeBlend = lumispheric$smoothstep(0.25F, 0.85F, zoomLevel);

        float dayLimit = -5.5F + zoomLevel * 2.2F;
        float nightLimit = 5.8F + zoomLevel * 3.4F;
        float skyMagLimit = Mth.lerp(Math.max(nightStrength, spaceBlend), dayLimit, nightLimit)
            + eclipse * 7.5F
            + spaceBlend * 2.5F;

        float pixelatedStyle = Config.pixelatedStyle ? 1.0F : 0.0F;
        boolean nakedEyeBatch = telescopeBlend < 0.15F;

        Matrix4f matrix = new Matrix4f(frustumMatrix)
            .rotateY((float) Math.toRadians(-90.0))
            .rotateX((float) Math.toRadians(skyAngle * 360.0));

        CelestialModel.PlanetDirection[] planets = CelestialModel.planetDirections(
            astro.astronomicalTime(), astro.latitude()
        );

        // Compute billboard basis, quad, and atmospheric extinction once per visible planet,
        // then emit batched halos and stars sequentially (Tesselator supports one active BufferBuilder at a time).
        int count = planets.length;
        CelestialModel.QuadVertex[][] bodyQuads = new CelestialModel.QuadVertex[count][];
        CelestialModel.QuadVertex[][] glowQuads = new CelestialModel.QuadVertex[count][];
        float[] pReds = new float[count];
        float[] pGreens = new float[count];
        float[] pBlues = new float[count];
        float[] pAlphas = new float[count];
        float[] haloAlphas = new float[count];
        CelestialModel.BillboardBasis[] bases = nakedEyeBatch ? null : new CelestialModel.BillboardBasis[count];
        boolean anyGlow = false;
        boolean anyBody = false;

        for (int i = 0; i < count; i++) {
            CelestialModel.PlanetDirection planetDirection = planets[i];
            CelestialModel.Planet planet = planetDirection.planet();
            float visibility = lumispheric$smoothstep(-0.6F, 1.8F, skyMagLimit - planet.apparentMagnitude());
            if (visibility <= 0.01F) {
                continue;
            }

            CelestialModel.Direction direction = planetDirection.direction();
            CelestialModel.BillboardBasis basis = CelestialModel.billboardBasis(direction);
            float nakedHalfSize = 0.075F + planet.intensity() * 0.085F;
            float telescopeScale = planet.name().equals("Saturn") ? 3.2F : 2.25F;
            float halfSize = nakedEyeBatch
                ? nakedHalfSize
                : Mth.lerp(telescopeBlend, nakedHalfSize, planet.halfSize() * telescopeScale);

            CelestialModel.QuadVertex[] quad = CelestialModel.buildOrientedQuad(
                direction, halfSize, 0.0F, 0.0F, 1.0F, 1.0F, skyAngle, basis
            );
            boolean bodyVisible = lumispheric$hasSkyVisibility(quad, horizon);

            CelestialModel.QuadVertex[] glowQuad = null;
            float haloStrength = 0.0F;
            if (planet.apparentMagnitude() < 0.8F) {
                haloStrength = lumispheric$smoothstep(0.8F, -3.5F, planet.apparentMagnitude())
                    * (1.0F - telescopeBlend * 0.55F);
                if (haloStrength > 0.01F) {
                    CelestialModel.QuadVertex[] candidateGlow = lumispheric$scaleCelestialQuad(
                        quad, Mth.lerp(telescopeBlend, 3.2F, 1.55F), skyAngle
                    );
                    if (lumispheric$hasSkyVisibility(candidateGlow, horizon)) {
                        glowQuad = candidateGlow;
                    }
                }
            }
            if (!bodyVisible && glowQuad == null) {
                continue;
            }

            Vector3f extinction = SkyRenderer.solarTransmission((float) direction.up(), altitude);
            pReds[i] = planet.red() * Mth.lerp(0.55F, 1.0F, extinction.x);
            pGreens[i] = planet.green() * Mth.lerp(0.55F, 1.0F, extinction.y);
            pBlues[i] = planet.blue() * Mth.lerp(0.55F, 1.0F, extinction.z);
            pAlphas[i] = weatherAlpha * visibility;

            if (bodyVisible) {
                bodyQuads[i] = quad;
                if (bases != null) {
                    bases[i] = basis;
                }
                anyBody = true;
            }
            if (glowQuad != null) {
                glowQuads[i] = glowQuad;
                haloAlphas[i] = pAlphas[i] * 0.14F * haloStrength;
                anyGlow = true;
            }
        }

        // 1. Batch all bright planet halos into a single draw call.
        if (anyGlow) {
            BufferBuilder glowBatch = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR
            );
            for (int i = 0; i < count; i++) {
                CelestialModel.QuadVertex[] glowQuad = glowQuads[i];
                if (glowQuad == null) {
                    continue;
                }
                lumispheric$emitColoredCelestialTriangle(
                    glowBatch, matrix, glowQuad[0], glowQuad[1], glowQuad[2],
                    horizon, fadeTop, cameraY, pReds[i], pGreens[i], pBlues[i], haloAlphas[i]
                );
                lumispheric$emitColoredCelestialTriangle(
                    glowBatch, matrix, glowQuad[0], glowQuad[2], glowQuad[3],
                    horizon, fadeTop, cameraY, pReds[i], pGreens[i], pBlues[i], haloAlphas[i]
                );
            }
            MeshData glowMesh = glowBatch.build();
            if (glowMesh != null) {
                try {
                    RenderSystem.enableBlend();
                    RenderSystem.blendFuncSeparate(
                        GlStateManager.SourceFactor.SRC_ALPHA,
                        GlStateManager.DestFactor.ONE,
                        GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ZERO
                    );
                    ShaderInstance glowShader = LumisphericClient.getCelestialShader(
                        1.0F, 1.0F, 1.0F, 1.0F, 1.55F,
                        0.0F, 0.0F, false, 2.0F, 0.0F,
                        UNIT_Z, pixelatedStyle, 0.0F, zoomLevel, 0.0F
                    );
                    RenderSystem.setShader(() -> glowShader);
                    BufferUploader.drawWithShader(glowMesh);
                } finally {
                    VertexBuffer.unbind();
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    RenderSystem.defaultBlendFunc();
                }
            }
        }

        if (!anyBody) {
            return;
        }

        // 2. Batch all naked-eye planet squares into a single draw call.
        if (nakedEyeBatch) {
            BufferBuilder starBatch = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR
            );
            for (int i = 0; i < count; i++) {
                CelestialModel.QuadVertex[] quad = bodyQuads[i];
                if (quad == null) {
                    continue;
                }
                lumispheric$emitColoredCelestialTriangle(
                    starBatch, matrix, quad[0], quad[1], quad[2],
                    horizon, fadeTop, cameraY, pReds[i], pGreens[i], pBlues[i], pAlphas[i]
                );
                lumispheric$emitColoredCelestialTriangle(
                    starBatch, matrix, quad[0], quad[2], quad[3],
                    horizon, fadeTop, cameraY, pReds[i], pGreens[i], pBlues[i], pAlphas[i]
                );
            }
            MeshData mesh = starBatch.build();
            if (mesh != null) {
                try {
                    RenderSystem.enableBlend();
                    RenderSystem.blendFuncSeparate(
                        GlStateManager.SourceFactor.SRC_ALPHA,
                        GlStateManager.DestFactor.ONE,
                        GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ZERO
                    );
                    ShaderInstance shader = LumisphericClient.getCelestialShader(
                        1.0F, 1.0F, 1.0F, 1.0F, 1.0F,
                        0.0F, 0.0F, false, 3.0F, 0.0F,
                        UNIT_Z, pixelatedStyle, 0.0F, zoomLevel, 0.0F
                    );
                    RenderSystem.setShader(() -> shader);
                    BufferUploader.drawWithShader(mesh);
                } finally {
                    VertexBuffer.unbind();
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    RenderSystem.defaultBlendFunc();
                }
            }
            return;
        }

        // 3. Telescopic (Spyglass / Zoom) planet disks with per-planet surface, phase, and rings.
        for (int i = 0; i < count; i++) {
            CelestialModel.QuadVertex[] quad = bodyQuads[i];
            if (quad == null) {
                continue;
            }
            CelestialModel.PlanetDirection planetDirection = planets[i];
            CelestialModel.Planet planet = planetDirection.planet();
            Vector3f lightDir = CelestialModel.lightInBillboardFrame(
                planetDirection.sunDirectionFromPlanet(), bases[i]
            );
            float planetType = lumispheric$planetType(planet.name());

            BufferBuilder builder = Tesselator.getInstance().begin(
                VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR
            );
            lumispheric$emitCelestialTriangle(
                builder, matrix, quad[0], quad[1], quad[2], horizon, fadeTop, cameraY, 1.0F
            );
            lumispheric$emitCelestialTriangle(
                builder, matrix, quad[0], quad[2], quad[3], horizon, fadeTop, cameraY, 1.0F
            );
            MeshData mesh = builder.build();
            if (mesh == null) {
                continue;
            }
            try {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShaderColor(pReds[i], pGreens[i], pBlues[i], pAlphas[i]);
                ShaderInstance shader = LumisphericClient.getCelestialShader(
                    pReds[i], pGreens[i], pBlues[i], pAlphas[i], 1.0F,
                    0.0F, 0.0F, false, 1.0F, planetType,
                    lightDir, pixelatedStyle, 0.0F, zoomLevel, 0.0F
                );
                RenderSystem.setShader(() -> shader);
                BufferUploader.drawWithShader(mesh);
            } finally {
                VertexBuffer.unbind();
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                RenderSystem.defaultBlendFunc();
            }
        }
    }

    private static float lumispheric$planetType(String name) {
        return switch (name) {
            case "Mercury" -> 1.0F;
            case "Venus" -> 2.0F;
            case "Mars" -> 3.0F;
            case "Jupiter" -> 4.0F;
            case "Saturn" -> 5.0F;
            case "Uranus" -> 6.0F;
            case "Neptune" -> 7.0F;
            default -> 0.0F;
        };
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
        boolean moonPass,
        Vector3f bodyLightDir,
        float pixelatedStyle,
        float earthshine,
        float spyglassZoom,
        float solarEclipse
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

        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
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
                red, green, blue, alpha, glowScale, lunarShadowOffsetX, lunarShadowOffsetY, moonPass,
                0.0F, 0.0F, bodyLightDir, pixelatedStyle, earthshine, spyglassZoom, solarEclipse
            );
            RenderSystem.setShader(() -> shader);
            BufferUploader.drawWithShader(mesh);
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
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
        lumispheric$emitColoredCelestialTriangle(
            builder, matrix, first, second, third, horizon, fadeTop, cameraY,
            1.0F, 1.0F, 1.0F, vertexAlphaScale
        );
    }

    private static void lumispheric$emitColoredCelestialTriangle(
        BufferBuilder builder,
        Matrix4f matrix,
        CelestialModel.QuadVertex first,
        CelestialModel.QuadVertex second,
        CelestialModel.QuadVertex third,
        float horizon,
        float fadeTop,
        double cameraY,
        float red,
        float green,
        float blue,
        float vertexAlphaScale
    ) {
        float u0 = first.localUp();
        float u1 = second.localUp();
        float u2 = third.localUp();
        if (u0 <= horizon && u1 <= horizon && u2 <= horizon) {
            return;
        }
        // Fast path: 99% of celestial triangles are completely above the horizon fade band (0 allocations).
        if (u0 >= fadeTop && u1 >= fadeTop && u2 >= fadeTop) {
            lumispheric$emitVertex(builder, matrix, first, cameraY, false, red, green, blue, vertexAlphaScale);
            lumispheric$emitVertex(builder, matrix, second, cameraY, false, red, green, blue, vertexAlphaScale);
            lumispheric$emitVertex(builder, matrix, third, cameraY, false, red, green, blue, vertexAlphaScale);
            return;
        }

        List<CelestialModel.QuadVertex> triangle = List.of(first, second, third);
        List<CelestialModel.QuadVertex> fadeBand = lumispheric$clipPolygon(
            lumispheric$clipPolygon(triangle, horizon, true), fadeTop, false
        );
        lumispheric$emitPolygon(builder, matrix, fadeBand, cameraY, true, red, green, blue, vertexAlphaScale);

        List<CelestialModel.QuadVertex> opaqueRegion = lumispheric$clipPolygon(triangle, fadeTop, true);
        lumispheric$emitPolygon(builder, matrix, opaqueRegion, cameraY, false, red, green, blue, vertexAlphaScale);
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
        float denom = end.localUp() - start.localUp();
        float amount = Math.abs(denom) < 1.0e-6F ? 0.5F : (boundary - start.localUp()) / denom;
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
        float red,
        float green,
        float blue,
        float vertexAlphaScale
    ) {
        if (polygon.size() < 3) {
            return;
        }

        CelestialModel.QuadVertex first = polygon.get(0);
        for (int i = 1; i < polygon.size() - 1; i++) {
            lumispheric$emitVertex(builder, matrix, first, cameraY, fadeBand, red, green, blue, vertexAlphaScale);
            lumispheric$emitVertex(builder, matrix, polygon.get(i), cameraY, fadeBand, red, green, blue, vertexAlphaScale);
            lumispheric$emitVertex(builder, matrix, polygon.get(i + 1), cameraY, fadeBand, red, green, blue, vertexAlphaScale);
        }
    }

    private static void lumispheric$emitVertex(
        BufferBuilder builder,
        Matrix4f matrix,
        CelestialModel.QuadVertex vertex,
        double cameraY,
        boolean fadeBand,
        float red,
        float green,
        float blue,
        float vertexAlphaScale
    ) {
        float opacity = (fadeBand
            ? SkyRenderer.celestialHorizonOpacity(vertex.localUp(), cameraY)
            : 1.0F) * vertexAlphaScale;
        builder.addVertex(matrix, vertex.x(), vertex.y(), vertex.z())
            .setUv(vertex.u(), vertex.v())
            .setColor(red, green, blue, opacity);
    }

    private static float lumispheric$weatherOpacity(float weather) {
        return 1.0F - Math.max(0.0F, Math.min(1.0F, weather)) * 0.65F;
    }

    private static float lumispheric$smoothstep(float edge0, float edge1, float value) {
        float x = Mth.clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
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
            float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
            float weatherFade = SkyRenderer.weatherFade(cameraY);
            float rain = level.getRainLevel(partialTick) * weatherFade;
            float thunder = level.getThunderLevel(partialTick) * weatherFade;
            SkyRenderer.FrameAstronomy astro = SkyRenderer.frameAstronomy(level, partialTick);
            float sunUp = (float) astro.sun().up();
            SkyRenderer.Twilight twilight = SkyRenderer.twilightPalette(sunUp, altitude);
            float nightStrength = SkyRenderer.astronomicalNightStrength(sunUp, altitude);
            ShaderInstance cloudShader = LumisphericClient.getCloudShader(
                rain,
                thunder,
                astro.sun(),
                astro.moon(),
                SkyRenderer.cloudDeckOpacity(cameraY),
                twilight.red(),
                twilight.green(),
                twilight.blue(),
                twilight.strength(),
                astro.moonIllumination(),
                nightStrength,
                sunUp,
                SkyRenderer.biomeDust()
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
    private float lumispheric$useAstronomicalStarBrightnessGate(ClientLevel level, float partialTick) {
        if (!SkyRenderer.shouldRender(level)) {
            return level.getStarBrightness(partialTick);
        }

        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        return SkyRenderer.astronomicalStarBrightness(level, partialTick, cameraY);
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
