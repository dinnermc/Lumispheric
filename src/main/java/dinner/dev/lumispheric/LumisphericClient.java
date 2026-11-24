package dinner.dev.lumispheric;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.Objects;

public final class LumisphericClient {
    private static ShaderInstance cloudShader;
    private static Uniform cloudRainUniform;
    private static Uniform cloudThunderUniform;
    private static Uniform cloudSunDirUniform;
    private static Uniform cloudMoonDirUniform;
    private static Uniform cloudDeckOpacityUniform;
    private static Uniform cloudTwilightColorUniform;
    private static Uniform cloudMoonParamsUniform;

    private static ShaderInstance milkyWayShader;
    private static Uniform mwGalacticNorthUniform;
    private static Uniform mwGalacticCenterUniform;
    private static Uniform mwStrengthUniform;
    private static Uniform mwParamsUniform;

    private static ShaderInstance planetShader;
    private static Uniform plSunDirUniform;
    private static Uniform plMoonDirUniform;
    private static Uniform plSkyParams0Uniform;
    private static Uniform plSkyParams1Uniform;
    private static Uniform plSkyParams2Uniform;
    private static Uniform plTwilightColorUniform;
    private static Uniform plTwilightExtraUniform;
    private static Uniform plTimeParamsUniform;
    private static final Uniform[] plMeteorStartUniforms = new Uniform[4];
    private static final Uniform[] plMeteorStateUniforms = new Uniform[4];
    private static Uniform plPlanetParamsUniform;
    private static Uniform plColorModulatorUniform;

    private static ShaderInstance starShader;
    private static Uniform starCelestialToLocalUniform;
    private static Uniform starHorizonLevelUniform;
    private static Uniform starHorizonFadeWidthUniform;
    private static Uniform starColorModulatorUniform;
    private static Uniform starAtmosphereUniform;

    private static ShaderInstance celestialShader;
    private static Uniform celColorModulatorUniform;
    private static Uniform celGlowScaleUniform;
    private static Uniform celLunarShadowOffsetUniform;
    private static Uniform celMoonPassUniform;
    private static Uniform celPlanetPassUniform;
    private static Uniform celPlanetTypeUniform;
    private static Uniform celBodyLightDirUniform;
    private static Uniform celStyleParamsUniform;

    private static ShaderInstance endSkyShader;
    private static Uniform endBlackHoleDirUniform;
    private static Uniform endParamsUniform;
    private static Uniform endColorModulatorUniform;

    private static final float STAR_HORIZON_FADE_WIDTH = (float) Math.sin(Math.toRadians(0.6));

    private LumisphericClient() {}

    static void initialize(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(LumisphericClient::onRegisterShaders);
        modEventBus.addListener(LumisphericClient::onRegisterReloadListeners);
        modEventBus.addListener(LumisphericClient::onRegisterGuiLayers);
        modContainer.registerExtensionPoint(
            IConfigScreenFactory.class,
            (container, parent) -> new LumisphericConfigScreen(parent)
        );
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(StarCatalog.INSTANCE);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
            ResourceLocation.fromNamespaceAndPath(Lumispheric.MODID, "spyglass_hud"),
            SpyglassHud::render
        );
    }

    private static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                new ShaderInstance(
                    event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Lumispheric.MODID, "clouds"),
                    DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
                ),
                shader -> {
                    cloudShader = shader;
                    cloudRainUniform = requireUniform(shader, "CloudRain");
                    cloudThunderUniform = requireUniform(shader, "CloudThunder");
                    cloudSunDirUniform = requireUniform(shader, "CloudSunDirection");
                    cloudMoonDirUniform = requireUniform(shader, "CloudMoonDirection");
                    cloudDeckOpacityUniform = requireUniform(shader, "CloudDeckOpacity");
                    cloudTwilightColorUniform = requireUniform(shader, "CloudTwilightColor");
                    cloudMoonParamsUniform = requireUniform(shader, "CloudMoonParams");
                }
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Lumispheric's cloud fade shader.", exception);
        }
        try {
            event.registerShader(
                new ShaderInstance(
                    event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Lumispheric.MODID, "milky_way"),
                    DefaultVertexFormat.POSITION_COLOR
                ),
                shader -> {
                    milkyWayShader = shader;
                    mwGalacticNorthUniform = requireUniform(shader, "GalacticNorth");
                    mwGalacticCenterUniform = requireUniform(shader, "GalacticCenter");
                    mwStrengthUniform = requireUniform(shader, "MilkyWayStrength");
                    mwParamsUniform = requireUniform(shader, "MilkyWayParams");
                }
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Lumispheric's Milky Way shader.", exception);
        }
        try {
            event.registerShader(
                new ShaderInstance(
                    event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Lumispheric.MODID, "planet"),
                    DefaultVertexFormat.POSITION_COLOR
                ),
                shader -> {
                    planetShader = shader;
                    plSunDirUniform = requireUniform(shader, "SunDirection");
                    plMoonDirUniform = requireUniform(shader, "MoonDirection");
                    plSkyParams0Uniform = requireUniform(shader, "SkyParams0");
                    plSkyParams1Uniform = requireUniform(shader, "SkyParams1");
                    plSkyParams2Uniform = requireUniform(shader, "SkyParams2");
                    plTwilightColorUniform = requireUniform(shader, "TwilightColor");
                    plTwilightExtraUniform = requireUniform(shader, "TwilightExtra");
                    plTimeParamsUniform = requireUniform(shader, "TimeParams");
                    for (int i = 0; i < 4; i++) {
                        plMeteorStartUniforms[i] = requireUniform(shader, "Meteor" + i + "Start");
                        plMeteorStateUniforms[i] = requireUniform(shader, "Meteor" + i + "State");
                    }
                    plPlanetParamsUniform = requireUniform(shader, "PlanetParams");
                    plColorModulatorUniform = requireUniform(shader, "ColorModulator");
                }
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Lumispheric's procedural sky and planet shader.", exception);
        }
        try {
            event.registerShader(
                new ShaderInstance(
                    event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Lumispheric.MODID, "stars"),
                    DefaultVertexFormat.POSITION_COLOR
                ),
                shader -> {
                    starShader = shader;
                    starCelestialToLocalUniform = requireUniform(shader, "CelestialToLocal");
                    starHorizonLevelUniform = requireUniform(shader, "HorizonLevel");
                    starHorizonFadeWidthUniform = requireUniform(shader, "HorizonFadeWidth");
                    starColorModulatorUniform = requireUniform(shader, "ColorModulator");
                    starAtmosphereUniform = requireUniform(shader, "StarAtmosphere");
                }
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Lumispheric's star horizon shader.", exception);
        }
        try {
            event.registerShader(
                new ShaderInstance(
                    event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Lumispheric.MODID, "celestial"),
                    DefaultVertexFormat.POSITION_TEX_COLOR
                ),
                shader -> {
                    celestialShader = shader;
                    celColorModulatorUniform = requireUniform(shader, "ColorModulator");
                    celGlowScaleUniform = requireUniform(shader, "GlowScale");
                    celLunarShadowOffsetUniform = requireUniform(shader, "LunarShadowOffset");
                    celMoonPassUniform = requireUniform(shader, "MoonPass");
                    celPlanetPassUniform = requireUniform(shader, "PlanetPass");
                    celPlanetTypeUniform = requireUniform(shader, "PlanetType");
                    celBodyLightDirUniform = requireUniform(shader, "BodyLightDir");
                    celStyleParamsUniform = requireUniform(shader, "StyleParams");
                }
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Lumispheric's celestial shader.", exception);
        }
        try {
            event.registerShader(
                new ShaderInstance(
                    event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Lumispheric.MODID, "end_sky"),
                    DefaultVertexFormat.POSITION_COLOR
                ),
                shader -> {
                    endSkyShader = shader;
                    endBlackHoleDirUniform = requireUniform(shader, "BlackHoleDir");
                    endParamsUniform = requireUniform(shader, "EndParams");
                    endColorModulatorUniform = requireUniform(shader, "ColorModulator");
                }
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Lumispheric's End sky shader.", exception);
        }
    }

    public static ShaderInstance getCloudShader(
        float rain,
        float thunder,
        CelestialModel.Direction sun,
        CelestialModel.Direction moon,
        float deckOpacity,
        float twilightRed,
        float twilightGreen,
        float twilightBlue,
        float twilightStrength,
        float moonIllumination,
        float nightStrength,
        float sunElevation,
        float biomeDust
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            cloudShader, "Lumispheric cloud shader has not been registered"
        );
        cloudRainUniform.set(rain);
        cloudThunderUniform.set(thunder);
        cloudSunDirUniform.set((float) sun.east(), (float) sun.up(), (float) sun.south());
        cloudMoonDirUniform.set((float) moon.east(), (float) moon.up(), (float) moon.south());
        cloudDeckOpacityUniform.set(deckOpacity);
        cloudTwilightColorUniform.set(twilightRed, twilightGreen, twilightBlue, twilightStrength);
        cloudMoonParamsUniform.set(moonIllumination, nightStrength, sunElevation, biomeDust);
        return shader;
    }

    public static ShaderInstance getStarShader(
        Matrix4f celestialToLocal,
        float horizonLevel,
        float brightness,
        float gameTimeSeconds,
        float spaceBlend
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            starShader, "Lumispheric star shader has not been registered"
        );
        starCelestialToLocalUniform.set(celestialToLocal);
        starHorizonLevelUniform.set(horizonLevel);
        starHorizonFadeWidthUniform.set(STAR_HORIZON_FADE_WIDTH);
        starColorModulatorUniform.set(brightness, brightness, brightness, 1.0F);
        starAtmosphereUniform.set(gameTimeSeconds, spaceBlend, 0.0F, 0.0F);
        return shader;
    }

    public static ShaderInstance getPlanetShader(
        CelestialModel.Direction sun,
        CelestialModel.Direction moon,
        float sunElevation,
        float moonIllumination,
        float rain,
        float eclipseStrength,
        float horizonDip,
        float geometricDip,
        float cameraHeight,
        float spaceBlend,
        float sunsetIntensity,
        float mieIntensity,
        float biomeDust,
        float biomeClearness,
        float twilightRed,
        float twilightGreen,
        float twilightBlue,
        float twilightStrength,
        float blueHour,
        float auroraStrength,
        float auroraHemisphere,
        float pixelatedStyle,
        SkyRenderer.MeteorSlot[] meteors,
        float gameTimeSeconds,
        float cloudWindAngle,
        float planetRadius,
        float planetBlend,
        float planetRotationAroundZ,
        float planetRotationAroundX
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            planetShader, "Lumispheric planet shader has not been registered"
        );
        plSunDirUniform.set((float) sun.east(), (float) sun.up(), (float) sun.south());
        plMoonDirUniform.set((float) moon.east(), (float) moon.up(), (float) moon.south());
        plSkyParams0Uniform.set(sunElevation, moonIllumination, rain, eclipseStrength);
        plSkyParams1Uniform.set(horizonDip, geometricDip, cameraHeight, spaceBlend);
        plSkyParams2Uniform.set(sunsetIntensity, mieIntensity, biomeDust, biomeClearness);
        plTwilightColorUniform.set(twilightRed, twilightGreen, twilightBlue, twilightStrength);
        plTwilightExtraUniform.set(blueHour, auroraStrength, auroraHemisphere, pixelatedStyle);
        plTimeParamsUniform.set(gameTimeSeconds, cloudWindAngle, 0.0F, 0.0F);
        for (int i = 0; i < 4; i++) {
            SkyRenderer.MeteorSlot slot = meteors != null && i < meteors.length ? meteors[i] : null;
            if (slot != null) {
                plMeteorStartUniforms[i].set(
                    slot.startAzimuth(), slot.startElevation(), slot.azimuthDelta(), slot.elevationDelta()
                );
                plMeteorStateUniforms[i].set(slot.progress(), 1.0F, slot.colorType(), 0.0F);
            } else {
                plMeteorStartUniforms[i].set(0.0F, 0.5F, 0.0F, 0.0F);
                plMeteorStateUniforms[i].set(0.0F, 0.0F, 0.0F, 0.0F);
            }
        }
        plPlanetParamsUniform.set(planetRadius, planetBlend, planetRotationAroundZ, planetRotationAroundX);
        plColorModulatorUniform.set(1.0F, 1.0F, 1.0F, 1.0F);
        return shader;
    }

    public static ShaderInstance getMilkyWayShader(
        CelestialModel.Direction galacticNorth,
        CelestialModel.Direction galacticCenter,
        float strength,
        float geometricHorizonDip,
        float spaceBlend,
        float pixelatedStyle,
        float planetBlend
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            milkyWayShader, "Lumispheric Milky Way shader has not been registered"
        );
        mwGalacticNorthUniform.set(
            (float) galacticNorth.east(), (float) galacticNorth.up(), (float) galacticNorth.south()
        );
        mwGalacticCenterUniform.set(
            (float) galacticCenter.east(), (float) galacticCenter.up(), (float) galacticCenter.south()
        );
        mwStrengthUniform.set(strength);
        mwParamsUniform.set(geometricHorizonDip, spaceBlend, pixelatedStyle, planetBlend);
        return shader;
    }

    public static ShaderInstance getCelestialShader(
        float red,
        float green,
        float blue,
        float alpha,
        float glowScale,
        float lunarShadowOffsetX,
        float lunarShadowOffsetY,
        boolean moonPass,
        float planetMode,
        float planetType,
        Vector3f bodyLightDir,
        float pixelatedStyle,
        float earthshine,
        float spyglassZoom,
        float solarEclipse
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            celestialShader, "Lumispheric celestial shader has not been registered"
        );
        shader.setSampler("Sampler0", RenderSystem.getShaderTexture(0));
        celColorModulatorUniform.set(red, green, blue, alpha);
        celGlowScaleUniform.set(glowScale);
        celLunarShadowOffsetUniform.set(lunarShadowOffsetX, lunarShadowOffsetY);
        celMoonPassUniform.set(moonPass ? 1.0F : 0.0F);
        celPlanetPassUniform.set(planetMode);
        celPlanetTypeUniform.set(planetType);
        celBodyLightDirUniform.set(bodyLightDir.x, bodyLightDir.y, bodyLightDir.z);
        celStyleParamsUniform.set(pixelatedStyle, earthshine, spyglassZoom, solarEclipse);
        return shader;
    }

    public static ShaderInstance getEndSkyShader(
        Vector3f blackHoleDirection,
        float gameTimeSeconds,
        float pixelatedStyle
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            endSkyShader, "Lumispheric End sky shader has not been registered"
        );
        endBlackHoleDirUniform.set(blackHoleDirection.x, blackHoleDirection.y, blackHoleDirection.z);
        endParamsUniform.set(gameTimeSeconds, pixelatedStyle, 0.0F, 0.0F);
        endColorModulatorUniform.set(1.0F, 1.0F, 1.0F, 1.0F);
        return shader;
    }

    private static Uniform requireUniform(ShaderInstance shader, String name) {
        Uniform uniform = shader.getUniform(name);
        if (uniform == null) {
            throw new IllegalStateException("Lumispheric shader is missing the " + name + " uniform");
        }
        return uniform;
    }
}
