package dinner.dev.lumispheric;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.Objects;

public final class LumisphericClient {
    private static ShaderInstance cloudShader;
    private static ShaderInstance milkyWayShader;
    private static ShaderInstance planetShader;
    private static ShaderInstance starShader;
    private static ShaderInstance celestialShader;

    private LumisphericClient() {}

    static void initialize(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(LumisphericClient::onRegisterShaders);
        modEventBus.addListener(LumisphericClient::onRegisterReloadListeners);
        modContainer.registerExtensionPoint(
            IConfigScreenFactory.class,
            (container, parent) -> new LumisphericConfigScreen(parent)
        );
    }

    private static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(StarCatalog.INSTANCE);
    }

    private static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                new ShaderInstance(
                    event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Lumispheric.MODID, "clouds"),
                    DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
                ),
                shader -> cloudShader = shader
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
                shader -> milkyWayShader = shader
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
                shader -> planetShader = shader
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Lumispheric's procedural planet shader.", exception);
        }
        try {
            event.registerShader(
                new ShaderInstance(
                    event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Lumispheric.MODID, "stars"),
                    DefaultVertexFormat.POSITION_COLOR
                ),
                shader -> starShader = shader
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
                shader -> celestialShader = shader
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Lumispheric's celestial shader.", exception);
        }
    }

    public static ShaderInstance getCloudShader(
        float rain, float thunder, CelestialModel.Direction sun, float deckOpacity
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            cloudShader, "Lumispheric cloud shader has not been registered"
        );
        setCloudWeather(shader, "CloudRain", rain);
        setCloudWeather(shader, "CloudThunder", thunder);
        requireUniform(shader, "CloudSunDirection").set(
            (float) sun.east(),
            (float) sun.up(),
            (float) sun.south()
        );
        requireUniform(shader, "CloudDeckOpacity").set(deckOpacity);
        return shader;
    }

    public static ShaderInstance getStarShader(Matrix4f celestialToLocal, float horizonLevel, float brightness) {
        ShaderInstance shader = Objects.requireNonNull(
            starShader, "Lumispheric star shader has not been registered"
        );
        requireUniform(shader, "CelestialToLocal").set(celestialToLocal);
        requireUniform(shader, "HorizonLevel").set(horizonLevel);
        requireUniform(shader, "HorizonFadeWidth").set((float) Math.sin(Math.toRadians(0.6)));
        requireUniform(shader, "ColorModulator").set(brightness, brightness, brightness, 1.0F);
        return shader;
    }

    public static ShaderInstance getPlanetShader(
        CelestialModel.Direction sun,
        CelestialModel.Direction moon,
        float sunElevation,
        float moonIllumination,
        float cloudWindAngle,
        float planetRadius,
        float cameraHeight,
        float horizonDip,
        float planetBlend,
        float planetRotationAroundZ,
        float planetRotationAroundX
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            planetShader, "Lumispheric planet shader has not been registered"
        );
        requireUniform(shader, "SunDirection").set(
            (float) sun.east(),
            (float) sun.up(),
            (float) sun.south()
        );
        requireUniform(shader, "MoonDirection").set(
            (float) moon.east(),
            (float) moon.up(),
            (float) moon.south()
        );
        requireUniform(shader, "SunElevation").set(sunElevation);
        requireUniform(shader, "MoonIllumination").set(moonIllumination);
        requireUniform(shader, "CloudWindAngle").set(cloudWindAngle);
        requireUniform(shader, "PlanetRadius").set(planetRadius);
        requireUniform(shader, "CameraHeight").set(cameraHeight);
        requireUniform(shader, "HorizonDip").set(horizonDip);
        requireUniform(shader, "PlanetBlend").set(planetBlend);
        requireUniform(shader, "PlanetRotation").set(planetRotationAroundZ, planetRotationAroundX);
        requireUniform(shader, "ColorModulator").set(1.0F, 1.0F, 1.0F, 1.0F);
        return shader;
    }

    public static ShaderInstance getMilkyWayShader(
        CelestialModel.Direction galacticNorth,
        CelestialModel.Direction galacticCenter,
        float strength
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            milkyWayShader, "Lumispheric Milky Way shader has not been registered"
        );
        requireUniform(shader, "GalacticNorth").set(
            (float) galacticNorth.east(),
            (float) galacticNorth.up(),
            (float) galacticNorth.south()
        );
        requireUniform(shader, "GalacticCenter").set(
            (float) galacticCenter.east(),
            (float) galacticCenter.up(),
            (float) galacticCenter.south()
        );
        requireUniform(shader, "MilkyWayStrength").set(strength);
        return shader;
    }

    public static ShaderInstance getCelestialShader(
        float red, float green, float blue, float alpha, float glowScale,
        float lunarShadowOffsetX, float lunarShadowOffsetY, boolean moonPass
    ) {
        ShaderInstance shader = Objects.requireNonNull(
            celestialShader, "Lumispheric celestial shader has not been registered"
        );
        shader.setSampler("Sampler0", RenderSystem.getShaderTexture(0));
        requireUniform(shader, "ColorModulator").set(red, green, blue, alpha);
        requireUniform(shader, "GlowScale").set(glowScale);
        requireUniform(shader, "LunarShadowOffset").set(lunarShadowOffsetX, lunarShadowOffsetY);
        requireUniform(shader, "MoonPass").set(moonPass ? 1.0F : 0.0F);
        return shader;
    }

    private static Uniform requireUniform(ShaderInstance shader, String name) {
        Uniform uniform = shader.getUniform(name);
        if (uniform == null) {
            throw new IllegalStateException("Lumispheric shader is missing the " + name + " uniform");
        }
        return uniform;
    }

    private static void setCloudWeather(ShaderInstance shader, String name, float value) {
        requireUniform(shader, name).set(value);
    }
}
