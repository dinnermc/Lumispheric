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
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

public final class SkyRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Lumispheric");
    // Lightweight static sphere mesh uploaded once; all atmospheric shading runs per-fragment on GPU.
    private static final int AZIMUTH_SEGMENTS = 64;
    private static final int ELEVATION_SEGMENTS = 48;
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
    private static final long METEOR_WINDOW_TICKS = 900L;
    private static final float METEOR_DURATION_TICKS = 28.0F;

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

    // Smoothly interpolated biome atmosphere traits to avoid sudden pops across biome borders.
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
            && level.dimension() == net.minecraft.world.level.Level.OVERWORLD
            && level.effects().skyType() == net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.NORMAL
            && !areShadersActive();
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

        double astronomicalTime = level.getDayTime() + partialTick;
        double latitude = CelestialModel.latitudeRadians();
        CelestialModel.Direction sun = CelestialModel.sunDirection(astronomicalTime, latitude);
        CelestialModel.Direction moon = CelestialModel.moonDirection(astronomicalTime, latitude);
        float sunUp = (float) sun.up();
        float moonIllum = CelestialModel.moonIllumination(astronomicalTime);
        float eclipse = CelestialModel.eclipseStrength(astronomicalTime, latitude) * (1.0F - rain);
        float horizonDip = horizonDip(altitude);
        float geomDip = geometricHorizonDip(altitude);
        float spaceBlend = spaceTransitionStrength(altitude);
        float planetBlend = planetSurfaceBlend(altitude);
        float nightStrength = astronomicalNightStrength(sunUp, altitude);
        Twilight twilight = twilightPalette(sunUp, altitude);
        float aurora = auroraStrength(level, nightStrength, rain, spaceBlend, cameraPosition);
        float auroraHemisphere = Config.latitude >= 0.0 ? 1.0F : -1.0F;
        Meteor meteor = currentMeteor(level, partialTick, rain, nightStrength, spaceBlend);
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
            sun,
            moon,
            sunUp,
            moonIllum,
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
            meteor != null ? meteor.startAzimuth() : 0.0F,
            meteor != null ? meteor.startElevation() : 0.5F,
            meteor != null ? meteor.azimuthDelta() : 0.0F,
            meteor != null ? meteor.elevationDelta() : 0.0F,
            meteor != null ? meteor.progress() : 0.0F,
            meteor != null ? 1.0F : 0.0F,
            gameTimeSeconds,
            (float) cloudWindAngle,
            (float) apparentPlanetRadius(altitude),
            planetBlend,
            (float) planetRotationAroundZ,
            (float) planetRotationAroundX
        );
        skyBuffer.bind();
        skyBuffer.drawWithShader(pose, projection, skyShader);

        float milkyWayStrength = milkyWayStrength(level, partialTick, sunUp, altitude, rain);
        if (milkyWayStrength > 0.001F) {
            ShaderInstance milkyWayShader = LumisphericClient.getMilkyWayShader(
                CelestialModel.galacticNorthDirection(astronomicalTime, latitude),
                CelestialModel.galacticCenterDirection(astronomicalTime, latitude),
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
        BlockPos pos = BlockPos.containing(cameraPosition);
        Biome biome = level.getBiome(pos).value();
        float temperature = biome.getBaseTemperature();
        boolean hasRain = biome.hasPrecipitation();
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
        // Normalize against red so the disk stays bright while shifting warmly to gold/orange/red near the horizon.
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
        double astronomicalTime = level.getDayTime() + partialTick;
        float rain = weatherStrength(level, cameraY, partialTick);
        double latitude = CelestialModel.latitudeRadians();
        CelestialModel.Direction sun = CelestialModel.sunDirection(astronomicalTime, latitude);
        CelestialModel.Direction moon = CelestialModel.moonDirection(astronomicalTime, latitude);
        float eclipse = CelestialModel.eclipseStrength(astronomicalTime, latitude) * (1.0F - rain);
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        Twilight twilight = twilightPalette((float) sun.up(), altitude);
        float horizonDip = horizonDip(altitude);
        return sampleHorizonAtmosphere(
            -horizonDip, azimuth, rain, twilight, sun, moon, horizonDip,
            CelestialModel.moonIllumination(astronomicalTime), eclipse, altitude
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

        // Subtle, camera-smooth 18% directional blend toward the look azimuth during twilight so looking into a sunset
        // blends naturally with distant terrain without causing jarring shifts while turning.
        if (lookDirection != null && (lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z) > 1.0e-4) {
            double astronomicalTime = level.getDayTime() + fogPartialTick;
            float sunUp = (float) CelestialModel.sunDirection(astronomicalTime, CelestialModel.latitudeRadians()).up();
            float alt = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
            float twilightWeight = twilightPalette(sunUp, alt).strength() * 0.18F;
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
            2.8F + smoothedBiomeClearness * 0.45F - smoothedBiomeDust * 0.35F,
            12.0F,
            spaceBlend
        );
        float horizon = (float) Math.exp(-Math.abs(skyElevation) * horizonSharpness);

        float zenithR = mix(mix(0.15F, 0.09F, smoothedBiomeClearness * 0.45F), 0.20F, smoothedBiomeDust * 0.22F);
        float zenithG = mix(mix(0.28F, 0.21F, smoothedBiomeClearness * 0.45F), 0.33F, smoothedBiomeDust * 0.22F);
        float zenithB = mix(mix(0.64F, 0.56F, smoothedBiomeClearness * 0.45F), 0.62F, smoothedBiomeDust * 0.22F);

        float horizonRed = mix(mix(0.60F, 0.14F, spaceBlend), 0.73F, smoothedBiomeDust * 0.22F * (1.0F - spaceBlend));
        float horizonGreen = mix(mix(0.74F, 0.30F, spaceBlend), 0.72F, smoothedBiomeDust * 0.22F * (1.0F - spaceBlend));
        float horizonBlue = mix(mix(0.89F, 0.62F, spaceBlend), 0.67F, smoothedBiomeDust * 0.22F * (1.0F - spaceBlend));

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

        red = mix(red, mix(0.005F, 0.016F, horizon), horizonNight);
        green = mix(green, mix(0.008F, 0.024F, horizon), horizonNight);
        blue = mix(blue, mix(0.018F, 0.040F, horizon), horizonNight);

        float atmosphereHeight = Math.max(0.0F, skyElevation) / 0.045F;
        float upperAtmosphere = spaceBlend * (float) Math.exp(-atmosphereHeight * atmosphereHeight);
        red = mix(red, mix(0.065F, 0.006F, horizonNight), upperAtmosphere * 0.82F);
        green = mix(green, mix(0.16F, 0.008F, horizonNight), upperAtmosphere * 0.82F);
        blue = mix(blue, mix(0.42F, 0.020F, horizonNight), upperAtmosphere * 0.82F);

        float moonAbove = smoothstep(-0.08F, 0.14F, moonY);
        float moonHalo = (float) Math.pow(Math.max(moonDot, 0.0F), 10.0) * 0.036F;
        float moonAmbient = (0.004F + 0.006F * horizon) * moonAbove;
        float lunarFactor = moonIllumination * horizonNight * (1.0F - rain * 0.85F) * (1.0F - spaceBlend * 0.75F);
        float lunarTotal = (moonHalo * moonAbove + moonAmbient) * lunarFactor;
        red += 0.42F * lunarTotal;
        green += 0.62F * lunarTotal;
        blue += 0.96F * lunarTotal;

        float sunAngle = (float) Math.acos(sunDot);
        float rayleighPhase = 0.75F * (1.0F + sunDot * sunDot);
        float rayleighScatter = (1.0F - horizonNight) * (1.0F - rain) * rayleighPhase * (0.35F + 0.65F * horizon);
        red += rayleighScatter * 0.012F;
        green += rayleighScatter * 0.035F;
        blue += rayleighScatter * 0.085F;

        float twilightLight = twilight.strength() * (1.0F - rain * 0.75F);
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

        float mieAsymmetry = mix(0.78F, 0.72F, smoothedBiomeDust * 0.4F);
        float mieDenom = 1.0F + mieAsymmetry * mieAsymmetry - 2.0F * mieAsymmetry * sunDot;
        float mieRatio = (1.0F - mieAsymmetry) * (1.0F - mieAsymmetry) / Math.max(mieDenom, 1.0e-4F);
        float miePhase = mieRatio * (float) Math.sqrt(mieRatio);
        float mieStrength = Math.min(0.85F, miePhase * Config.mieIntensity * (1.0F + smoothedBiomeDust * 0.45F)
            * (1.0F - horizonNight) * (1.0F - rain * 0.75F) * 0.16F * (0.35F + 0.65F * twilight.strength()));
        red = mix(red, 0.99F, mieStrength);
        green = mix(green, mix(0.92F, twilight.green() + 0.20F, twilight.strength()), mieStrength);
        blue = mix(blue, mix(0.82F, twilight.blue() + 0.20F, twilight.strength()), mieStrength);

        float luminance = red * 0.2126F + green * 0.7152F + blue * 0.0722F;
        red = mix(red, luminance * 0.88F, rain * 0.62F);
        green = mix(green, luminance * 0.94F, rain * 0.48F);
        blue = mix(blue, Math.min(1.0F, luminance * 1.04F + 0.045F), rain * 0.42F);

        if (eclipse > 0.001F) {
            float eclipseDarkening = eclipse * 0.78F;
            red *= 1.0F - eclipseDarkening;
            green *= 1.0F - eclipseDarkening;
            blue *= 1.0F - eclipseDarkening;
            float eclipseHorizon = (float) Math.exp(-Math.abs(skyElevation) * 7.5F) * eclipse * (1.0F - spaceBlend);
            red = mix(red, 0.55F, eclipseHorizon * 0.45F);
            green = mix(green, 0.30F, eclipseHorizon * 0.45F);
            blue = mix(blue, 0.16F, eclipseHorizon * 0.45F);
        }

        float spaceRed = mix(0.002F, 0.001F, horizonNight);
        float spaceGreen = mix(0.004F, 0.002F, horizonNight);
        float spaceBlue = mix(0.012F, 0.006F, horizonNight);
        float apparentElevation = elevation + horizonDip + horizonDrop * 0.5F;
        float spaceFacing = smoothstep(0.02F, 0.14F, Math.abs(apparentElevation));
        float spaceDarkening = spaceBlend * spaceFacing;
        red = mix(red, spaceRed, spaceDarkening);
        green = mix(green, spaceGreen, spaceDarkening);
        blue = mix(blue, spaceBlue, spaceDarkening);

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

    private static float milkyWayStrength(
        ClientLevel level, float partialTick, float sunUp, float altitude, float rain
    ) {
        double astronomicalTime = level.getDayTime() + partialTick;
        float nightBlend = astronomicalNightStrength(sunUp, altitude);
        float moonIllumination = CelestialModel.moonIllumination(astronomicalTime);
        float spaceBlend = spaceTransitionStrength(altitude);
        float highAltitudeVisibility = smoothstep(1_024.0F, 3_600.0F, altitude);
        float milkyWayVisibility = Math.max(nightBlend, highAltitudeVisibility);
        float airDensity = (float) Math.exp(-altitude / 8_500.0);
        float moonlightFade = 1.0F - moonIllumination * 0.30F * (1.0F - spaceBlend);
        return milkyWayVisibility * (1.0F - rain * 0.92F) * moonlightFade
            * (1.0F - airDensity * 0.08F) * Config.milkyWayBrightness;
    }

    public static int moonPhase(ClientLevel level) {
        return CelestialModel.moonPhase(level.getDayTime());
    }

    public static float astronomicalStarBrightness(ClientLevel level, float partialTick, double cameraY) {
        double astronomicalTime = level.getDayTime() + partialTick;
        double latitude = CelestialModel.latitudeRadians();
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float sunUp = (float) CelestialModel.sunDirection(astronomicalTime, latitude).up();
        float nightBlend = astronomicalNightStrength(sunUp, altitude);
        float spaceBlend = spaceTransitionStrength(altitude);
        float spaceStarBlend = smoothstep(
            2_048.0F - OVERWORLD_SEA_LEVEL,
            4_096.0F - OVERWORLD_SEA_LEVEL,
            altitude
        );
        float eclipse = CelestialModel.eclipseStrength(astronomicalTime, latitude)
            * (1.0F - weatherStrength(level, cameraY, partialTick));
        float twilightStars = (1.0F - smoothstep(-0.12F, 0.06F, sunUp)) * mix(0.22F, 0.34F, spaceBlend);
        float eclipseStars = eclipse * 0.65F;
        return Math.max(Math.max(nightBlend * 0.85F, spaceStarBlend * 0.85F), Math.max(twilightStars, eclipseStars))
            * Config.starBrightness
            * starVisibility(level, partialTick);
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
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
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

    public static float starVisibility(ClientLevel level, float partialTick) {
        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        float rain = weatherStrength(level, cameraY, partialTick);
        float spaceBlend = spaceTransition(cameraY);
        float moonlightDim = 1.0F - 0.14F * moonIllumination(level) * (1.0F - spaceBlend);
        return moonlightDim * (1.0F - rain * 0.95F);
    }

    private static float auroraStrength(
        ClientLevel level, float night, float rain, float spaceBlend, Vec3 cameraPosition
    ) {
        if (night < 0.55F || rain > 0.30F || spaceBlend > 0.85F) {
            return 0.0F;
        }

        double absLat = Math.abs(Config.latitude);
        float latitudeFactor = smoothstep(50.0F, 68.0F, (float) absLat);

        BlockPos position = BlockPos.containing(cameraPosition);
        float temperature = level.getBiome(position).value().getBaseTemperature();
        float coldBiomeFactor = 1.0F - smoothstep(-0.2F, 0.25F, temperature);

        float locationEligibility = Math.max(latitudeFactor, coldBiomeFactor * 0.85F);
        if (locationEligibility <= 0.01F) {
            return 0.0F;
        }

        long day = Math.floorDiv(level.getDayTime(), 24000L);
        int dayHash = Math.floorMod(mixDay(day), 100);
        // More frequent at high polar latitudes (~1 in 3 nights), rarer in cold mid-latitude biomes (~1 in 7 nights).
        int chancePercent = Math.round(mix(14.0F, 38.0F, latitudeFactor));
        if (dayHash >= chancePercent) {
            return 0.0F;
        }

        float pulse = 0.76F + 0.24F * (float) Math.sin(level.getGameTime() * 0.0035F);
        return locationEligibility * pulse * smoothstep(0.55F, 0.88F, night)
            * (1.0F - rain * 2.5F) * (1.0F - spaceBlend) * 0.78F;
    }

    private static Meteor currentMeteor(
        ClientLevel level, float partialTick, float rain, float night, float spaceBlend
    ) {
        if (rain > 0.25F || (night < 0.55F && spaceBlend < 0.5F)) {
            return null;
        }

        double continuousTicks = level.getGameTime() + partialTick;
        long window = Math.floorDiv((long) Math.floor(continuousTicks), METEOR_WINDOW_TICKS);
        int seed = mixDay(window ^ (Math.floorDiv(level.getDayTime(), 24000L) * 31L));
        // Roughly 1-2 shooting stars per clear night (~30% per 45-second window).
        if (Math.floorMod(seed, 10) >= 3) {
            return null;
        }

        float offsetInWindow = ((seed >>> 4) & 0xFF) / 255.0F * (METEOR_WINDOW_TICKS - METEOR_DURATION_TICKS - 40.0F);
        float localTick = (float) (continuousTicks - window * METEOR_WINDOW_TICKS);
        float progress = (localTick - offsetInWindow) / METEOR_DURATION_TICKS;
        if (progress < 0.0F || progress > 1.0F) {
            return null;
        }

        float startAzimuth = ((seed >>> 16) & 0xFFFF) / 65535.0F * (float) (Math.PI * 2.0);
        float startElevation = 0.30F + ((seed >>> 8) & 0xFF) / 255.0F * 0.65F;
        float azimuthDelta = (((seed >>> 24) & 0xFF) / 255.0F - 0.5F) * 0.52F;
        // Meteors generally streak slightly downward across the sky.
        float elevationDelta = -0.10F - (((seed >>> 2) & 0x3F) / 63.0F) * 0.24F;
        return new Meteor(startAzimuth, startElevation, azimuthDelta, elevationDelta, progress);
    }

    private static int mixDay(long day) {
        long value = day + 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return (int) (value ^ (value >>> 31));
    }

    private record Meteor(
        float startAzimuth, float startElevation, float azimuthDelta, float elevationDelta, float progress
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
