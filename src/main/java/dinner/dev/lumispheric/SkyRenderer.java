package dinner.dev.lumispheric;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

public final class SkyRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Lumispheric");
    private static final int AZIMUTH_SEGMENTS = 64;
    private static final int ELEVATION_SEGMENTS = 48;
    private static final int AZIMUTH_STRIDE = AZIMUTH_SEGMENTS + 1;
    private static final int FOG_AZIMUTH_SAMPLES = 16;
    private static final int SKY_VERTEX_COUNT = (ELEVATION_SEGMENTS + 1) * AZIMUTH_STRIDE;
    private static final float[] SKY_POSITIONS = new float[SKY_VERTEX_COUNT * 3];
    private static final int[] SKY_COLORS = new int[SKY_VERTEX_COUNT];
    // Level#getSeaLevel is deprecated and hardcodes 63; this renderer only handles the Overworld.
    private static final int OVERWORLD_SEA_LEVEL = 63;
    private static final double PLANET_RADIUS = 6_371_000.0;
    private static VertexBuffer skyBuffer;
    private static VertexBuffer starBuffer;
    private static int cachedSkyKey = Integer.MIN_VALUE;
    private static int cachedAltitude = Integer.MIN_VALUE;
    private static double cachedSkyLatitude = Double.NaN;
    private static Method irisShaderCheck;
    private static Object irisApi;
    private static boolean irisLookupFailed;
    private static long shaderCheckTime;
    private static boolean shadersActive;
    private static ClientLevel cachedFogLevel;
    private static long cachedFogGameTime = Long.MIN_VALUE;
    private static long cachedFogDayTime = Long.MIN_VALUE;
    private static int cachedFogAltitude = Integer.MIN_VALUE;
    private static int cachedFogWeather = Integer.MIN_VALUE;
    private static double cachedFogLatitude = Double.NaN;
    private static float cachedFogSunsetIntensity = Float.NaN;
    private static float cachedFogMieIntensity = Float.NaN;
    private static float cachedFogMilkyWayBrightness = Float.NaN;
    private static Vec3 cachedFogColor;

    private SkyRenderer() {}

    public static boolean shouldRender(ClientLevel level) {
        return Config.enabled && level != null
            && level.dimension() == net.minecraft.world.level.Level.OVERWORLD
            && level.effects().skyType() == net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.NORMAL
            && !areShadersActive();
    }

    public static void render(Matrix4f pose, Matrix4f projection, float partialTick, ClientLevel level) {
        float rain = Math.max(level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
        double cameraY = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
        int altitude = Math.max(0, (int) Math.floor(cameraY - OVERWORLD_SEA_LEVEL));
        float horizonDip = horizonDip(altitude);
        int key = Long.hashCode(level.getGameTime() * 31L + level.getDayTime())
            ^ (Math.round(rain * 100.0F) << 8)
            ^ (Math.round(Config.sunsetIntensity * 100.0F) << 16)
            ^ (Math.round(Config.mieIntensity * 100.0F) << 24);

        if (skyBuffer == null || key != cachedSkyKey || altitude != cachedAltitude
            || Double.compare(Config.latitude, cachedSkyLatitude) != 0) {
            rebuild(level, partialTick, rain, horizonDip, key, altitude);
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        skyBuffer.drawWithShader(pose, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        float milkyWayStrength = milkyWayStrength(level, partialTick);
        if (milkyWayStrength > 0.001F) {
            double astronomicalTime = level.getDayTime() + partialTick;
            double latitude = CelestialModel.latitudeRadians();
            ShaderInstance milkyWayShader = LumisphericClient.getMilkyWayShader(
                CelestialModel.galacticNorthDirection(astronomicalTime, latitude),
                CelestialModel.galacticCenterDirection(astronomicalTime, latitude),
                milkyWayStrength
            );
            skyBuffer.drawWithShader(pose, projection, milkyWayShader);
            VertexBuffer.unbind();
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void rebuild(
        ClientLevel level, float partialTick, float rain, float horizonDip, int key, int altitude
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
        float warmAmount = twilightAmount(sunY) * Config.sunsetIntensity;
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
                SKY_COLORS[index] = atmosphereColor(
                    angle, azimuthAngle, night, rain, warmAmount, sunX, sunY, sunZ, horizonDip,
                    moonIllumination, eclipse, aurora, meteor, gameTime, altitude
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
        float rain = Math.max(level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
        double latitude = CelestialModel.latitudeRadians();
        CelestialModel.Direction sun = CelestialModel.sunDirection(astronomicalTime, latitude);
        float eclipse = CelestialModel.eclipseStrength(astronomicalTime, latitude)
            * (1.0F - rain);
        float sunX = (float) sun.east();
        float sunY = (float) sun.up();
        float sunZ = (float) sun.south();
        float warm = twilightAmount(sunY) * Config.sunsetIntensity;
        float horizonDip = horizonDip(Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL));
        int color = atmosphereColor(
            -horizonDip, azimuth, night, rain, warm, sunX, sunY, sunZ, horizonDip,
            CelestialModel.moonIllumination(astronomicalTime), eclipse, 0.0F, null, level.getGameTime(),
            (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL)
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
        long gameTime = level.getGameTime();
        long dayTime = level.getDayTime();
        int altitude = (int) Math.floor(cameraY - OVERWORLD_SEA_LEVEL);
        int weather = Float.floatToIntBits(
            Math.max(level.getRainLevel(fogPartialTick), level.getThunderLevel(fogPartialTick))
        );
        if (cachedFogColor != null
            && cachedFogLevel == level
            && cachedFogGameTime == gameTime
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
        cachedFogGameTime = gameTime;
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
        float elevation, float azimuth, float night, float rain, float warmAmount, float sunX, float sunY,
        float sunZ, float horizonDip, float moonIllumination, float eclipse, float aurora, Meteor meteor, long gameTime,
        float altitude
    ) {
        float skyElevation = Math.max(0.0F, elevation + horizonDip);
        float altitudeBlend = smoothstep(0.0F, 256.0F, altitude);
        float horizonSharpness = mix(2.8F, 3.8F, altitudeBlend);
        float horizon = (float) Math.exp(-skyElevation * horizonSharpness);
        float red = mix(0.15F, 0.60F, horizon);
        float green = mix(0.28F, 0.74F, horizon);
        float blue = mix(0.64F, 0.89F, horizon);

        float nightBlend = Math.max(Math.min(night * 2.0F, 1.0F), 1.0F - smoothstep(-0.28F, 0.03F, sunY));
        red = mix(red, mix(0.006F, 0.018F, horizon), nightBlend);
        green = mix(green, mix(0.009F, 0.026F, horizon), nightBlend);
        blue = mix(blue, mix(0.018F, 0.040F, horizon), nightBlend);

        float upperAtmosphere = altitudeBlend * smoothstep(0.08F, 0.72F, skyElevation);
        red = mix(red, mix(0.065F, 0.006F, nightBlend), upperAtmosphere * 0.82F);
        green = mix(green, mix(0.16F, 0.008F, nightBlend), upperAtmosphere * 0.82F);
        blue = mix(blue, mix(0.42F, 0.020F, nightBlend), upperAtmosphere * 0.82F);

        float lunarGlow = moonIllumination * nightBlend * (1.0F - rain) * 0.012F;
        red += lunarGlow * 0.35F;
        green += lunarGlow * 0.65F;
        blue += lunarGlow;

        float horizontal = (float) Math.cos(elevation);
        float viewX = (float) Math.cos(azimuth) * horizontal;
        float viewY = (float) Math.sin(elevation);
        float viewZ = (float) Math.sin(azimuth) * horizontal;
        float sunDot = Math.max(-1.0F, Math.min(1.0F, viewX * sunX + viewY * sunY + viewZ * sunZ));
        float sunAngle = (float) Math.acos(sunDot);
        float rayleighPhase = 0.75F * (1.0F + sunDot * sunDot);
        float rayleighScatter = (1.0F - nightBlend) * (1.0F - rain)
            * rayleighPhase * (0.35F + 0.65F * horizon);
        red += rayleighScatter * 0.012F;
        green += rayleighScatter * 0.035F;
        blue += rayleighScatter * 0.085F;

        float solarOpticalDepth = solarAirMass(sunY) - 1.0F;
        float solarGreen = (float) Math.exp(-0.12F * solarOpticalDepth);
        float solarBlue = (float) Math.exp(-0.34F * solarOpticalDepth);
        float warmRed = 0.98F;
        float warmGreen = 0.62F * (0.42F + 0.58F * solarGreen);
        float warmBlue = 0.44F * (0.14F + 0.86F * solarBlue);
        float wideGlow = (float) Math.exp(-(sunAngle * sunAngle) / 0.55F) * warmAmount;
        float coreGlow = (float) Math.exp(-(sunAngle * sunAngle) / 0.12F) * warmAmount;
        float twilight = 1.0F - rain * 0.75F;

        float horizonTwilight = warmAmount * horizon * (0.18F + 0.26F * Math.max(0.0F, sunDot)) * twilight;
        red = mix(red, 0.76F, horizonTwilight);
        green = mix(green, 0.34F + solarGreen * 0.12F, horizonTwilight);
        blue = mix(blue, 0.24F + solarBlue * 0.12F, horizonTwilight);

        float outer = wideGlow * twilight * 0.48F;
        float core = coreGlow * twilight * 0.40F;
        red = mix(red, 0.88F, outer);
        green = mix(green, warmGreen + 0.08F, outer);
        blue = mix(blue, warmBlue + 0.08F, outer);
        red = mix(red, warmRed, core);
        green = mix(green, warmGreen + 0.12F, core);
        blue = mix(blue, warmBlue + 0.13F, core);

        float mieAsymmetry = 0.78F;
        float mieDenominator = 1.0F + mieAsymmetry * mieAsymmetry - 2.0F * mieAsymmetry * sunDot;
        float mieRatio = (1.0F - mieAsymmetry) * (1.0F - mieAsymmetry) / mieDenominator;
        float miePhase = mieRatio * (float) Math.sqrt(mieRatio);
        float mieStrength = miePhase * Config.mieIntensity * (1.0F - nightBlend)
            * (1.0F - rain * 0.75F) * 0.16F * (0.35F + 0.65F * warmAmount);
        float mieGreen = mix(0.92F, warmGreen + 0.20F, warmAmount);
        float mieBlue = mix(0.82F, warmBlue + 0.20F, warmAmount);
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
        return (toByte(red) << 16) | (toByte(green) << 8) | toByte(blue);
    }

    private static float milkyWayStrength(ClientLevel level, float partialTick) {
        float rain = Math.max(level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
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
        float airDensity = (float) Math.exp(-altitude / 8_500.0);
        return nightBlend * (1.0F - rain) * (1.0F - moonIllumination * 0.55F)
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
        float bandDistance = galacticLatitude / 0.15F;
        float band = (float) Math.exp(-bandDistance * bandDistance);
        float centerDot = viewX * (float) galacticCenter.east()
            + viewY * (float) galacticCenter.up()
            + viewZ * (float) galacticCenter.south();
        float core = 0.55F + 0.45F * Math.max(0.0F, centerDot);
        float alpha = band * core * strength * 0.16F;
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
        double latitude = CelestialModel.latitudeRadians();
        if (starBuffer == null) {
            starBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            starBuffer.upload(StarCatalog.buildMesh());
        }

        Matrix4f celestialToLocal = CelestialModel.celestialToLocalMatrix(
            level.getDayTime() + partialTick,
            latitude
        );
        float observerAltitude = (float) Math.max(
            0.0,
            Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y - OVERWORLD_SEA_LEVEL
        );
        ShaderInstance shader = LumisphericClient.getStarShader(celestialToLocal, observerAltitude);
        starBuffer.drawWithShader(pose, projection, shader);
        VertexBuffer.unbind();
    }

    public static float moonIllumination(ClientLevel level) {
        return CelestialModel.moonIllumination(level.getDayTime());
    }

    public static float eclipseStrength(ClientLevel level) {
        return CelestialModel.eclipseStrength(level.getDayTime(), CelestialModel.latitudeRadians());
    }

    public static float starVisibility(ClientLevel level) {
        float rain = Math.max(level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
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

    private static float twilightAmount(float sunY) {
        float sunElevation = (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, sunY)));
        return smoothstep(-0.15F, 0.0F, sunElevation)
            * (1.0F - smoothstep(0.06F, 0.22F, sunElevation));
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

    private static float horizonDip(double altitude) {
        double height = Math.max(0.0, altitude);
        float geometricDip = (float) Math.acos(PLANET_RADIUS / (PLANET_RADIUS + height));
        float altitudeLayers = smoothstep(0.0F, 256.0F, (float) height);
        return geometricDip + altitudeLayers * 0.025F;
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
