package dinner.dev.lumispheric;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.Objects;

public final class LumisphericClient {
    private static ShaderInstance cloudShader;
    private static ShaderInstance starShader;
    private static ShaderInstance milkyWayShader;

    private LumisphericClient() {}

    static void initialize(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(LumisphericClient::onRegisterShaders);
        modContainer.registerExtensionPoint(
            IConfigScreenFactory.class,
            (container, parent) -> new LumisphericConfigScreen(parent)
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
                shader -> cloudShader = shader
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load Lumispheric's cloud fade shader.", exception);
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
            throw new IllegalStateException("Failed to load Lumispheric's star shader.", exception);
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
    }

    public static ShaderInstance getCloudShader(float rain, float thunder) {
        ShaderInstance shader = Objects.requireNonNull(
            cloudShader, "Lumispheric cloud shader has not been registered"
        );
        setCloudWeather(shader, "CloudRain", rain);
        setCloudWeather(shader, "CloudThunder", thunder);
        return shader;
    }

    public static ShaderInstance getStarShader(Matrix4f celestialToLocal, float observerAltitude) {
        ShaderInstance shader = Objects.requireNonNull(
            starShader, "Lumispheric star shader has not been registered"
        );
        requireUniform(shader, "CelestialToLocal").set(celestialToLocal);
        requireUniform(shader, "ObserverAltitude").set(observerAltitude);
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
