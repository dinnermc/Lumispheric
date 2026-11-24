package dinner.dev.lumispheric;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
        .comment("Enable Lumispheric's custom Overworld sky.")
        .define("enabled", true);
    private static final ModConfigSpec.DoubleValue SUNSET_INTENSITY = BUILDER
        .comment("Intensity of the warm sunrise and sunset colors.")
        .defineInRange("sunsetIntensity", 0.85, 0.0, 1.0);
    private static final ModConfigSpec.DoubleValue STAR_BRIGHTNESS = BUILDER
        .comment("Brightness multiplier for the star field.")
        .defineInRange("starBrightness", 1.0, 0.0, 2.0);
    private static final ModConfigSpec.DoubleValue MILKY_WAY_BRIGHTNESS = BUILDER
        .comment("Brightness multiplier for the Milky Way.")
        .defineInRange("milkyWayBrightness", 1.0, 0.0, 2.0);
    private static final ModConfigSpec.DoubleValue MIE_INTENSITY = BUILDER
        .comment("Intensity of the atmospheric aerosol halo around the Sun.")
        .defineInRange("mieIntensity", 0.7, 0.0, 1.0);
    private static final ModConfigSpec.DoubleValue LATITUDE = BUILDER
        .comment("Observer latitude used for the celestial sky.")
        .defineInRange("latitude", 40.0, -90.0, 90.0);
    private static final ModConfigSpec.DoubleValue CLOUD_DISTANCE = BUILDER
        .comment("How far beyond vanilla the cloud mesh extends.")
        .defineInRange("cloudDistance", 1.0, 0.0, 1.0);
    private static final ModConfigSpec.DoubleValue CELESTIAL_SIZE = BUILDER
        .comment("Relative size of the Sun and Moon.")
        .defineInRange("celestialSize", 0.65, 0.25, 1.0);
    private static final ModConfigSpec.DoubleValue YEAR_LENGTH_DAYS = BUILDER
        .comment("Length of an astronomical year in Minecraft days (controls seasonal Sun path and planetary motion).")
        .defineInRange("yearLengthDays", 48.0, 16.0, 365.2422);
    private static final ModConfigSpec.BooleanValue PIXELATED_STYLE = BUILDER
        .comment("Use Minecraft-style pixelated shading for spyglass planets, Milky Way, auroras, and high-altitude planet.")
        .define("pixelatedStyle", true);
    private static final ModConfigSpec.BooleanValue END_SKY_ENABLED = BUILDER
        .comment("Enable Lumispheric's Eye of the Universe sky, cosmic purple ambience, and fog in The End.")
        .define("endSkyEnabled", true);
    private static final ModConfigSpec.BooleanValue LUNAR_NIGHT_DARKNESS = BUILDER
        .comment("Modulate Overworld nighttime darkness by real Moon phase/presence and darken terrain during solar eclipses.")
        .define("lunarNightDarkness", true);
    private static final ModConfigSpec.BooleanValue SPYGLASS_HUD_ENABLED = BUILDER
        .comment("Show a subtle HUD label when aiming a Spyglass at celestial bodies.")
        .define("spyglassHudEnabled", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enabled = true;
    public static float sunsetIntensity = 0.85F;
    public static float starBrightness = 1.0F;
    public static float milkyWayBrightness = 1.0F;
    public static float mieIntensity = 0.7F;
    public static double latitude = 40.0;
    public static float cloudDistance = 1.0F;
    public static float celestialSize = 0.65F;
    public static double yearLengthDays = 48.0;
    public static boolean pixelatedStyle = true;
    public static boolean endSkyEnabled = true;
    public static boolean lunarNightDarkness = true;
    public static boolean spyglassHudEnabled = true;

    private Config() {}

    public static void setEnabled(boolean value) {
        ENABLED.set(value);
        enabled = value;
    }

    public static void setSunsetIntensity(double value) {
        SUNSET_INTENSITY.set(value);
        sunsetIntensity = (float) value;
    }

    public static void setStarBrightness(double value) {
        STAR_BRIGHTNESS.set(value);
        starBrightness = (float) value;
    }

    public static void setMilkyWayBrightness(double value) {
        MILKY_WAY_BRIGHTNESS.set(value);
        milkyWayBrightness = (float) value;
    }

    public static void setMieIntensity(double value) {
        MIE_INTENSITY.set(value);
        mieIntensity = (float) value;
    }

    public static void setLatitude(double value) {
        LATITUDE.set(value);
        latitude = value;
    }

    public static void setCloudDistance(double value) {
        CLOUD_DISTANCE.set(value);
        cloudDistance = (float) value;
    }

    public static void setCelestialSize(double value) {
        CELESTIAL_SIZE.set(value);
        celestialSize = (float) value;
    }

    public static void setYearLengthDays(double value) {
        double clamped = Math.max(16.0, Math.min(365.2422, value));
        YEAR_LENGTH_DAYS.set(clamped);
        yearLengthDays = clamped;
    }

    public static void setPixelatedStyle(boolean value) {
        PIXELATED_STYLE.set(value);
        pixelatedStyle = value;
    }

    public static void setEndSkyEnabled(boolean value) {
        END_SKY_ENABLED.set(value);
        endSkyEnabled = value;
    }

    public static void setLunarNightDarkness(boolean value) {
        LUNAR_NIGHT_DARKNESS.set(value);
        lunarNightDarkness = value;
    }

    public static void setSpyglassHudEnabled(boolean value) {
        SPYGLASS_HUD_ENABLED.set(value);
        spyglassHudEnabled = value;
    }

    public static void save() {
        SPEC.save();
    }

    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }

        enabled = ENABLED.get();
        sunsetIntensity = SUNSET_INTENSITY.get().floatValue();
        starBrightness = STAR_BRIGHTNESS.get().floatValue();
        milkyWayBrightness = MILKY_WAY_BRIGHTNESS.get().floatValue();
        mieIntensity = MIE_INTENSITY.get().floatValue();
        latitude = LATITUDE.get();
        cloudDistance = CLOUD_DISTANCE.get().floatValue();
        celestialSize = CELESTIAL_SIZE.get().floatValue();
        yearLengthDays = YEAR_LENGTH_DAYS.get();
        pixelatedStyle = PIXELATED_STYLE.get();
        endSkyEnabled = END_SKY_ENABLED.get();
        lunarNightDarkness = LUNAR_NIGHT_DARKNESS.get();
        spyglassHudEnabled = SPYGLASS_HUD_ENABLED.get();
    }
}
