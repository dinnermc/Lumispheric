package dinner.dev.lumispheric;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

public final class SkyRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Lumispheric");
    private static final int AZIMUTH_SEGMENTS = 128;
    private static final int ELEVATION_SEGMENTS = 96;
    private static final int AZIMUTH_STRIDE = AZIMUTH_SEGMENTS + 1;
    private static final int FOG_AZIMUTH_SAMPLES = 16;
    private static final int SKY_VERTEX_COUNT = (ELEVATION_SEGMENTS + 1) * AZIMUTH_STRIDE;
    private static final float[] SKY_POSITIONS = new float[SKY_VERTEX_COUNT * 3];
    private static final int[] SKY_COLORS = new int[SKY_VERTEX_COUNT];
    // Level#getSeaLevel is deprecated and hardcodes 63; this renderer only handles the Overworld.
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
    private static VertexBuffer skyBuffer;
    private static VertexBuffer starBuffer;
    private static int cachedSkyKey = Integer.MIN_VALUE;
    private static long cachedSkyDayTimeBucket = Long.MIN_VALUE;
    private static long cachedSkyAnimationBucket = Long.MIN_VALUE;
    private static long cachedStarRevision = Long.MIN_VALUE;
    private static int cachedAltitude = Integer.MIN_VALUE;
    private static double cachedSkyLatitude = Double.NaN;
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
    private static ClientLevel cachedFogLevel;
    private static long cachedFogDayTime = Long.MIN_VALUE;
    private static int cachedFogAltitude = Integer.MIN_VALUE;
    private static int cachedFogWeather = Integer.MIN_VALUE;
    private static double cachedFogLatitude = Double.NaN;
    private static float cachedFogSunsetIntensity = Float.NaN;
    private static float cachedFogMieIntensity = Float.NaN;
    private static float cachedFogMilkyWayBrightness = Float.NaN;
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
        cachedSkyKey = Integer.MIN_VALUE;
        cachedSkyDayTimeBucket = Long.MIN_VALUE;
        cachedSkyAnimationBucket = Long.MIN_VALUE;
        cachedAltitude = Integer.MIN_VALUE;
        cachedSkyLatitude = Double.NaN;
        previousPlanetCameraX = Double.NaN;
        previousPlanetCameraZ = Double.NaN;
        planetRotationAroundX = 0.0;
        planetRotationAroundZ = 0.0;
        cachedStarRevision = Long.MIN_VALUE;
        cachedFogLevel = null;
        cachedFogDayTime = Long.MIN_VALUE;
        cachedFogAltitude = Integer.MIN_VALUE;
        cachedFogWeather = Integer.MIN_VALUE;
        cachedFogLatitude = Double.NaN;
        cachedFogSunsetIntensity = Float.NaN;
        cachedFogMieIntensity = Float.NaN;
        cachedFogMilkyWayBrightness = Float.NaN;
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
        Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double cameraY = cameraPosition.y;
        float rain = weatherStrength(level, cameraY, partialTick);
        int altitude = Math.max(0, (int) Math.floor(cameraY - OVERWORLD_SEA_LEVEL));
        updatePlanetRotation(cameraPosition.x, cameraPosition.z);
        float horizonDip = horizonDip(altitude);
        long dayTimeBucket = level.getDayTime() / 10L;
        long animationBucket = level.getGameTime() / 10L;
        int key = (Math.round(rain * 100.0F) << 8)
            ^ (Math.round(Config.sunsetIntensity * 100.0F) << 16)
            ^ (Math.round(Config.mieIntensity * 100.0F) << 24)
            ^ Float.floatToIntBits(Config.milkyWayBrightness);

        if (skyBuffer == null || key != cachedSkyKey || altitude != cachedAltitude
            || dayTimeBucket != cachedSkyDayTimeBucket || animationBucket != cachedSkyAnimationBucket
            || Double.compare(Config.latitude, cachedSkyLatitude) != 0) {
            rebuild(level, partialTick, rain, horizonDip, key, altitude, dayTimeBucket, animationBucket);
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        float planetBlend = planetSurfaceBlend(altitude);
        double astronomicalTime = level.getDayTime() + partialTick;
        double latitude = CelestialModel.latitudeRadians();
        CelestialModel.Direction sun = CelestialModel.sunDirection(astronomicalTime, latitude);
        CelestialModel.Direction moon = CelestialModel.moonDirection(astronomicalTime, latitude);
        double cloudWindAngle = Math.IEEEremainder(
            (level.getGameTime() + (double) partialTick) * CLOUD_WIND_RADIANS_PER_TICK,
            Math.PI * 2.0
        );
        ShaderInstance planetShader = LumisphericClient.getPlanetShader(
            sun,
            moon,
            (float) sun.up(),
            CelestialModel.moonIllumination(astronomicalTime),
            (float) cloudWindAngle,
            (float) apparentPlanetRadius(altitude),
            altitude,
            geometricHorizonDip(altitude),
            planetBlend,
            (float) planetRotationAroundZ,
            (float) planetRotationAroundX
        );
        skyBuffer.drawWithShader(pose, projection, planetShader);
        VertexBuffer.unbind();
        float milkyWayStrength = milkyWayStrength(level, partialTick);
        if (milkyWayStrength > 0.001F) {
            ShaderInstance milkyWayShader = LumisphericClient.getMilkyWayShader(
                CelestialModel.galacticNorthDirection(astronomicalTime, latitude),
                CelestialModel.galacticCenterDirection(astronomicalTime, latitude),
                milkyWayStrength
            );
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO
            );
            skyBuffer.drawWithShader(pose, projection, milkyWayShader);
            VertexBuffer.unbind();
            RenderSystem.defaultBlendFunc();
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void rebuild(
        ClientLevel level, float partialTick, float rain, float horizonDip, int key, int altitude,
        long dayTimeBucket, long animationBucket
    ) {
        if (skyBuffer == null) {
            skyBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float night = level.getStarBrightness(partialTick);
        double astronomicalTime = level.getDayTime() + partialTick;
        double latitude = CelestialModel.latitudeRadians();
        CelestialModel.Direction sun = CelestialModel.sunDirection(astronomicalTime, latitude);
        float sunX = (float) sun.east();
        float sunY = (float) sun.up();
        float sunZ = (float) sun.south();
        CelestialModel.Direction galacticNorth = CelestialModel.galacticNorthDirection(
            astronomicalTime, latitude
        );
        CelestialModel.Direction galacticCenter = CelestialModel.galacticCenterDirection(
            astronomicalTime, latitude
        );
        float galaxyStrength = milkyWayStrength(level, partialTick);
        Twilight twilight = twilightPalette(sunY, altitude);
        float moonIllumination = CelestialModel.moonIllumination(astronomicalTime);
        float eclipse = CelestialModel.eclipseStrength(astronomicalTime, latitude)
            * (1.0F - rain);
        float aurora = auroraStrength(level, night, rain);
        Meteor meteor = currentMeteor(level, rain);
        long gameTime = level.getGameTime();
        for (int elevation = 0; elevation <= ELEVATION_SEGMENTS; elevation++) {
            float angle = -(float) Math.PI * 0.5F + (float) Math.PI * elevation / ELEVATION_SEGMENTS;
            float horizontal = (float) Math.cos(angle);
            float y = (float) Math.sin(angle) * 128.0F;
            for (int azimuth = 0; azimuth <= AZIMUTH_SEGMENTS; azimuth++) {
                float azimuthAngle = (float) (Math.PI * 2.0) * azimuth / AZIMUTH_SEGMENTS;
                int index = elevation * AZIMUTH_STRIDE + azimuth;
                int positionIndex = index * 3;
                SKY_POSITIONS[positionIndex] = (float) Math.cos(azimuthAngle) * horizontal * 128.0F;
                SKY_POSITIONS[positionIndex + 1] = y;
                SKY_POSITIONS[positionIndex + 2] = (float) Math.sin(azimuthAngle) * horizontal * 128.0F;
                int skyColor = atmosphereColor(
                    angle, azimuthAngle, night, rain, twilight, sunX, sunY, sunZ, horizonDip,
                    moonIllumination, eclipse, aurora, meteor, gameTime, altitude
                );
                SKY_COLORS[index] = applyMilkyWay(
                    skyColor, angle, azimuthAngle, galacticNorth, galacticCenter, galaxyStrength
                );
            }
        }

        for (int elevation = 0; elevation < ELEVATION_SEGMENTS; elevation++) {
            for (int azimuth = 0; azimuth < AZIMUTH_SEGMENTS; azimuth++) {
                int lowerLeft = elevation * AZIMUTH_STRIDE + azimuth;
                int lowerRight = lowerLeft + 1;
                int upperLeft = lowerLeft + AZIMUTH_STRIDE;
                int upperRight = upperLeft + 1;
                addVertex(builder, lowerLeft);
                addVertex(builder, lowerRight);
                addVertex(builder, upperRight);
                addVertex(builder, lowerLeft);
                addVertex(builder, upperRight);
                addVertex(builder, upperLeft);
            }
        }

        skyBuffer.upload(builder.buildOrThrow());
        cachedSkyKey = key;
        cachedSkyDayTimeBucket = dayTimeBucket;
        cachedSkyAnimationBucket = animationBucket;
        cachedAltitude = altitude;
        cachedSkyLatitude = Config.latitude;
    }

    private static void addVertex(BufferBuilder builder, int index) {
        int positionIndex = index * 3;
        int color = SKY_COLORS[index];
        builder.addVertex(
            SKY_POSITIONS[positionIndex],
            SKY_POSITIONS[positionIndex + 1],
            SKY_POSITIONS[positionIndex + 2]
        ).setColor(
            ((color >>> 16) & 0xFF) / 255.0F,
            ((color >>> 8) & 0xFF) / 255.0F,
            (color & 0xFF) / 255.0F,
            1.0F
        );
    }

    public static Vec3 horizonColor(ClientLevel level, float partialTick, float azimuth, double cameraY) {
        double astronomicalTime = level.getDayTime() + partialTick;
        float night = level.getStarBrightness(partialTick);
        float rain = weatherStrength(level, cameraY, partialTick);
        double latitude = CelestialModel.latitudeRadians();
        CelestialModel.Direction sun = CelestialModel.sunDirection(astronomicalTime, latitude);
        float eclipse = CelestialModel.eclipseStrength(astronomicalTime, latitude)
            * (1.0F - rain);
        float sunX = (float) sun.east();
        float sunY = (float) sun.up();
        float sunZ = (float) sun.south();
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        Twilight twilight = twilightPalette(sunY, altitude);
        float horizonDip = horizonDip(Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL));
        int color = atmosphereColor(
            -horizonDip, azimuth, night, rain, twilight, sunX, sunY, sunZ, horizonDip,
            CelestialModel.moonIllumination(astronomicalTime), eclipse, 0.0F, null, level.getGameTime(),
            altitude
        );
        color = applyMilkyWay(
            color,
            -horizonDip,
            azimuth,
            CelestialModel.galacticNorthDirection(astronomicalTime, latitude),
            CelestialModel.galacticCenterDirection(astronomicalTime, latitude),
            milkyWayStrength(level, partialTick)
        );
        return new Vec3(((color >>> 16) & 0xFF) / 255.0, ((color >>> 8) & 0xFF) / 255.0, (color & 0xFF) / 255.0);
    }

    public static Vec3 horizonFogColor(ClientLevel level, double cameraY) {
        float fogPartialTick = 0.5F;
        long dayTime = level.getDayTime() / 10L;
        int altitude = (int) Math.floor(cameraY - OVERWORLD_SEA_LEVEL);
        int weather = Float.floatToIntBits(
            Math.max(level.getRainLevel(fogPartialTick), level.getThunderLevel(fogPartialTick))
        );
        if (cachedFogColor != null
            && cachedFogLevel == level
            && cachedFogDayTime == dayTime
            && cachedFogAltitude == altitude
            && cachedFogWeather == weather
            && Double.compare(cachedFogLatitude, Config.latitude) == 0
            && Float.compare(cachedFogSunsetIntensity, Config.sunsetIntensity) == 0
            && Float.compare(cachedFogMieIntensity, Config.mieIntensity) == 0
            && Float.compare(cachedFogMilkyWayBrightness, Config.milkyWayBrightness) == 0) {
            return cachedFogColor;
        }

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
        cachedFogSunsetIntensity = Config.sunsetIntensity;
        cachedFogMieIntensity = Config.mieIntensity;
        cachedFogMilkyWayBrightness = Config.milkyWayBrightness;
        return cachedFogColor;
    }

    private static int atmosphereColor(
        float elevation, float azimuth, float night, float rain, Twilight twilight, float sunX, float sunY,
        float sunZ, float horizonDip, float moonIllumination, float eclipse, float aurora, Meteor meteor, long gameTime,
        float altitude
    ) {
        float spaceBlend = spaceTransitionStrength(altitude);
        float horizonDrop = spaceBlend * 0.10F;
        float skyElevation = elevation + horizonDip + horizonDrop;
        float altitudeBlend = smoothstep(0.0F, 256.0F, altitude);
        float geometricDip = horizonDip - altitudeBlend * 0.025F;
        float horizonSharpness = mix(2.8F, 12.0F, spaceBlend);
        float horizon = (float) Math.exp(-Math.abs(skyElevation) * horizonSharpness);
        float horizonRed = mix(0.60F, 0.14F, spaceBlend);
        float horizonGreen = mix(0.74F, 0.30F, spaceBlend);
        float horizonBlue = mix(0.89F, 0.62F, spaceBlend);
        float red = mix(0.15F, horizonRed, horizon);
        float green = mix(0.28F, horizonGreen, horizon);
        float blue = mix(0.64F, horizonBlue, horizon);

        float horizontal = (float) Math.cos(elevation);
        float viewX = (float) Math.cos(azimuth) * horizontal;
        float viewY = (float) Math.sin(elevation);
        float viewZ = (float) Math.sin(azimuth) * horizontal;
        float sunDot = Math.max(-1.0F, Math.min(1.0F, viewX * sunX + viewY * sunY + viewZ * sunZ));
        float sunElevation = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, sunY)));
        float apparentSunElevation = sunElevation + geometricDip;
        float vanillaNight = Math.max(
            Math.min(night * 2.0F, 1.0F),
            1.0F - smoothstep(-0.28F, 0.03F, sunY)
        );
        float highAltitudeNight = 1.0F - smoothstep(-0.32F, 0.02F, apparentSunElevation);
        float nightBlend = mix(vanillaNight, highAltitudeNight, altitudeBlend);
        float horizonNight = mix(
            nightBlend,
            1.0F - smoothstep(-0.16F, 0.22F, sunDot),
            spaceBlend
        );
        red = mix(red, mix(0.006F, 0.018F, horizon), horizonNight);
        green = mix(green, mix(0.009F, 0.026F, horizon), horizonNight);
        blue = mix(blue, mix(0.018F, 0.040F, horizon), horizonNight);

        float atmosphereHeight = Math.max(0.0F, skyElevation) / 0.045F;
        float upperAtmosphere = spaceBlend * (float) Math.exp(-atmosphereHeight * atmosphereHeight);
        red = mix(red, mix(0.065F, 0.006F, horizonNight), upperAtmosphere * 0.82F);
        green = mix(green, mix(0.16F, 0.008F, horizonNight), upperAtmosphere * 0.82F);
        blue = mix(blue, mix(0.42F, 0.020F, horizonNight), upperAtmosphere * 0.82F);

        float lunarGlow = moonIllumination * horizonNight * (1.0F - rain) * 0.012F;
        red += lunarGlow * 0.35F;
        green += lunarGlow * 0.65F;
        blue += lunarGlow;

        float sunAngle = (float) Math.acos(sunDot);
        float rayleighPhase = 0.75F * (1.0F + sunDot * sunDot);
        float rayleighScatter = (1.0F - horizonNight) * (1.0F - rain)
            * rayleighPhase * (0.35F + 0.65F * horizon);
        red += rayleighScatter * 0.012F;
        green += rayleighScatter * 0.035F;
        blue += rayleighScatter * 0.085F;

        float limbCenter = elevation + geometricDip - 0.03F;
        float blueLimbWidth = 0.035F + spaceBlend * 0.012F;
        float blueLimbDistance = limbCenter / blueLimbWidth;
        float blueLimbBand = (float) Math.exp(-blueLimbDistance * blueLimbDistance)
            * spaceBlend * (1.0F - horizonNight * 0.65F) * (1.0F - rain * 0.5F);
        float sunwardAlignment = smoothstep(-0.16F, 0.28F, sunDot);
        float blueLimb = blueLimbBand * sunwardAlignment;
        red += blueLimb * 0.025F;
        green += blueLimb * 0.095F;
        blue += blueLimb * 0.22F;

        float warmLimbWidth = 0.045F + spaceBlend * 0.02F;
        float warmLimbDistance = (elevation + geometricDip - 0.006F) / warmLimbWidth;
        float warmLimb = (float) Math.exp(-warmLimbDistance * warmLimbDistance)
            * spaceBlend * twilight.strength * smoothstep(0.55F, 0.95F, sunDot)
            * (1.0F - rain * 0.5F) * 0.7F;
        red += warmLimb * 0.12F;
        green += warmLimb * 0.045F;
        blue += warmLimb * 0.018F;

        float twilightLight = twilight.strength * (1.0F - rain * 0.75F);
        float wideGlow = (float) Math.exp(-(sunAngle * sunAngle) / 0.36F) * twilightLight;
        float coreGlow = (float) Math.exp(-(sunAngle * sunAngle) / 0.085F) * twilightLight;
        float sunwardHorizon = (float) Math.exp(-(sunAngle * sunAngle) / 0.72F);
        float horizonTwilight = horizon * twilightLight * (0.58F + 0.42F * sunwardHorizon) * 0.72F;
        float warmPalette = 0.25F + 0.55F * sunwardHorizon;
        float peachToRose = 1.0F - twilight.blueHour;
        float broadRed = mix(0.68F, 0.84F, peachToRose);
        float broadGreen = mix(0.55F, 0.66F, peachToRose);
        float broadBlue = mix(0.72F, 0.56F, peachToRose);
        red = mix(red, mix(broadRed, twilight.red, warmPalette), horizonTwilight);
        green = mix(green, mix(broadGreen, twilight.green, warmPalette), horizonTwilight);
        blue = mix(blue, mix(broadBlue, twilight.blue, warmPalette), horizonTwilight);

        float roseHeight = (skyElevation - 0.10F) / 0.22F;
        float roseBand = (float) Math.exp(-roseHeight * roseHeight) * horizon * twilightLight
            * (0.65F + 0.35F * (1.0F - sunwardHorizon)) * 0.36F;
        float roseRed = mix(0.74F, 0.56F, twilight.blueHour);
        float roseGreen = mix(0.48F, 0.43F, twilight.blueHour);
        float roseBlue = mix(0.56F, 0.72F, twilight.blueHour);
        red = mix(red, roseRed, roseBand);
        green = mix(green, roseGreen, roseBand);
        blue = mix(blue, roseBlue, roseBand);

        float antisolarDistance = (float) Math.PI - sunAngle;
        float antisolarGlow = (float) Math.exp(-(antisolarDistance * antisolarDistance) / 0.62F)
            * horizon * twilightLight * twilight.blueHour * 0.24F;
        red = mix(red, 0.44F, antisolarGlow);
        green = mix(green, 0.27F, antisolarGlow);
        blue = mix(blue, 0.54F, antisolarGlow);

        float outer = wideGlow * 0.34F;
        float core = coreGlow * 0.32F;
        red = mix(red, mix(0.88F, twilight.red, 0.65F), outer);
        green = mix(green, twilight.green + 0.08F, outer);
        blue = mix(blue, twilight.blue + 0.08F, outer);
        red = mix(red, twilight.red, core);
        green = mix(green, twilight.green + 0.12F, core);
        blue = mix(blue, twilight.blue + 0.13F, core);

        float mieAsymmetry = 0.78F;
        float mieDenominator = 1.0F + mieAsymmetry * mieAsymmetry - 2.0F * mieAsymmetry * sunDot;
        float mieRatio = (1.0F - mieAsymmetry) * (1.0F - mieAsymmetry) / mieDenominator;
        float miePhase = mieRatio * (float) Math.sqrt(mieRatio);
        float mieStrength = miePhase * Config.mieIntensity * (1.0F - horizonNight)
            * (1.0F - rain * 0.75F) * 0.16F * (0.35F + 0.65F * twilight.strength);
        float mieGreen = mix(0.92F, twilight.green + 0.20F, twilight.strength);
        float mieBlue = mix(0.82F, twilight.blue + 0.20F, twilight.strength);
        red = mix(red, 0.99F, mieStrength);
        green = mix(green, mieGreen, mieStrength);
        blue = mix(blue, mieBlue, mieStrength);

        float luminance = red * 0.2126F + green * 0.7152F + blue * 0.0722F;
        red = mix(red, luminance * 0.88F, rain * 0.62F);
        green = mix(green, luminance * 0.94F, rain * 0.48F);
        blue = mix(blue, Math.min(1.0F, luminance * 1.04F + 0.045F), rain * 0.42F);

        float auroraBand = auroraBand(elevation, azimuth, gameTime);
        float auroraLight = aurora * auroraBand;
        red = mix(red, 0.24F, auroraLight * 0.45F);
        green = mix(green, 0.86F, auroraLight);
        blue = mix(blue, 0.68F, auroraLight * 0.8F);

        float meteorLight = meteor == null ? 0.0F : meteor.intensity(elevation, azimuth);
        red = mix(red, 0.92F, meteorLight);
        green = mix(green, 0.97F, meteorLight);
        blue = mix(blue, 1.0F, meteorLight);

        float eclipseDarkening = eclipse * 0.72F;
        red *= 1.0F - eclipseDarkening;
        green *= 1.0F - eclipseDarkening;
        blue *= 1.0F - eclipseDarkening;
        float coronaDistance = (sunAngle - 0.10F) / 0.055F;
        float corona = (float) Math.exp(-coronaDistance * coronaDistance) * eclipse;
        red = mix(red, 0.92F, corona);
        green = mix(green, 0.78F, corona);
        blue = mix(blue, 0.52F, corona);

        float spaceRed = mix(0.002F, 0.001F, horizonNight);
        float spaceGreen = mix(0.004F, 0.002F, horizonNight);
        float spaceBlue = mix(0.012F, 0.006F, horizonNight);
        float apparentElevation = elevation + horizonDip + horizonDrop * 0.5F;
        float spaceFacing = smoothstep(0.02F, 0.14F, Math.abs(apparentElevation));
        float spaceDarkening = spaceBlend * spaceFacing;
        red = mix(red, spaceRed, spaceDarkening);
        green = mix(green, spaceGreen, spaceDarkening);
        blue = mix(blue, spaceBlue, spaceDarkening);

        return (toByte(red) << 16) | (toByte(green) << 8) | toByte(blue);
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

    public static float surfaceDaylightStrength(
        float sunUp, float vanillaStarBrightness, double cameraY
    ) {
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float astronomicalDaylight = smoothstep(-0.08F, 0.16F, sunUp);
        float vanillaDaylight = 1.0F - Math.max(0.0F, Math.min(1.0F, vanillaStarBrightness));
        float daylight = Math.max(astronomicalDaylight, vanillaDaylight);
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

    private static float milkyWayStrength(ClientLevel level, float partialTick) {
        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        float rain = weatherStrength(level, cameraY, partialTick);
        float night = level.getStarBrightness(partialTick);
        double astronomicalTime = level.getDayTime() + partialTick;
        CelestialModel.Direction sun = CelestialModel.sunDirection(
            astronomicalTime, CelestialModel.latitudeRadians()
        );
        float nightBlend = Math.max(
            Math.min(night * 2.0F, 1.0F),
            1.0F - smoothstep(-0.28F, 0.03F, (float) sun.up())
        );
        float moonIllumination = CelestialModel.moonIllumination(astronomicalTime);
        double altitude = Math.max(
            0.0,
            Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y - OVERWORLD_SEA_LEVEL
        );
        float spaceBlend = spaceTransitionStrength((float) altitude);
        float highAltitudeVisibility = smoothstep(1_024.0F, 4_096.0F, (float) altitude);
        float milkyWayVisibility = Math.max(nightBlend, highAltitudeVisibility);
        float airDensity = (float) Math.exp(-altitude / 8_500.0);
        float moonlightFade = 1.0F - moonIllumination * 0.55F * (1.0F - spaceBlend);
        return milkyWayVisibility * (1.0F - rain) * moonlightFade
            * (1.0F - airDensity * 0.15F) * Config.milkyWayBrightness;
    }

    private static int applyMilkyWay(
        int color,
        float elevation,
        float azimuth,
        CelestialModel.Direction galacticNorth,
        CelestialModel.Direction galacticCenter,
        float strength
    ) {
        float horizontal = (float) Math.cos(elevation);
        float viewX = (float) Math.cos(azimuth) * horizontal;
        float viewY = (float) Math.sin(elevation);
        float viewZ = (float) Math.sin(azimuth) * horizontal;
        float planeDot = viewX * (float) galacticNorth.east()
            + viewY * (float) galacticNorth.up()
            + viewZ * (float) galacticNorth.south();
        float galacticLatitude = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, planeDot)));
        float bandDistance = galacticLatitude / 0.30F;
        float band = (float) Math.exp(-bandDistance * bandDistance);
        float centerDot = viewX * (float) galacticCenter.east()
            + viewY * (float) galacticCenter.up()
            + viewZ * (float) galacticCenter.south();
        float core = 0.55F + 0.45F * Math.max(0.0F, centerDot);
        float alpha = Math.min(band * core * 0.10F * strength, 0.14F);
        if (alpha <= 0.0F) {
            return color;
        }

        float red = ((color >>> 16) & 0xFF) / 255.0F;
        float green = ((color >>> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;
        red = mix(red, 0.74F, alpha);
        green = mix(green, 0.76F, alpha);
        blue = mix(blue, 0.96F, alpha);
        return (toByte(red) << 16) | (toByte(green) << 8) | toByte(blue);
    }

    public static int moonPhase(ClientLevel level) {
        return CelestialModel.moonPhase(level.getDayTime());
    }

    public static void renderStars(Matrix4f pose, Matrix4f projection, ClientLevel level, float partialTick) {
        ensureLevel(level);
        double latitude = CelestialModel.latitudeRadians();
        if (starBuffer == null) {
            starBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        long catalogRevision = StarCatalog.revision();
        if (catalogRevision != cachedStarRevision) {
            starBuffer.upload(StarCatalog.buildMesh());
            cachedStarRevision = catalogRevision;
        }

        Matrix4f celestialToLocal = CelestialModel.celestialToLocalMatrix(
            level.getDayTime() + partialTick, latitude
        );
        float nightBrightness = level.getStarBrightness(partialTick);
        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float spaceBlend = spaceTransitionStrength(altitude);
        float spaceStarBlend = smoothstep(
            2_048.0F - OVERWORLD_SEA_LEVEL,
            4_096.0F - OVERWORLD_SEA_LEVEL,
            altitude
        );
        float spaceBrightness = spaceStarBlend * 0.75F;
        float sunY = (float) CelestialModel.sunDirection(
            level.getDayTime() + partialTick, latitude
        ).up();
        float twilightStars = (1.0F - smoothstep(-0.12F, 0.08F, sunY))
            * mix(0.20F, 0.30F, spaceBlend);
        float brightness = Math.max(nightBrightness, Math.max(spaceBrightness, twilightStars))
            * Config.starBrightness
            * starVisibility(level);
        if (brightness <= 0.001F) {
            return;
        }

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
            starBuffer.drawWithShader(
                pose,
                projection,
                LumisphericClient.getStarShader(
                    celestialToLocal,
                    (float) -Math.sin(geometricHorizonDip(altitude)),
                    brightness
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

    public static float starVisibility(ClientLevel level) {
        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        float rain = weatherStrength(level, cameraY, 1.0F);
        return (1.0F - 0.12F * moonIllumination(level)) * (1.0F - rain * 0.95F);
    }

    private static float auroraStrength(ClientLevel level, float night, float rain) {
        if (night < 0.65F || rain > 0.25F) {
            return 0.0F;
        }

        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        BlockPos position = BlockPos.containing(camera.getPosition());
        float temperature = level.getBiome(position).value().getBaseTemperature();
        if (temperature > 0.2F) {
            return 0.0F;
        }

        long day = Math.floorDiv(level.getDayTime(), 24000L);
        if (Math.floorMod(mixDay(day), 17) != 0) {
            return 0.0F;
        }

        float cold = 1.0F - smoothstep(-0.2F, 0.2F, temperature);
        float pulse = 0.72F + 0.28F * (float) Math.sin(level.getGameTime() * 0.004F);
        return cold * pulse * smoothstep(0.65F, 0.9F, night) * (1.0F - rain) * 0.72F;
    }

    private static float auroraBand(float elevation, float azimuth, long gameTime) {
        float wave = (float) Math.sin(azimuth * 2.4F + gameTime * 0.0003F
            + Math.sin(azimuth * 1.3F) * 1.5F);
        float center = 0.42F + wave * 0.06F;
        float distance = (elevation - center) / 0.13F;
        float curtains = 0.35F + 0.65F * (0.5F + 0.5F * (float) Math.sin(
            azimuth * 8.0F + elevation * 15.0F + gameTime * 0.0015F
        ));
        return (float) Math.exp(-distance * distance) * curtains;
    }

    private static Meteor currentMeteor(ClientLevel level, float rain) {
        if (rain > 0.2F) {
            return null;
        }

        long day = Math.floorDiv(level.getDayTime(), 24000L);
        int seed = mixDay(day);
        if (Math.floorMod(seed, 37) != 0) {
            return null;
        }

        float dayFraction = (Math.floorMod(level.getDayTime(), 24000L)) / 24000.0F;
        float start = 0.72F + ((seed >>> 8) & 0xFF) / 255.0F * 0.16F;
        float duration = 0.0035F;
        float progress = (dayFraction - start) / duration;
        if (progress < 0.0F || progress > 1.0F) {
            return null;
        }

        float startAzimuth = ((seed >>> 16) & 0xFFFF) / 65535.0F * (float) (Math.PI * 2.0);
        float startElevation = 0.28F + ((seed >>> 4) & 0xFF) / 255.0F * 0.62F;
        float azimuthDelta = (((seed >>> 24) & 0xFF) / 255.0F - 0.5F) * 0.65F;
        float elevationDelta = (((seed >>> 2) & 0xFF) / 255.0F - 0.5F) * 0.36F;
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
    ) {
        private float intensity(float elevation, float azimuth) {
            float tail = Math.max(0.0F, progress - 0.22F);
            float headAzimuth = startAzimuth + azimuthDelta * progress;
            float tailAzimuth = startAzimuth + azimuthDelta * tail;
            float headElevation = startElevation + elevationDelta * progress;
            float tailElevation = startElevation + elevationDelta * tail;
            float azimuthScale = (float) Math.cos((headElevation + tailElevation) * 0.5F);
            float dx = wrapRadians(azimuth - tailAzimuth) * azimuthScale;
            float dy = elevation - tailElevation;
            float vx = wrapRadians(headAzimuth - tailAzimuth) * azimuthScale;
            float vy = headElevation - tailElevation;
            float lengthSquared = vx * vx + vy * vy;
            float along = lengthSquared == 0.0F ? 0.0F : Math.max(0.0F, Math.min(1.0F, (dx * vx + dy * vy) / lengthSquared));
            float distanceX = dx - along * vx;
            float distanceY = dy - along * vy;
            float distance = (float) Math.sqrt(distanceX * distanceX + distanceY * distanceY);
            return (1.0F - smoothstep(0.018F, 0.055F, distance)) * (1.0F - progress * 0.45F);
        }
    }

    private static float wrapRadians(float angle) {
        while (angle > Math.PI) {
            angle -= (float) (Math.PI * 2.0);
        }
        while (angle < -Math.PI) {
            angle += (float) (Math.PI * 2.0);
        }
        return angle;
    }

    private static int toByte(float color) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, color)) * 255.0F);
    }

    private static float twilightAmount(float apparentSunElevation, float altitudeBlend) {
        float twilightStart = mix(-0.15F, -0.30F, altitudeBlend);
        float twilightPeak = mix(0.0F, -0.02F, altitudeBlend);
        return smoothstep(twilightStart, twilightPeak, apparentSunElevation)
            * (1.0F - smoothstep(0.06F, 0.22F, apparentSunElevation));
    }

    private static Twilight twilightPalette(float sunY, float altitude) {
        float opticalDepth = solarAirMass(sunY) - 1.0F;
        // Beer-Lambert-style attenuation removes short wavelengths faster along long solar paths.
        float redTransmission = (float) Math.exp(-0.04F * opticalDepth);
        float greenTransmission = (float) Math.exp(-0.13F * opticalDepth);
        float blueTransmission = (float) Math.exp(-0.38F * opticalDepth);
        float greenRatio = (float) Math.sqrt(greenTransmission / redTransmission);
        float blueRatio = (float) Math.sqrt(blueTransmission / redTransmission);
        float sunElevation = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, sunY)));
        float apparentSunElevation = sunElevation + geometricHorizonDip(altitude);
        float blueHour = smoothstep(0.0F, 0.12F, -apparentSunElevation);
        float warmRed = mix(0.96F, 0.76F, blueHour);
        float warmGreen = mix(0.28F + 0.38F * greenRatio, 0.34F, blueHour);
        float warmBlue = mix(0.08F + 0.30F * blueRatio, 0.48F, blueHour);
        float altitudeBlend = smoothstep(0.0F, 256.0F, altitude);
        return new Twilight(
            twilightAmount(apparentSunElevation, altitudeBlend) * Config.sunsetIntensity,
            warmRed,
            warmGreen,
            warmBlue,
            blueHour
        );
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

    private record Twilight(float strength, float red, float green, float blue, float blueHour) {}

    private static float horizonDip(double altitude) {
        double height = Math.max(0.0, altitude);
        float altitudeLayers = smoothstep(0.0F, 256.0F, (float) height);
        return geometricHorizonDip(height) + altitudeLayers * 0.025F;
    }

    private static float geometricHorizonDip(double altitude) {
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
