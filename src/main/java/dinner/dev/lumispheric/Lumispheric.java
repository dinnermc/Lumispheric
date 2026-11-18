package dinner.dev.lumispheric;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(Lumispheric.MODID)
public class Lumispheric {
    public static final String MODID = "lumispheric";

    public Lumispheric(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        modEventBus.addListener(Config::onLoad);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            LumisphericClient.initialize(modEventBus, modContainer);
        }
    }
}
