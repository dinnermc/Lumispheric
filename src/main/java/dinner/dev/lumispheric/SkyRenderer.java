package dinner.dev.lumispheric;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

public final class SkyRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Lumispheric");
    private static final int AZIMUTH_SEGMENTS = 32;
    private static final int ELEVATION_SEGMENTS = 24;
    private static final int AZIMUTH_STRIDE = AZIMUTH_SEGMENTS + 1;
    private static final int FOG_AZIMUTH_SAMPLES = 16;
    private static final int OVERWORLD_SEA_LEVEL = 63;
    private static final double PLANET_RADIUS = 6_371_000.0;
    private static final double CLOUD_WIND_RADIANS_PER_TICK = 0.000022;
    private static final float PLANET_CURVATURE_START = 1_024.0F - OVERWORLD_SEA_LEVEL;
    private static final float PLANET_CURVATURE_END = 8_000.0F - OVERWORLD_SEA_LEVEL;
    private static final float PLANET_CURVATURE_MAX_DIP = 0.20F;
    private static final float PLANET_HORIZON_TOP_FADE = 0.018F;
    private static final float CELESTIAL_HORIZON_CLEARANCE = 0.035F;
    private static final float PLANET_SURFACE_START = 1_024.0F - OVERWORLD_SEA_LEVEL;
    private static final float PLANET_SURFACE_END = 4_096.0F - OVERWORLD_SEA_LEVEL;
    private static final float CLOUD_DECK_FADE_START = 256.0F - OVERWORLD_SEA_LEVEL;
    private static final float CLOUD_DECK_FADE_END = 768.0F - OVERWORLD_SEA_LEVEL;
    private static final float HIGH_CLOUD_DECK_START = 768.0F - OVERWORLD_SEA_LEVEL;
    private static final float HIGH_CLOUD_DECK_END = 1_024.0F - OVERWORLD_SEA_LEVEL;
    private static final float HIGH_CLOUD_DECK_HEIGHT = 1_536.0F;
    private static final float WEATHER_FADE_START = 896.0F;
    private static final float WEATHER_FADE_END = 1024.0F;
    private static final double PLANET_ROTATION_RADIANS_PER_BLOCK = 1.0 / PLANET_RADIUS;
    private static final double MAX_PLANET_ROTATION_STEP = 512.0;
    private static final float SPACE_TRANSITION_START = 2048.0F - OVERWORLD_SEA_LEVEL;
    private static final float SPACE_TRANSITION_END = 8_000.0F - OVERWORLD_SEA_LEVEL;

    // Sporadic & meteor shower timing windows.
    private static final long SPORADIC_METEOR_WINDOW_TICKS = 900L;
    private static final float SPORADIC_METEOR_DURATION_TICKS = 28.0F;
    private static final long[] SHOWER_SLOT_WINDOWS = { 170L, 211L, 257L, 307L };

    // Eye of the Universe vortex direction in The End sky (towering high overhead toward north-northwest).
    public static final Vector3f END_EYE_DIRECTION = new Vector3f(-0.22F, 0.82F, -0.53F).normalize();
    private static final Vec3 END_FOG_COLOR = new Vec3(0.088, 0.038, 0.152);

    private static VertexBuffer skyBuffer;
    private static VertexBuffer starBuffer;
    private static long cachedStarRevision = Long.MIN_VALUE;
    private static double previousPlanetCameraX = Double.NaN;
    private static double previousPlanetCameraZ = Double.NaN;
    private static double planetRotationAroundX;
    private static double planetRotationAroundZ;
    private static Method irisShaderCheck;
    private static Object irisApi;
    private static boolean irisLookupFailed;
    private static long shaderCheckTime;
    private static boolean shadersActive;
    private static ClientLevel renderedLevel;

    // Reusable 4-slot meteor array (avoids per-frame array allocation).
    private static final MeteorSlot[] ACTIVE_METEORS = new MeteorSlot[4];

    // Per-frame astronomy snapshot cache shared across sky, stars, Sun, Moon, planets, clouds, and HUD.
    private static ClientLevel cachedAstroLevel;
    private static long cachedAstroDayTime = Long.MIN_VALUE;
    private static float cachedAstroPartialTick = Float.NaN;
    private static double cachedAstroLatitude = Double.NaN;
    private static double cachedAstroYearLength = Double.NaN;
    private static FrameAstronomy cachedAstro;

    // Smoothly interpolated biome atmosphere traits + quart-cell biome lookup cache (eliminates per-frame Voronoi lookups).
    private static final BlockPos.MutableBlockPos BIOME_QUERY_POS = new BlockPos.MutableBlockPos();
    private static ClientLevel cachedBiomeLevel;
    private static int cachedBiomeQuartX = Integer.MIN_VALUE;
    private static int cachedBiomeQuartY = Integer.MIN_VALUE;
    private static int cachedBiomeQuartZ = Integer.MIN_VALUE;
    private static float cachedBiomeTemperature = 0.8F;
    private static boolean cachedBiomeHasRain = true;
    private static float smoothedBiomeDust;
    private static float smoothedBiomeClearness;
    private static boolean biomeTraitsInitialized;
    private static long lastBiomeUpdateNanos;

    // Fog color cache.
    private static ClientLevel cachedFogLevel;
    private static long cachedFogDayTime = Long.MIN_VALUE;
    private static int cachedFogAltitude = Integer.MIN_VALUE;
    private static int cachedFogWeather = Integer.MIN_VALUE;
    private static double cachedFogLatitude = Double.NaN;
    private static double cachedFogYearLength = Double.NaN;
    private static float cachedFogSunsetIntensity = Float.NaN;
    private static float cachedFogMieIntensity = Float.NaN;
    private static int cachedFogBiomeBucket = Integer.MIN_VALUE;
    private static Vec3 cachedFogColor;

    // Per-frame Overworld lunar night & solar eclipse lightmap cache (shared across all 256 lightmap texels).
    private static ClientLevel cachedNightLightLevel;
    private static long cachedNightLightDayTime = Long.MIN_VALUE;
    private static float cachedNightLightPartialTick = Float.NaN;
    private static int cachedNightLightFlash = Integer.MIN_VALUE;
    private static int cachedNightLightRainBucket = Integer.MIN_VALUE;
    private static double cachedNightLightLatitude = Double.NaN;
    private static double cachedNightLightYearLength = Double.NaN;
    private static boolean cachedNightLightEnabled = true;
    private static float cachedNightLightWeight;
    private static float cachedNightSkyScaleR = 1.0F;
    private static float cachedNightSkyScaleG = 1.0F;
    private static float cachedNightSkyScaleB = 1.0F;

    private SkyRenderer() {}

    private static void ensureLevel(ClientLevel level) {
        if (renderedLevel == level) {
            return;
        }
        closeBuffers();
        renderedLevel = level;
    }

    public static void closeBuffers() {
        if (skyBuffer != null) {
            skyBuffer.close();
            skyBuffer = null;
        }
        if (starBuffer != null) {
            starBuffer.close();
            starBuffer = null;
        }
        renderedLevel = null;
        previousPlanetCameraX = Double.NaN;
        previousPlanetCameraZ = Double.NaN;
        planetRotationAroundX = 0.0;
        planetRotationAroundZ = 0.0;
        cachedStarRevision = Long.MIN_VALUE;
        cachedAstroLevel = null;
        cachedAstro = null;
        cachedNightLightLevel = null;
        cachedNightLightWeight = 0.0F;
        cachedBiomeLevel = null;
        cachedBiomeQuartX = Integer.MIN_VALUE;
        biomeTraitsInitialized = false;
        smoothedBiomeDust = 0.0F;
        smoothedBiomeClearness = 0.0F;
        lastBiomeUpdateNanos = 0L;
        cachedFogLevel = null;
        cachedFogDayTime = Long.MIN_VALUE;
        cachedFogAltitude = Integer.MIN_VALUE;
        cachedFogWeather = Integer.MIN_VALUE;
        cachedFogLatitude = Double.NaN;
        cachedFogYearLength = Double.NaN;
        cachedFogSunsetIntensity = Float.NaN;
        cachedFogMieIntensity = Float.NaN;
        cachedFogBiomeBucket = Integer.MIN_VALUE;
        cachedFogColor = null;
    }

    public static boolean shouldRender(ClientLevel level) {
        return Config.enabled && level != null
            && level.dimension() == Level.OVERWORLD
            && level.effects().skyType() == DimensionSpecialEffects.SkyType.NORMAL
            && !areShadersActive();
    }

    public static boolean shouldRenderEnd(ClientLevel level) {
        return Config.enabled && Config.endSkyEnabled && level != null
            && level.dimension() == Level.END
            && level.effects().skyType() == DimensionSpecialEffects.SkyType.END
            && !areShadersActive();
    }

    public static Vec3 endFogColor() {
        return END_FOG_COLOR;
    }

    /**
     * Modulates Overworld nighttime sky-light ambience based on real lunar presence, phase,
     * lunar eclipse umbra, and rain overcast, while keeping block light (torches, lanterns, fire)
     * and deep caves completely untouched.
     */
    public static void applyOverworldNightLightmap(
        ClientLevel level,
        float partialTick,
        float blockLightRedFlicker,
        int blockLightLevel,
        int skyLightLevel,
        Vector3f colors
    ) {
        if (skyLightLevel <= 0 || !shouldRender(level)) {
            return;
        }
        updateNightLightmapCache(level, partialTick);
        if (cachedNightLightWeight <= 0.001F) {
            return;
        }

        // Smooth transition at cave entrances (pixelY 0..6) so underground lighting is unaffected.
        float skyExposure = smoothstep(0.0F, 6.0F, (float) skyLightLevel);

        // Separate pure block light from sky light so torches/lanterns stay 100% warm and bright.
        float blockR = net.minecraft.client.renderer.LightTexture.getBrightness(level.dimensionType(), blockLightLevel)
            * blockLightRedFlicker;
        float blockG = blockR * ((blockR * 0.6F + 0.4F) * 0.6F + 0.4F);
        float blockB = blockR * (blockR * blockR * 0.6F + 0.4F);

        float skyR = Math.max(0.0F, colors.x - blockR);
        float skyG = Math.max(0.0F, colors.y - blockG);
        float skyB = Math.max(0.0F, colors.z - blockB);

        float scaleR = mix(1.0F, cachedNightSkyScaleR, skyExposure);
        float scaleG = mix(1.0F, cachedNightSkyScaleG, skyExposure);
        float scaleB = mix(1.0F, cachedNightSkyScaleB, skyExposure);

        colors.set(
            Math.min(1.0F, blockR + skyR * scaleR),
            Math.min(1.0F, blockG + skyG * scaleG),
            Math.min(1.0F, blockB + skyB * scaleB)
        );
    }

    private static void updateNightLightmapCache(ClientLevel level, float partialTick) {
        long dayTime = level.getDayTime();
        int flashTime = level.getSkyFlashTime();
        float rain = Math.max(0.0F, Math.min(1.0F, level.getRainLevel(partialTick)));
        int rainBucket = Math.round(rain * 100.0F);
        double latitude = Config.latitude;
        double yearLength = Config.yearLengthDays;
        boolean lunarEnabled = Config.lunarNightDarkness;

        if (cachedNightLightLevel == level
            && cachedNightLightDayTime == dayTime
            && Float.compare(cachedNightLightPartialTick, partialTick) == 0
            && cachedNightLightFlash == flashTime
            && cachedNightLightRainBucket == rainBucket
            && Double.compare(cachedNightLightLatitude, latitude) == 0
            && Double.compare(cachedNightLightYearLength, yearLength) == 0
            && cachedNightLightEnabled == lunarEnabled) {
            return;
        }
        cachedNightLightLevel = level;
        cachedNightLightDayTime = dayTime;
        cachedNightLightPartialTick = partialTick;
        cachedNightLightFlash = flashTime;
        cachedNightLightRainBucket = rainBucket;
        cachedNightLightLatitude = latitude;
        cachedNightLightYearLength = yearLength;
        cachedNightLightEnabled = lunarEnabled;

        // Never darken lightning flashes during thunderstorms.
        if (flashTime > 0) {
            cachedNightLightWeight = 0.0F;
            return;
        }

        FrameAstronomy astro = frameAstronomy(level, partialTick);
        float sunUp = (float) astro.sun().up();
        float eclipse = astro.eclipseStrength() * (1.0F - rain * 0.45F);
        float nightWeight = lunarEnabled ? (1.0F - smoothstep(-0.14F, 0.03F, sunUp)) : 0.0F;

        if (nightWeight <= 0.001F && eclipse <= 0.005F) {
            cachedNightLightWeight = 0.0F;
            return;
        }

        float scaleR = 1.0F;
        float scaleG = 1.0F;
        float scaleB = 1.0F;

        if (nightWeight > 0.001F) {
            float moonUp = (float) astro.moon().up();
            float moonAbove = smoothstep(-0.04F, 0.20F, moonUp);
            float phase = Math.max(0.0F, Math.min(1.0F, astro.moonIllumination()));
            // Real opposition surge: quarter/crescent moons are noticeably dimmer than a full moon.
            float phaseBrightness = (float) Math.pow(phase, 1.35);
            float lunarUmbra = astro.lunarEclipse().umbra();
            float bloodMoon = lunarUmbra * moonAbove * (1.0F - rain * 0.7F);

            float moonlight = moonAbove * phaseBrightness * (1.0F - 0.85F * lunarUmbra) * (1.0F - 0.45F * rain);

            // Full moon (~0.96) keeps nights silvery and clear; moonless / new moon (~0.38) deepens
            // the ambience atmospherically while remaining clearly playable.
            float baseScale = mix(0.38F, 0.96F, moonlight);
            float tintR = mix(0.90F, 0.94F, moonlight);
            float tintG = mix(0.94F, 0.98F, moonlight);
            float tintB = mix(1.08F, 1.06F, moonlight);

            if (bloodMoon > 0.05F) {
                tintR = mix(tintR, 1.18F, bloodMoon * 0.65F);
                tintG = mix(tintG, 0.72F, bloodMoon * 0.65F);
                tintB = mix(tintB, 0.70F, bloodMoon * 0.65F);
            }

            scaleR = mix(1.0F, baseScale * tintR, nightWeight);
            scaleG = mix(1.0F, baseScale * tintG, nightWeight);
            scaleB = mix(1.0F, baseScale * tintB, nightWeight);
        }

        // Daytime solar eclipse smoothly plunges Overworld terrain into eerie deep twilight
        // while keeping torches, lanterns, and campfires warm and bright.
        if (eclipse > 0.005F) {
            float eclipseCurve = (float) Math.pow(eclipse, 1.15);
            float eclipseDim = mix(1.0F, 0.24F, eclipseCurve);
            scaleR *= eclipseDim * mix(1.0F, 0.94F, eclipseCurve);
            scaleG *= eclipseDim * mix(1.0F, 0.92F, eclipseCurve);
            scaleB *= eclipseDim * mix(1.0F, 1.08F, eclipseCurve);
        }

        cachedNightLightWeight = Math.max(nightWeight, eclipse);
        cachedNightSkyScaleR = scaleR;
        cachedNightSkyScaleG = scaleG;
        cachedNightSkyScaleB = scaleB;
    }

    public static FrameAstronomy frameAstronomy(ClientLevel level, float partialTick) {
        long dayTime = level.getDayTime();
        double latitude = CelestialModel.latitudeRadians();
        double yearLength = CelestialModel.configuredDaysPerYear();
        if (cachedAstro != null
            && cachedAstroLevel == level
            && cachedAstroDayTime == dayTime
            && Float.compare(cachedAstroPartialTick, partialTick) == 0
            && Double.compare(cachedAstroLatitude, latitude) == 0
            && Double.compare(cachedAstroYearLength, yearLength) == 0) {
            return cachedAstro;
        }
        double astronomicalTime = dayTime + partialTick;
        CelestialModel.Direction sun = CelestialModel.sunDirection(astronomicalTime, latitude);
        CelestialModel.Direction moon = CelestialModel.moonDirection(astronomicalTime, latitude);
        CelestialModel.Direction galacticNorth = CelestialModel.galacticNorthDirection(astronomicalTime, latitude);
        CelestialModel.Direction galacticCenter = CelestialModel.galacticCenterDirection(astronomicalTime, latitude);
        float moonIllum = CelestialModel.moonIllumination(astronomicalTime);
        float eclipse = CelestialModel.eclipseStrength(astronomicalTime, latitude);
        CelestialModel.LunarEclipse lunarEclipse = CelestialModel.lunarEclipse(astronomicalTime, sun, moon);
        CelestialModel.MeteorShower shower = CelestialModel.activeMeteorShower(astronomicalTime, latitude);
        cachedAstro = new FrameAstronomy(
            astronomicalTime, latitude, sun, moon, galacticNorth, galacticCenter,
            moonIllum, eclipse, lunarEclipse, shower
        );
        cachedAstroLevel = level;
        cachedAstroDayTime = dayTime;
        cachedAstroPartialTick = partialTick;
        cachedAstroLatitude = latitude;
        cachedAstroYearLength = yearLength;
        return cachedAstro;
    }

    public static void render(Matrix4f pose, Matrix4f projection, float partialTick, ClientLevel level) {
        ensureLevel(level);
        if (skyBuffer == null) {
            buildStaticSkySphere();
        }

        Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double cameraY = cameraPosition.y;
        float rain = weatherStrength(level, cameraY, partialTick);
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        updatePlanetRotation(cameraPosition.x, cameraPosition.z);
        updateBiomeTraits(level, cameraPosition, altitude);

        FrameAstronomy astro = frameAstronomy(level, partialTick);
        float sunUp = (float) astro.sun().up();
        float eclipse = astro.eclipseStrength() * (1.0F - rain);
        float horizonDip = horizonDip(altitude);
        float geomDip = geometricHorizonDip(altitude);
        float spaceBlend = spaceTransitionStrength(altitude);
        float planetBlend = planetSurfaceBlend(altitude);
        float nightStrength = astronomicalNightStrength(sunUp, altitude);
        Twilight twilight = twilightPalette(sunUp, altitude);
        float aurora = auroraStrength(level, nightStrength, rain, spaceBlend, cameraPosition);
        float auroraHemisphere = Config.latitude >= 0.0 ? 1.0F : -1.0F;
        updateActiveMeteors(level, partialTick, rain, nightStrength, spaceBlend, astro.activeShower());
        float gameTimeSeconds = (float) (((level.getGameTime() % 72_000L) + partialTick) / 20.0);
        double cloudWindAngle = Math.IEEEremainder(
            (level.getGameTime() + (double) partialTick) * CLOUD_WIND_RADIANS_PER_TICK,
            Math.PI * 2.0
        );

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        ShaderInstance skyShader = LumisphericClient.getPlanetShader(
            astro.sun(),
            astro.moon(),
            sunUp,
            astro.moonIllumination(),
            rain,
            eclipse,
            horizonDip,
            geomDip,
            altitude,
            spaceBlend,
            Config.sunsetIntensity,
            Config.mieIntensity,
            smoothedBiomeDust,
            smoothedBiomeClearness,
            twilight.red(),
            twilight.green(),
            twilight.blue(),
            twilight.strength(),
            twilight.blueHour(),
            aurora,
            auroraHemisphere,
            Config.pixelatedStyle ? 1.0F : 0.0F,
            ACTIVE_METEORS,
            gameTimeSeconds,
            (float) cloudWindAngle,
            (float) apparentPlanetRadius(altitude),
            planetBlend,
            (float) planetRotationAroundZ,
            (float) planetRotationAroundX
        );
        skyBuffer.bind();
        skyBuffer.drawWithShader(pose, projection, skyShader);

        float milkyWayStrength = milkyWayStrength(astro, altitude, rain);
        if (milkyWayStrength > 0.001F) {
            ShaderInstance milkyWayShader = LumisphericClient.getMilkyWayShader(
                astro.galacticNorth(),
                astro.galacticCenter(),
                milkyWayStrength,
                geomDip,
                spaceBlend,
                Config.pixelatedStyle ? 1.0F : 0.0F,
                planetBlend
            );
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
            );
            skyBuffer.bind();
            skyBuffer.drawWithShader(pose, projection, milkyWayShader);
            RenderSystem.defaultBlendFunc();
        }
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    public static void renderEnd(Matrix4f pose, Matrix4f projection, float partialTick, ClientLevel level) {
        ensureLevel(level);
        if (skyBuffer == null) {
            buildStaticSkySphere();
        }

        float gameTimeSeconds = (float) (((level.getGameTime() % 144_000L) + partialTick) / 20.0);
        ShaderInstance endShader = LumisphericClient.getEndSkyShader(
            END_EYE_DIRECTION,
            gameTimeSeconds,
            Config.pixelatedStyle ? 1.0F : 0.0F
        );

        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            skyBuffer.bind();
            skyBuffer.drawWithShader(pose, projection, endShader);
        } finally {
            VertexBuffer.unbind();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
        }
    }

    private static void buildStaticSkySphere() {
        skyBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        int vertexCount = (ELEVATION_SEGMENTS + 1) * AZIMUTH_STRIDE;
        float[] positions = new float[vertexCount * 3];
        for (int elevation = 0; elevation <= ELEVATION_SEGMENTS; elevation++) {
            float angle = -(float) Math.PI * 0.5F + (float) Math.PI * elevation / ELEVATION_SEGMENTS;
            float horizontal = (float) Math.cos(angle);
            float y = (float) Math.sin(angle) * 128.0F;
            for (int azimuth = 0; azimuth <= AZIMUTH_SEGMENTS; azimuth++) {
                float azimuthAngle = (float) (Math.PI * 2.0) * azimuth / AZIMUTH_SEGMENTS;
                int index = (elevation * AZIMUTH_STRIDE + azimuth) * 3;
                positions[index] = (float) Math.cos(azimuthAngle) * horizontal * 128.0F;
                positions[index + 1] = y;
                positions[index + 2] = (float) Math.sin(azimuthAngle) * horizontal * 128.0F;
            }
        }

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int elevation = 0; elevation < ELEVATION_SEGMENTS; elevation++) {
            for (int azimuth = 0; azimuth < AZIMUTH_SEGMENTS; azimuth++) {
                int lowerLeft = elevation * AZIMUTH_STRIDE + azimuth;
                int lowerRight = lowerLeft + 1;
                int upperLeft = lowerLeft + AZIMUTH_STRIDE;
                int upperRight = upperLeft + 1;
                addWhiteVertex(builder, positions, lowerLeft);
                addWhiteVertex(builder, positions, lowerRight);
                addWhiteVertex(builder, positions, upperRight);
                addWhiteVertex(builder, positions, lowerLeft);
                addWhiteVertex(builder, positions, upperRight);
                addWhiteVertex(builder, positions, upperLeft);
            }
        }
        skyBuffer.bind();
        skyBuffer.upload(builder.buildOrThrow());
        VertexBuffer.unbind();
    }

    private static void addWhiteVertex(BufferBuilder builder, float[] positions, int index) {
        int positionIndex = index * 3;
        builder.addVertex(
            positions[positionIndex],
            positions[positionIndex + 1],
            positions[positionIndex + 2]
        ).setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void updateBiomeTraits(ClientLevel level, Vec3 cameraPosition, float altitude) {
        int blockX = (int) Math.floor(cameraPosition.x);
        int blockY = (int) Math.floor(cameraPosition.y);
        int blockZ = (int) Math.floor(cameraPosition.z);
        int quartX = blockX >> 2;
        int quartY = blockY >> 2;
        int quartZ = blockZ >> 2;
        if (cachedBiomeLevel != level
            || cachedBiomeQuartX != quartX
            || cachedBiomeQuartY != quartY
            || cachedBiomeQuartZ != quartZ) {
            BIOME_QUERY_POS.set(blockX, blockY, blockZ);
            Biome biome = level.getBiome(BIOME_QUERY_POS).value();
            cachedBiomeTemperature = biome.getBaseTemperature();
            cachedBiomeHasRain = biome.hasPrecipitation();
            cachedBiomeLevel = level;
            cachedBiomeQuartX = quartX;
            cachedBiomeQuartY = quartY;
            cachedBiomeQuartZ = quartZ;
        }

        float temperature = cachedBiomeTemperature;
        boolean hasRain = cachedBiomeHasRain;
        float targetDust = !hasRain && temperature >= 0.95F
            ? smoothstep(0.95F, 1.85F, temperature)
            : 0.0F;
        float coldClear = 1.0F - smoothstep(-0.15F, 0.35F, temperature);
        float highClear = smoothstep(80.0F, 220.0F, altitude);
        float targetClearness = Math.max(coldClear, highClear * 0.85F);

        long now = System.nanoTime();
        if (!biomeTraitsInitialized) {
            smoothedBiomeDust = targetDust;
            smoothedBiomeClearness = targetClearness;
            biomeTraitsInitialized = true;
            lastBiomeUpdateNanos = now;
            return;
        }
        float dtSeconds = Math.max(0.0F, Math.min(0.25F, (now - lastBiomeUpdateNanos) * 1.0e-9F));
        lastBiomeUpdateNanos = now;
        float blend = 1.0F - (float) Math.exp(-dtSeconds * 1.8);
        smoothedBiomeDust += (targetDust - smoothedBiomeDust) * blend;
        smoothedBiomeClearness += (targetClearness - smoothedBiomeClearness) * blend;
    }

    public static float biomeDust() {
        return smoothedBiomeDust;
    }

    public static float biomeClearness() {
        return smoothedBiomeClearness;
    }

    public static Vector3f solarTransmission(float elevationSin, float altitude) {
        float spaceBlend = spaceTransitionStrength(altitude);
        float opticalDepth = solarAirMass(elevationSin) - 1.0F;
        float red = (float) Math.exp(-0.045F * opticalDepth);
        float green = (float) Math.exp(-0.145F * opticalDepth);
        float blue = (float) Math.exp(-0.34F * opticalDepth);
        float r = 1.0F;
        float g = mix(Math.min(1.0F, green / Math.max(red, 0.05F)), 1.0F, spaceBlend);
        float b = mix(Math.min(1.0F, blue / Math.max(red, 0.05F)), 1.0F, spaceBlend);
        return new Vector3f(r, g, b);
    }

    public static float astronomicalNightStrength(float sunUp, float altitude) {
        float sunElevation = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, sunUp)));
        float geometricDip = geometricHorizonDip(altitude);
        float apparentSunElevation = sunElevation + geometricDip;
        float altitudeBlend = smoothstep(0.0F, 256.0F, altitude);
        float groundNight = 1.0F - smoothstep(-0.28F, 0.04F, sunElevation);
        float highAltNight = 1.0F - smoothstep(-0.32F, 0.02F, apparentSunElevation);
        return mix(groundNight, highAltNight, altitudeBlend);
    }

    public static Twilight twilightPalette(float sunY, float altitude) {
        float opticalDepth = solarAirMass(sunY) - 1.0F;
        float redTransmission = (float) Math.exp(-0.035F * opticalDepth);
        float greenTransmission = (float) Math.exp(-0.135F * opticalDepth);
        float blueTransmission = (float) Math.exp(-0.42F * opticalDepth);
        float greenRatio = (float) Math.sqrt(greenTransmission / redTransmission);
        float blueRatio = (float) Math.sqrt(blueTransmission / redTransmission);
        float sunElevation = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, sunY)));
        float apparentSunElevation = sunElevation + geometricHorizonDip(altitude);
        float blueHour = smoothstep(0.0F, 0.13F, -apparentSunElevation);
        float warmRed = mix(1.00F, 0.84F, blueHour);
        float warmGreen = mix(0.24F + 0.42F * greenRatio, 0.30F, blueHour);
        float warmBlue = mix(0.05F + 0.24F * blueRatio, 0.44F, blueHour);
        float altitudeBlend = smoothstep(0.0F, 256.0F, altitude);
        return new Twilight(
            twilightAmount(apparentSunElevation, altitudeBlend) * Config.sunsetIntensity,
            warmRed,
            warmGreen,
            warmBlue,
            blueHour
        );
    }

    public static Vec3 horizonColor(ClientLevel level, float partialTick, float azimuth, double cameraY) {
        FrameAstronomy astro = frameAstronomy(level, partialTick);
        float rain = weatherStrength(level, cameraY, partialTick);
        float eclipse = astro.eclipseStrength() * (1.0F - rain);
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        Twilight twilight = twilightPalette((float) astro.sun().up(), altitude);
        float horizonDip = horizonDip(altitude);
        return sampleHorizonAtmosphere(
            -horizonDip, azimuth, rain, twilight, astro.sun(), astro.moon(), horizonDip,
            astro.moonIllumination(), eclipse, altitude
        );
    }

    public static Vec3 horizonFogColor(ClientLevel level, double cameraY, Vec3 lookDirection) {
        float fogPartialTick = 0.5F;
        long dayTime = level.getDayTime() / 10L;
        int altitude = (int) Math.floor(cameraY - OVERWORLD_SEA_LEVEL);
        int weather = Float.floatToIntBits(
            Math.max(level.getRainLevel(fogPartialTick), level.getThunderLevel(fogPartialTick))
        );
        int biomeBucket = (Math.round(smoothedBiomeDust * 20.0F) << 8)
            ^ Math.round(smoothedBiomeClearness * 20.0F);
        if (cachedFogColor == null
            || cachedFogLevel != level
            || cachedFogDayTime != dayTime
            || cachedFogAltitude != altitude
            || cachedFogWeather != weather
            || Double.compare(cachedFogLatitude, Config.latitude) != 0
            || Double.compare(cachedFogYearLength, Config.yearLengthDays) != 0
            || Float.compare(cachedFogSunsetIntensity, Config.sunsetIntensity) != 0
            || Float.compare(cachedFogMieIntensity, Config.mieIntensity) != 0
            || cachedFogBiomeBucket != biomeBucket) {
            double red = 0.0;
            double green = 0.0;
            double blue = 0.0;
            for (int sample = 0; sample < FOG_AZIMUTH_SAMPLES; sample++) {
                float azimuth = (float) (Math.PI * 2.0 * sample / FOG_AZIMUTH_SAMPLES);
                Vec3 color = horizonColor(level, fogPartialTick, azimuth, cameraY);
                red += color.x;
                green += color.y;
                blue += color.z;
            }
            double normalization = 1.0 / FOG_AZIMUTH_SAMPLES;
            cachedFogColor = new Vec3(red * normalization, green * normalization, blue * normalization);
            cachedFogLevel = level;
            cachedFogDayTime = dayTime;
            cachedFogAltitude = altitude;
            cachedFogWeather = weather;
            cachedFogLatitude = Config.latitude;
            cachedFogYearLength = Config.yearLengthDays;
            cachedFogSunsetIntensity = Config.sunsetIntensity;
            cachedFogMieIntensity = Config.mieIntensity;
            cachedFogBiomeBucket = biomeBucket;
        }

        if (lookDirection != null && (lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z) > 1.0e-4) {
            FrameAstronomy astro = frameAstronomy(level, fogPartialTick);
            float alt = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
            float twilightWeight = twilightPalette((float) astro.sun().up(), alt).strength() * 0.18F;
            if (twilightWeight > 0.005F) {
                float lookAzimuth = (float) Math.atan2(lookDirection.z, lookDirection.x);
                Vec3 directional = horizonColor(level, fogPartialTick, lookAzimuth, cameraY);
                return new Vec3(
                    mix((float) cachedFogColor.x, (float) directional.x, twilightWeight),
                    mix((float) cachedFogColor.y, (float) directional.y, twilightWeight),
                    mix((float) cachedFogColor.z, (float) directional.z, twilightWeight)
                );
            }
        }
        return cachedFogColor;
    }

    private static Vec3 sampleHorizonAtmosphere(
        float elevation,
        float azimuth,
        float rain,
        Twilight twilight,
        CelestialModel.Direction sun,
        CelestialModel.Direction moon,
        float horizonDip,
        float moonIllumination,
        float eclipse,
        float altitude
    ) {
        float sunX = (float) sun.east();
        float sunY = (float) sun.up();
        float sunZ = (float) sun.south();
        float moonX = (float) moon.east();
        float moonY = (float) moon.up();
        float moonZ = (float) moon.south();

        float spaceBlend = spaceTransitionStrength(altitude);
        float horizonDrop = spaceBlend * 0.10F;
        float skyElevation = elevation + horizonDip + horizonDrop;
        float geometricDip = geometricHorizonDip(altitude);
        float horizonSharpness = mix(
            2.7F + smoothedBiomeClearness * 0.45F - smoothedBiomeDust * 0.35F,
            12.0F,
            spaceBlend
        );
        float horizon = (float) Math.exp(-Math.abs(skyElevation) * horizonSharpness);

        float zenithR = mix(mix(0.13F, 0.07F, smoothedBiomeClearness * 0.45F), 0.19F, smoothedBiomeDust * 0.22F);
        float zenithG = mix(mix(0.27F, 0.19F, smoothedBiomeClearness * 0.45F), 0.33F, smoothedBiomeDust * 0.22F);
        float zenithB = mix(mix(0.66F, 0.56F, smoothedBiomeClearness * 0.45F), 0.63F, smoothedBiomeDust * 0.22F);

        float horizonRed = mix(mix(0.60F, 0.12F, spaceBlend), 0.75F, smoothedBiomeDust * 0.22F * (1.0F - spaceBlend));
        float horizonGreen = mix(mix(0.76F, 0.30F, spaceBlend), 0.73F, smoothedBiomeDust * 0.22F * (1.0F - spaceBlend));
        float horizonBlue = mix(mix(0.92F, 0.68F, spaceBlend), 0.67F, smoothedBiomeDust * 0.22F * (1.0F - spaceBlend));

        float red = mix(zenithR, horizonRed, horizon);
        float green = mix(zenithG, horizonGreen, horizon);
        float blue = mix(zenithB, horizonBlue, horizon);

        float horizontal = (float) Math.cos(elevation);
        float viewX = (float) Math.cos(azimuth) * horizontal;
        float viewY = (float) Math.sin(elevation);
        float viewZ = (float) Math.sin(azimuth) * horizontal;
        float sunDot = Math.max(-1.0F, Math.min(1.0F, viewX * sunX + viewY * sunY + viewZ * sunZ));
        float moonDot = Math.max(-1.0F, Math.min(1.0F, viewX * moonX + viewY * moonY + viewZ * moonZ));
        float nightBlend = astronomicalNightStrength(sunY, altitude);
        float horizonNight = mix(nightBlend, 1.0F - smoothstep(-0.16F, 0.22F, sunDot), spaceBlend);

        red = mix(red, mix(0.004F, 0.015F, horizon), horizonNight);
        green = mix(green, mix(0.007F, 0.024F, horizon), horizonNight);
        blue = mix(blue, mix(0.017F, 0.042F, horizon), horizonNight);

        if (spaceBlend > 0.001F) {
            float atmosphereHeight = Math.max(0.0F, skyElevation) / 0.045F;
            float upperAtmosphere = spaceBlend * (float) Math.exp(-atmosphereHeight * atmosphereHeight);
            red = mix(red, mix(0.06F, 0.005F, horizonNight), upperAtmosphere * 0.85F);
            green = mix(green, mix(0.17F, 0.008F, horizonNight), upperAtmosphere * 0.85F);
            blue = mix(blue, mix(0.48F, 0.022F, horizonNight), upperAtmosphere * 0.85F);
        }

        float moonAbove = smoothstep(-0.08F, 0.14F, moonY);
        float lunarFactor = moonIllumination * horizonNight * (1.0F - rain * 0.85F) * (1.0F - spaceBlend * 0.75F);
        if (lunarFactor > 0.001F && moonAbove > 0.001F) {
            float moonHalo = (float) Math.pow(Math.max(moonDot, 0.0F), 10.0) * 0.036F;
            float moonAmbient = (0.004F + 0.006F * horizon) * moonAbove;
            float lunarTotal = (moonHalo * moonAbove + moonAmbient) * lunarFactor;
            red += 0.42F * lunarTotal;
            green += 0.62F * lunarTotal;
            blue += 0.96F * lunarTotal;
        }

        float rayleighPhase = 0.75F * (1.0F + sunDot * sunDot);
        float rayleighScatter = (1.0F - horizonNight) * (1.0F - rain) * rayleighPhase * (0.35F + 0.65F * horizon);
        red += rayleighScatter * 0.012F;
        green += rayleighScatter * 0.036F;
        blue += rayleighScatter * 0.090F;

        float twilightLight = twilight.strength() * (1.0F - rain * 0.75F);
        if (twilightLight > 0.001F) {
            float sunAngle = (float) Math.acos(sunDot);
            float sunwardHorizon = (float) Math.exp(-(sunAngle * sunAngle) / 0.88F);
            float tighterSunward = (float) Math.exp(-(sunAngle * sunAngle) / 0.30F);
            float lowHorizonBand = (float) Math.exp(-Math.abs(skyElevation) * mix(5.2F, 13.0F, spaceBlend));
            float wideHorizonBand = (float) Math.exp(-Math.abs(skyElevation) * mix(2.4F, 9.0F, spaceBlend));

            float flankR = mix(0.88F, 0.58F, twilight.blueHour());
            float flankG = mix(0.46F, 0.32F, twilight.blueHour());
            float flankB = mix(0.48F, 0.56F, twilight.blueHour());
            float solR = mix(1.00F, 0.95F, twilight.blueHour() * 0.72F);
            float solG = mix(0.54F, 0.30F, twilight.blueHour() * 0.72F);
            float solB = mix(0.18F, 0.22F, twilight.blueHour() * 0.72F);
            float sweepMix = 0.22F + 0.78F * sunwardHorizon;
            float sweepWeight = Math.min(0.92F, wideHorizonBand * twilightLight * (0.48F + 0.52F * sunwardHorizon) * 0.84F);
            red = mix(red, mix(flankR, mix(twilight.red(), solR, 0.55F), sweepMix), sweepWeight);
            green = mix(green, mix(flankG, mix(twilight.green(), solG, 0.55F), sweepMix), sweepWeight);
            blue = mix(blue, mix(flankB, mix(twilight.blue(), solB, 0.55F), sweepMix), sweepWeight);

            float blazingBand = Math.min(0.90F, lowHorizonBand * tighterSunward * twilightLight * 0.85F);
            red = mix(red, mix(1.00F, 0.98F, twilight.blueHour() * 0.85F), blazingBand);
            green = mix(green, mix(0.76F, 0.42F, twilight.blueHour() * 0.85F), blazingBand);
            blue = mix(blue, mix(0.32F, 0.16F, twilight.blueHour() * 0.85F), blazingBand);

            float sunElevation = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, sunY)));
            float apparentSunElev = sunElevation + geometricDip;
            float antisolarDist = (float) Math.PI - sunAngle;
            float antisolarMask = (float) Math.exp(-(antisolarDist * antisolarDist) / 0.62F) * twilightLight * (1.0F - spaceBlend);
            float shadowTop = Math.max(0.025F, Math.min(0.24F, 0.025F + Math.max(-apparentSunElev, 0.0F) * 1.55F));
            float earthShadow = (1.0F - smoothstep(shadowTop - 0.042F, shadowTop + 0.042F, skyElevation))
                * smoothstep(-0.18F, 0.01F, skyElevation)
                * antisolarMask * smoothstep(-0.03F, 0.12F, -apparentSunElev + 0.04F) * 0.66F;
            red = mix(red, 0.06F, earthShadow);
            green = mix(green, 0.09F, earthShadow);
            blue = mix(blue, 0.21F, earthShadow);

            float wideGlow = (float) Math.exp(-(sunAngle * sunAngle) / 0.32F) * twilightLight;
            float coreGlow = (float) Math.exp(-(sunAngle * sunAngle) / 0.065F) * twilightLight;
            red += mix(0.42F, 0.32F, twilight.blueHour()) * wideGlow * 0.65F
                + mix(0.58F, 0.42F, twilight.blueHour()) * coreGlow * 0.85F;
            green += mix(0.20F, 0.10F, twilight.blueHour()) * wideGlow * 0.65F
                + mix(0.40F, 0.18F, twilight.blueHour()) * coreGlow * 0.85F;
            blue += mix(0.06F, 0.08F, twilight.blueHour()) * wideGlow * 0.65F
                + mix(0.16F, 0.08F, twilight.blueHour()) * coreGlow * 0.85F;
        }

        float mieAsymmetry = mix(0.79F, 0.73F, smoothedBiomeDust * 0.4F);
        float mieDenom = 1.0F + mieAsymmetry * mieAsymmetry - 2.0F * mieAsymmetry * sunDot;
        float mieRatio = (1.0F - mieAsymmetry) * (1.0F - mieAsymmetry) / Math.max(mieDenom, 1.0e-4F);
        float miePhase = mieRatio * (float) Math.sqrt(mieRatio);
        float mieStrength = Math.min(0.88F, miePhase * Config.mieIntensity * (1.0F + smoothedBiomeDust * 0.45F)
            * (1.0F - horizonNight) * (1.0F - rain * 0.75F) * 0.18F * (0.35F + 0.65F * twilight.strength()));
        red = mix(red, 1.04F, mieStrength);
        green = mix(green, mix(0.95F, twilight.green() + 0.24F, twilight.strength()), mieStrength);
        blue = mix(blue, mix(0.84F, twilight.blue() + 0.18F, twilight.strength()), mieStrength);

        if (rain > 0.001F) {
            float luminance = red * 0.2126F + green * 0.7152F + blue * 0.0722F;
            red = mix(red, luminance * 0.88F, rain * 0.62F);
            green = mix(green, luminance * 0.94F, rain * 0.48F);
            blue = mix(blue, Math.min(1.0F, luminance * 1.04F + 0.045F), rain * 0.42F);
        }

        if (eclipse > 0.001F) {
            float eclipseDarkening = eclipse * 0.85F;
            red *= 1.0F - eclipseDarkening;
            green *= 1.0F - eclipseDarkening;
            blue *= 1.0F - eclipseDarkening;
            float eclipseHorizon = (float) Math.exp(-Math.abs(skyElevation) * 7.2F) * eclipse * (1.0F - spaceBlend);
            red = mix(red, 0.68F, eclipseHorizon * 0.56F);
            green = mix(green, 0.34F, eclipseHorizon * 0.56F);
            blue = mix(blue, 0.16F, eclipseHorizon * 0.56F);
        }

        if (spaceBlend > 0.001F) {
            float spaceRed = mix(0.002F, 0.001F, horizonNight);
            float spaceGreen = mix(0.004F, 0.002F, horizonNight);
            float spaceBlue = mix(0.012F, 0.006F, horizonNight);
            float apparentElevation = elevation + horizonDip + horizonDrop * 0.5F;
            float spaceFacing = smoothstep(0.02F, 0.14F, Math.abs(apparentElevation));
            float spaceDarkening = spaceBlend * spaceFacing;
            red = mix(red, spaceRed, spaceDarkening);
            green = mix(green, spaceGreen, spaceDarkening);
            blue = mix(blue, spaceBlue, spaceDarkening);
        }

        return new Vec3(clamp01(red), clamp01(green), clamp01(blue));
    }

    private static float spaceTransitionStrength(float altitude) {
        return smoothstep(SPACE_TRANSITION_START, SPACE_TRANSITION_END, altitude);
    }

    public static float weatherFade(double cameraY) {
        return 1.0F - smoothstep(WEATHER_FADE_START, WEATHER_FADE_END, (float) cameraY);
    }

    public static float spaceTransition(double cameraY) {
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        return spaceTransitionStrength(altitude);
    }

    public static float surfaceDaylightStrength(float sunUp, double cameraY) {
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float daylight = 1.0F - astronomicalNightStrength(sunUp, altitude);
        return daylight * (1.0F - planetSurfaceBlend(altitude));
    }

    public static float cloudDeckHeight(float vanillaHeight, double cameraY) {
        if (Float.isNaN(vanillaHeight)) {
            return vanillaHeight;
        }
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        return altitude >= HIGH_CLOUD_DECK_START ? HIGH_CLOUD_DECK_HEIGHT : vanillaHeight;
    }

    public static float cloudDeckOpacity(double cameraY) {
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float lowDeckOpacity = 1.0F - smoothstep(
            CLOUD_DECK_FADE_START, CLOUD_DECK_FADE_END, altitude
        );
        float highDeckOpacity = smoothstep(
            HIGH_CLOUD_DECK_START, HIGH_CLOUD_DECK_END, altitude
        ) * (1.0F - planetSurfaceBlend(altitude));
        return Math.max(lowDeckOpacity, highDeckOpacity);
    }

    private static float planetSurfaceBlend(float altitude) {
        return smoothstep(PLANET_SURFACE_START, PLANET_SURFACE_END, altitude);
    }

    public static float weatherStrength(ClientLevel level, double cameraY, float partialTick) {
        if (level == null || cameraY >= WEATHER_FADE_END) {
            return 0.0F;
        }
        float weather = Math.max(level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
        return weather * weatherFade(cameraY);
    }

    public static float celestialHorizonLevel(double cameraY) {
        double altitude = Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float planetBlend = planetSurfaceBlend((float) altitude);
        float horizonClearance = PLANET_HORIZON_TOP_FADE + CELESTIAL_HORIZON_CLEARANCE * planetBlend;
        return (float) Math.sin(-geometricHorizonDip(altitude) + horizonClearance);
    }

    public static float celestialHorizonFadeTop(double cameraY) {
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float fadeDegrees = mix(
            0.6F,
            3.0F,
            smoothstep(PLANET_SURFACE_START, PLANET_SURFACE_END, altitude)
        );
        return celestialHorizonLevel(cameraY) + (float) Math.sin(Math.toRadians(fadeDegrees));
    }

    public static float celestialHorizonOpacity(float localUp, double cameraY) {
        return smoothstep(celestialHorizonLevel(cameraY), celestialHorizonFadeTop(cameraY), localUp);
    }

    public static float milkyWayStrength(FrameAstronomy astro, float altitude, float rain) {
        float nightBlend = astronomicalNightStrength((float) astro.sun().up(), altitude);
        float spaceBlend = spaceTransitionStrength(altitude);
        float highAltitudeVisibility = smoothstep(1_024.0F, 3_600.0F, altitude);
        float milkyWayVisibility = Math.max(nightBlend, highAltitudeVisibility);
        float airDensity = (float) Math.exp(-altitude / 8_500.0);
        float moonlightFade = 1.0F - astro.moonIllumination() * 0.30F * (1.0F - spaceBlend);
        return milkyWayVisibility * (1.0F - rain * 0.92F) * moonlightFade
            * (1.0F - airDensity * 0.08F) * Config.milkyWayBrightness;
    }

    public static int moonPhase(ClientLevel level) {
        return CelestialModel.moonPhase(level.getDayTime());
    }

    public static float astronomicalStarBrightness(ClientLevel level, float partialTick, double cameraY) {
        FrameAstronomy astro = frameAstronomy(level, partialTick);
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float sunUp = (float) astro.sun().up();
        float nightBlend = astronomicalNightStrength(sunUp, altitude);
        float spaceBlend = spaceTransitionStrength(altitude);
        float spaceStarBlend = smoothstep(
            900.0F - OVERWORLD_SEA_LEVEL,
            4_800.0F - OVERWORLD_SEA_LEVEL,
            altitude
        );
        // Smooth quadratic ramp so bright stars emerge gradually as the sky darkens into space (never popping).
        float spaceStars = spaceStarBlend * spaceStarBlend * 0.92F;
        float rain = weatherStrength(level, cameraY, partialTick);
        float eclipse = astro.eclipseStrength() * (1.0F - rain);
        float twilightStars = (1.0F - smoothstep(-0.12F, 0.06F, sunUp)) * mix(0.22F, 0.34F, spaceBlend);
        float eclipseStars = eclipse * 0.65F;
        float moonlightDim = 1.0F - 0.14F * astro.moonIllumination() * (1.0F - spaceBlend);
        float visibility = moonlightDim * (1.0F - rain * 0.95F);
        return Math.max(Math.max(nightBlend * 0.85F, spaceStars), Math.max(twilightStars, eclipseStars))
            * Config.starBrightness
            * visibility;
    }

    public static void renderStars(Matrix4f pose, Matrix4f projection, ClientLevel level, float partialTick) {
        ensureLevel(level);
        double latitude = CelestialModel.latitudeRadians();
        if (starBuffer == null) {
            starBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        long catalogRevision = StarCatalog.revision();
        if (catalogRevision != cachedStarRevision) {
            starBuffer.bind();
            starBuffer.upload(StarCatalog.buildMesh());
            VertexBuffer.unbind();
            cachedStarRevision = catalogRevision;
        }

        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        float brightness = astronomicalStarBrightness(level, partialTick, cameraY);
        if (brightness <= 0.001F) {
            return;
        }

        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float spaceBlend = spaceTransitionStrength(altitude);
        Matrix4f celestialToLocal = CelestialModel.celestialToLocalMatrix(
            level.getDayTime() + partialTick, latitude
        );
        float gameTimeSeconds = (float) (((level.getGameTime() % 72_000L) + partialTick) / 20.0);

        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO
        );
        // RenderSystem.setShaderColor drives ShaderInstance's default ColorModulator during drawWithShader!
        RenderSystem.setShaderColor(brightness, brightness, brightness, 1.0F);
        try {
            starBuffer.bind();
            starBuffer.drawWithShader(
                pose,
                projection,
                LumisphericClient.getStarShader(
                    celestialToLocal,
                    (float) -Math.sin(geometricHorizonDip(altitude)),
                    brightness,
                    gameTimeSeconds,
                    spaceBlend
                )
            );
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.enableCull();
        }
    }

    public static float moonIllumination(ClientLevel level) {
        return CelestialModel.moonIllumination(level.getDayTime());
    }

    public static float eclipseStrength(ClientLevel level) {
        return CelestialModel.eclipseStrength(level.getDayTime(), CelestialModel.latitudeRadians());
    }

    private static float auroraStrength(
        ClientLevel level, float night, float rain, float spaceBlend, Vec3 cameraPosition
    ) {
        if (night < 0.55F || rain > 0.30F || spaceBlend > 0.85F) {
            return 0.0F;
        }

        double absLat = Math.abs(Config.latitude);
        float latitudeFactor = smoothstep(50.0F, 68.0F, (float) absLat);

        float temperature = cachedBiomeTemperature;
        float coldBiomeFactor = 1.0F - smoothstep(-0.2F, 0.25F, temperature);

        float locationEligibility = Math.max(latitudeFactor, coldBiomeFactor * 0.85F);
        if (locationEligibility <= 0.01F) {
            return 0.0F;
        }

        long day = Math.floorDiv(level.getDayTime(), 24000L);
        int dayHash = Math.floorMod(mixDay(day), 100);
        int chancePercent = Math.round(mix(14.0F, 38.0F, latitudeFactor));
        if (dayHash >= chancePercent) {
            return 0.0F;
        }

        float pulse = 0.76F + 0.24F * (float) Math.sin(level.getGameTime() * 0.0035F);
        return locationEligibility * pulse * smoothstep(0.55F, 0.88F, night)
            * (1.0F - rain * 2.5F) * (1.0F - spaceBlend) * 0.78F;
    }

    private static void updateActiveMeteors(
        ClientLevel level,
        float partialTick,
        float rain,
        float night,
        float spaceBlend,
        CelestialModel.MeteorShower shower
    ) {
        for (int i = 0; i < 4; i++) {
            ACTIVE_METEORS[i] = null;
        }
        if (rain > 0.25F || (night < 0.55F && spaceBlend < 0.5F)) {
            return;
        }

        double continuousTicks = level.getGameTime() + partialTick;
        long day = Math.floorDiv(level.getDayTime(), 24000L);

        // Slot 0: Sporadic background meteors year-round.
        ACTIVE_METEORS[0] = sporadicMeteor(continuousTicks, day);

        // If Perseids or Geminids are active and their radiant is near or above the horizon, populate up to 4
        // simultaneous meteors radiating outward in perspective from the real celestial radiant!
        if (shower != null && shower.strength() > 0.14F && shower.radiant().up() > -0.18) {
            Vector3f radiantVec = new Vector3f(
                (float) shower.radiant().east(),
                (float) shower.radiant().up(),
                (float) shower.radiant().south()
            ).normalize();
            Vector3f refUp = Math.abs(radiantVec.y) > 0.95F
                ? new Vector3f(0.0F, 0.0F, 1.0F)
                : new Vector3f(0.0F, 1.0F, 0.0F);
            Vector3f tangentA = new Vector3f(refUp).cross(radiantVec).normalize();
            Vector3f tangentB = new Vector3f(radiantVec).cross(tangentA).normalize();

            // Perseids are faster & crisp; Geminids are slightly slower with richer golden-amber trails.
            float duration = shower.typeIndex() == 1 ? 22.0F : 30.0F;
            int activeChance = Math.round(mix(22.0F, 76.0F, shower.strength()));

            for (int slot = 0; slot < 4; slot++) {
                if (slot == 0 && ACTIVE_METEORS[0] != null) {
                    continue;
                }
                long windowLen = SHOWER_SLOT_WINDOWS[slot];
                long window = Math.floorDiv((long) Math.floor(continuousTicks), windowLen);
                int seed = mixDay(window * 131L + slot * 997L + day * 37L);
                if (Math.floorMod(seed, 100) >= activeChance) {
                    continue;
                }
                float offset = ((seed >>> 4) & 0xFF) / 255.0F * (windowLen - duration - 16.0F);
                float localTick = (float) (continuousTicks - window * windowLen);
                float progress = (localTick - offset) / duration;
                if (progress < 0.0F || progress > 1.0F) {
                    continue;
                }

                // Random outward bearing around the radiant (biased slightly upward when radiant is low).
                float bearing = ((seed >>> 12) & 0x3FF) / 1023.0F * (float) (Math.PI * 2.0);
                float startSep = (float) Math.toRadians(14.0 + (((seed >>> 22) & 0x3F) / 63.0) * 38.0);
                float streakLen = (float) Math.toRadians(14.0 + (((seed >>> 28) & 0xF) / 15.0) * 18.0);
                float endSep = startSep + streakLen;

                Vector3f outDir = new Vector3f(tangentA).mul((float) Math.cos(bearing))
                    .add(new Vector3f(tangentB).mul((float) Math.sin(bearing)))
                    .normalize();
                Vector3f startPos = new Vector3f(radiantVec).mul((float) Math.cos(startSep))
                    .add(new Vector3f(outDir).mul((float) Math.sin(startSep)))
                    .normalize();
                Vector3f endPos = new Vector3f(radiantVec).mul((float) Math.cos(endSep))
                    .add(new Vector3f(outDir).mul((float) Math.sin(endSep)))
                    .normalize();

                // Skip meteors that start or end far below the horizon.
                if (startPos.y < 0.10F && endPos.y < 0.10F) {
                    continue;
                }

                float startEl = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, startPos.y)));
                float endEl = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, endPos.y)));
                float startAz = (float) Math.atan2(startPos.z, startPos.x);
                float endAz = (float) Math.atan2(endPos.z, endPos.x);
                float azDelta = wrapRadiansFloat(endAz - startAz);

                ACTIVE_METEORS[slot] = new MeteorSlot(
                    startAz, startEl, azDelta, endEl - startEl, progress, shower.typeIndex()
                );
            }
        }
    }

    private static MeteorSlot sporadicMeteor(double continuousTicks, long day) {
        long window = Math.floorDiv((long) Math.floor(continuousTicks), SPORADIC_METEOR_WINDOW_TICKS);
        int seed = mixDay(window ^ (day * 31L));
        if (Math.floorMod(seed, 10) >= 3) {
            return null;
        }

        float offsetInWindow = ((seed >>> 4) & 0xFF) / 255.0F
            * (SPORADIC_METEOR_WINDOW_TICKS - SPORADIC_METEOR_DURATION_TICKS - 40.0F);
        float localTick = (float) (continuousTicks - window * SPORADIC_METEOR_WINDOW_TICKS);
        float progress = (localTick - offsetInWindow) / SPORADIC_METEOR_DURATION_TICKS;
        if (progress < 0.0F || progress > 1.0F) {
            return null;
        }

        float startAzimuth = ((seed >>> 16) & 0xFFFF) / 65535.0F * (float) (Math.PI * 2.0);
        float startElevation = 0.30F + ((seed >>> 8) & 0xFF) / 255.0F * 0.65F;
        float azimuthDelta = (((seed >>> 24) & 0xFF) / 255.0F - 0.5F) * 0.52F;
        float elevationDelta = -0.10F - (((seed >>> 2) & 0x3F) / 63.0F) * 0.24F;
        return new MeteorSlot(startAzimuth, startElevation, azimuthDelta, elevationDelta, progress, 0.0F);
    }

    private static float wrapRadiansFloat(float angle) {
        float twoPi = (float) (Math.PI * 2.0);
        while (angle > Math.PI) {
            angle -= twoPi;
        }
        while (angle < -Math.PI) {
            angle += twoPi;
        }
        return angle;
    }

    private static int mixDay(long day) {
        long value = day + 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return (int) (value ^ (value >>> 31));
    }

    public record MeteorSlot(
        float startAzimuth, float startElevation, float azimuthDelta, float elevationDelta,
        float progress, float colorType
    ) {}

    public record FrameAstronomy(
        double astronomicalTime,
        double latitude,
        CelestialModel.Direction sun,
        CelestialModel.Direction moon,
        CelestialModel.Direction galacticNorth,
        CelestialModel.Direction galacticCenter,
        float moonIllumination,
        float eclipseStrength,
        CelestialModel.LunarEclipse lunarEclipse,
        CelestialModel.MeteorShower activeShower
    ) {}

    private static float twilightAmount(float apparentSunElevation, float altitudeBlend) {
        float twilightStart = mix(-0.15F, -0.30F, altitudeBlend);
        float twilightPeak = mix(0.0F, -0.02F, altitudeBlend);
        return smoothstep(twilightStart, twilightPeak, apparentSunElevation)
            * (1.0F - smoothstep(0.06F, 0.22F, apparentSunElevation));
    }

    private static float solarAirMass(float sunY) {
        float sunElevation = (float) Math.asin(Math.max(0.0F, Math.min(1.0F, sunY)));
        float elevationDegrees = (float) Math.toDegrees(sunElevation);
        double airMass = 1.0 / (
            Math.sin(sunElevation)
                + 0.50572 * Math.pow(elevationDegrees + 6.07995, -1.6364)
        );
        return (float) Math.max(1.0, Math.min(8.0, airMass));
    }

    public record Twilight(float strength, float red, float green, float blue, float blueHour) {}

    private static float horizonDip(double altitude) {
        double height = Math.max(0.0, altitude);
        float altitudeLayers = smoothstep(0.0F, 256.0F, (float) height);
        return geometricHorizonDip(height) + altitudeLayers * 0.025F;
    }

    public static float geometricHorizonDip(double altitude) {
        double height = Math.max(0.0, altitude);
        double radius = apparentPlanetRadius(height);
        return (float) Math.acos(radius / (radius + height));
    }

    private static double apparentPlanetRadius(double altitude) {
        double height = Math.max(0.0, altitude);
        if (height <= 0.01) {
            return PLANET_RADIUS;
        }
        double physicalDip = Math.acos(PLANET_RADIUS / (PLANET_RADIUS + height));
        double artisticDip = smoothstep(PLANET_CURVATURE_START, PLANET_CURVATURE_END, (float) height)
            * PLANET_CURVATURE_MAX_DIP;
        double totalDip = Math.min(physicalDip + artisticDip, Math.toRadians(80.0));
        return height / (1.0 / Math.cos(totalDip) - 1.0);
    }

    private static void updatePlanetRotation(double cameraX, double cameraZ) {
        if (Double.isNaN(previousPlanetCameraX) || Double.isNaN(previousPlanetCameraZ)) {
            previousPlanetCameraX = cameraX;
            previousPlanetCameraZ = cameraZ;
            return;
        }

        double deltaX = cameraX - previousPlanetCameraX;
        double deltaZ = cameraZ - previousPlanetCameraZ;
        previousPlanetCameraX = cameraX;
        previousPlanetCameraZ = cameraZ;
        if (deltaX * deltaX + deltaZ * deltaZ > MAX_PLANET_ROTATION_STEP * MAX_PLANET_ROTATION_STEP) {
            return;
        }

        planetRotationAroundZ = wrapAngle(
            planetRotationAroundZ - deltaX * PLANET_ROTATION_RADIANS_PER_BLOCK
        );
        planetRotationAroundX = wrapAngle(
            planetRotationAroundX + deltaZ * PLANET_ROTATION_RADIANS_PER_BLOCK
        );
    }

    private static double wrapAngle(double angle) {
        double fullTurn = Math.PI * 2.0;
        return angle - Math.floor((angle + Math.PI) / fullTurn) * fullTurn;
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float x = Math.max(0.0F, Math.min(1.0F, (value - edge0) / (edge1 - edge0)));
        return x * x * (3.0F - 2.0F * x);
    }

    private static float mix(float from, float to, float amount) {
        return from + (to - from) * Math.max(0.0F, Math.min(1.0F, amount));
    }

    private static boolean areShadersActive() {
        if (!net.neoforged.fml.ModList.get().isLoaded("iris")) {
            return false;
        }
        long now = System.nanoTime();
        if (now - shaderCheckTime < 250_000_000L) {
            return shadersActive;
        }
        shaderCheckTime = now;
        try {
            if (irisShaderCheck == null) {
                Class<?> irisClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = irisClass.getMethod("getInstance").invoke(null);
                irisShaderCheck = irisClass.getMethod("isShaderPackInUse");
            }
            shadersActive = (boolean) irisShaderCheck.invoke(irisApi);
        } catch (ReflectiveOperationException exception) {
            if (!irisLookupFailed) {
                LOGGER.warn("Could not query Iris shader state; disabling Lumispheric's custom sky for compatibility.", exception);
                irisLookupFailed = true;
            }
            shadersActive = true;
        }
        return shadersActive;
    }
}
