package dinner.dev.lumispheric;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

public final class LumisphericConfigScreen extends Screen {
    private final Screen parent;
    private static final Profile[] PROFILES = Profile.values();
    private int profileIndex;
    private Tab tab = Tab.ATMOSPHERE;

    public LumisphericConfigScreen(Screen parent) {
        super(Component.translatable("config.lumispheric.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int firstY = Math.max(28, this.height / 2 - 96);
        this.addRenderableWidget(Button.builder(enabledLabel(), button -> {
            Config.setEnabled(!Config.enabled);
            button.setMessage(enabledLabel());
        }).bounds(centerX - 100, firstY, 200, 20).build());
        this.addRenderableWidget(Button.builder(profileLabel(), button -> {
            PROFILES[this.profileIndex].apply();
            this.profileIndex = (this.profileIndex + 1) % PROFILES.length;
            this.rebuildWidgets();
        }).bounds(centerX - 100, firstY + 22, 200, 20).build());

        this.addRenderableWidget(Button.builder(tabLabel(Tab.ATMOSPHERE), button -> {
            this.tab = Tab.ATMOSPHERE;
            this.rebuildWidgets();
        }).bounds(centerX - 140, firstY + 44, 138, 20).build());
        this.addRenderableWidget(Button.builder(tabLabel(Tab.NIGHT_AND_CLOUDS), button -> {
            this.tab = Tab.NIGHT_AND_CLOUDS;
            this.rebuildWidgets();
        }).bounds(centerX + 2, firstY + 44, 138, 20).build());

        if (this.tab == Tab.ATMOSPHERE) {
            this.addRenderableWidget(new ConfigSlider(
                centerX - 140, firstY + 72, "config.lumispheric.sunset_intensity",
                "config.lumispheric.tooltip.sunset_intensity", Config.sunsetIntensity, Config::setSunsetIntensity
            ));
            this.addRenderableWidget(new ConfigSlider(
                centerX - 140, firstY + 96, "config.lumispheric.mie_intensity",
                "config.lumispheric.tooltip.mie_intensity", Config.mieIntensity, Config::setMieIntensity
            ));
            this.addRenderableWidget(new ConfigSlider(
                centerX - 140, firstY + 120, "config.lumispheric.latitude",
                "config.lumispheric.tooltip.latitude", (float) ((Config.latitude + 90.0) / 180.0),
                value -> String.format(Locale.ROOT, "%.0f°", value * 180.0 - 90.0),
                value -> Config.setLatitude(value * 180.0 - 90.0)
            ));
            this.addRenderableWidget(new ConfigSlider(
                centerX - 140, firstY + 144, "config.lumispheric.celestial_size",
                "config.lumispheric.tooltip.celestial_size",
                (Config.celestialSize - 0.25F) / 0.75F,
                value -> String.format(Locale.ROOT, "%.0f%%", 25.0 + value * 75.0),
                value -> Config.setCelestialSize(0.25 + value * 0.75)
            ));
        } else {
            this.addRenderableWidget(new ConfigSlider(
                centerX - 140, firstY + 72, "config.lumispheric.star_brightness",
                "config.lumispheric.tooltip.star_brightness", Config.starBrightness / 2.0F, 200.0F,
                value -> Config.setStarBrightness(value * 2.0)
            ));
            this.addRenderableWidget(new ConfigSlider(
                centerX - 140, firstY + 96, "config.lumispheric.milky_way_brightness",
                "config.lumispheric.tooltip.milky_way_brightness", Config.milkyWayBrightness / 2.0F,
                200.0F, value -> Config.setMilkyWayBrightness(value * 2.0)
            ));
            this.addRenderableWidget(new ConfigSlider(
                centerX - 140, firstY + 120, "config.lumispheric.cloud_distance",
                "config.lumispheric.tooltip.cloud_distance", Config.cloudDistance,
                value -> String.format(Locale.ROOT, "%.0f%%", value * 100.0),
                Config::setCloudDistance
            ));
        }

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
            .bounds(centerX - 100, firstY + 172, 200, 20).build());
    }

    @Override
    public void onClose() {
        Config.save();
        this.minecraft.setScreen(this.parent);
    }

    private static Component enabledLabel() {
        return Component.translatable(Config.enabled ? "config.lumispheric.enabled" : "config.lumispheric.disabled");
    }

    private Component profileLabel() {
        return Component.translatable(
            "config.lumispheric.apply_profile",
            Component.translatable(PROFILES[this.profileIndex].translationKey)
        );
    }

    private Component tabLabel(Tab candidate) {
        return Component.translatable(
            this.tab == candidate ? candidate.selectedTranslationKey : candidate.translationKey
        );
    }

    private enum Tab {
        ATMOSPHERE("config.lumispheric.tab.atmosphere", "config.lumispheric.tab.atmosphere.selected"),
        NIGHT_AND_CLOUDS("config.lumispheric.tab.night_clouds", "config.lumispheric.tab.night_clouds.selected");

        private final String translationKey;
        private final String selectedTranslationKey;

        Tab(String translationKey, String selectedTranslationKey) {
            this.translationKey = translationKey;
            this.selectedTranslationKey = selectedTranslationKey;
        }
    }

    private enum Profile {
        VANILLA_PLUS("config.lumispheric.profile.vanilla_plus", 0.70, 0.90, 0.65, 0.45, 0.65, 0.65),
        REALISTIC("config.lumispheric.profile.realistic", 0.85, 1.00, 1.00, 0.70, 1.00, 0.70),
        PERFORMANCE("config.lumispheric.profile.performance", 0.70, 0.80, 0.50, 0.45, 0.00, 0.65);

        private final String translationKey;
        private final double sunset;
        private final double stars;
        private final double milkyWay;
        private final double mie;
        private final double cloudDistance;
        private final double celestialSize;

        Profile(
            String translationKey, double sunset, double stars, double milkyWay,
            double mie, double cloudDistance, double celestialSize
        ) {
            this.translationKey = translationKey;
            this.sunset = sunset;
            this.stars = stars;
            this.milkyWay = milkyWay;
            this.mie = mie;
            this.cloudDistance = cloudDistance;
            this.celestialSize = celestialSize;
        }

        private void apply() {
            Config.setSunsetIntensity(this.sunset);
            Config.setStarBrightness(this.stars);
            Config.setMilkyWayBrightness(this.milkyWay);
            Config.setMieIntensity(this.mie);
            Config.setCloudDistance(this.cloudDistance);
            Config.setCelestialSize(this.celestialSize);
        }
    }

    private static final class ConfigSlider extends AbstractSliderButton {
        private final String labelKey;
        private final DoubleFunction<String> valueLabel;
        private final DoubleConsumer save;

        private ConfigSlider(int x, int y, String labelKey, String tooltipKey, float value, DoubleConsumer save) {
            this(x, y, labelKey, tooltipKey, value, 100.0F, save);
        }

        private ConfigSlider(
            int x, int y, String labelKey, String tooltipKey, float value, float maximumPercent, DoubleConsumer save
        ) {
            this(
                x, y, labelKey, tooltipKey, value,
                normalizedValue -> String.format(Locale.ROOT, "%.0f%%", normalizedValue * maximumPercent),
                save
            );
        }

        private ConfigSlider(
            int x, int y, String labelKey, String tooltipKey, float value,
            DoubleFunction<String> valueLabel, DoubleConsumer save
        ) {
            super(x, y, 280, 20, Component.empty(), Math.max(0.0, Math.min(1.0, value)));
            this.labelKey = labelKey;
            this.valueLabel = valueLabel;
            this.save = save;
            this.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable(this.labelKey, Component.literal(this.valueLabel.apply(this.value))));
        }

        @Override
        protected void applyValue() {
            this.save.accept(this.value);
        }
    }
}
