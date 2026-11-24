package dinner.dev.lumispheric;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

public final class LumisphericConfigScreen extends Screen {
    private static final double MIN_YEAR_DAYS = 16.0;
    private static final double MAX_YEAR_DAYS = 365.2422;
    private static final int LAYOUT_HEIGHT = 222;
    private static final int COLUMN_GAP = 10;

    private final Screen parent;
    private int layoutTop;
    private int leftColumnX;
    private int rightColumnX;
    private int columnWidth;
    private int sectionHeaderY;

    public LumisphericConfigScreen(Screen parent) {
        super(Component.translatable("config.lumispheric.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(this.width - 24, 380);
        int contentLeft = (this.width - contentWidth) / 2;
        boolean inGame = this.minecraft != null && this.minecraft.level != null;
        this.layoutTop = inGame && this.height >= LAYOUT_HEIGHT + 52
            ? this.height - LAYOUT_HEIGHT - 14
            : Math.max(10, (this.height - LAYOUT_HEIGHT) / 2);

        this.columnWidth = (contentWidth - COLUMN_GAP) / 2;
        this.leftColumnX = contentLeft;
        this.rightColumnX = this.leftColumnX + this.columnWidth + COLUMN_GAP;

        // Top bar: master ON/OFF + 3 one-click preset buttons.
        int topBarY = this.layoutTop + 20;
        int buttonGap = 4;
        int pillWidth = (contentWidth - buttonGap * 3) / 4;

        this.addRenderableWidget(Button.builder(enabledLabel(), button -> {
            Config.setEnabled(!Config.enabled);
            button.setMessage(enabledLabel());
        })
            .tooltip(Tooltip.create(Component.translatable("config.lumispheric.tooltip.enabled")))
            .bounds(this.leftColumnX, topBarY, pillWidth, 18)
            .build());

        Profile[] profiles = Profile.values();
        for (int i = 0; i < profiles.length; i++) {
            Profile profile = profiles[i];
            int x = this.leftColumnX + (pillWidth + buttonGap) * (i + 1);
            int width = i == profiles.length - 1
                ? (contentLeft + contentWidth) - x
                : pillWidth;
            this.addRenderableWidget(Button.builder(profileLabel(profile), button -> {
                profile.apply();
                this.rebuildWidgets();
            })
                .tooltip(Tooltip.create(Component.translatable(profile.tooltipKey)))
                .bounds(x, topBarY, width, 18)
                .build());
        }

        this.sectionHeaderY = this.layoutTop + 46;
        int rowStartY = this.layoutTop + 58;
        int rowStep = 21;

        // Left column: Visual style + Atmosphere & Orbit.
        this.addRenderableWidget(Button.builder(styleLabel(), button -> {
            Config.setPixelatedStyle(!Config.pixelatedStyle);
            this.rebuildWidgets();
        })
            .tooltip(Tooltip.create(Component.translatable("config.lumispheric.tooltip.pixelated_style")))
            .bounds(this.leftColumnX, rowStartY, this.columnWidth, 18)
            .build());

        this.addRenderableWidget(new ConfigSlider(
            this.leftColumnX, rowStartY + rowStep, this.columnWidth,
            "config.lumispheric.sunset_intensity", "config.lumispheric.tooltip.sunset_intensity",
            Config.sunsetIntensity, Config::setSunsetIntensity
        ));
        this.addRenderableWidget(new ConfigSlider(
            this.leftColumnX, rowStartY + rowStep * 2, this.columnWidth,
            "config.lumispheric.mie_intensity", "config.lumispheric.tooltip.mie_intensity",
            Config.mieIntensity, Config::setMieIntensity
        ));
        this.addRenderableWidget(new ConfigSlider(
            this.leftColumnX, rowStartY + rowStep * 3, this.columnWidth,
            "config.lumispheric.latitude", "config.lumispheric.tooltip.latitude",
            (float) ((Config.latitude + 90.0) / 180.0),
            LumisphericConfigScreen::formatLatitude,
            value -> Config.setLatitude(Math.round(value * 180.0 - 90.0))
        ));
        this.addRenderableWidget(new ConfigSlider(
            this.leftColumnX, rowStartY + rowStep * 4, this.columnWidth,
            "config.lumispheric.year_length", "config.lumispheric.tooltip.year_length",
            (float) ((Config.yearLengthDays - MIN_YEAR_DAYS) / (MAX_YEAR_DAYS - MIN_YEAR_DAYS)),
            value -> String.format(
                Locale.ROOT, "%.0f", MIN_YEAR_DAYS + value * (MAX_YEAR_DAYS - MIN_YEAR_DAYS)
            ),
            value -> Config.setYearLengthDays(
                Math.round(MIN_YEAR_DAYS + value * (MAX_YEAR_DAYS - MIN_YEAR_DAYS))
            )
        ));

        // Right column: Night, Celestial Bodies & Clouds.
        this.addRenderableWidget(new ConfigSlider(
            this.rightColumnX, rowStartY, this.columnWidth,
            "config.lumispheric.star_brightness", "config.lumispheric.tooltip.star_brightness",
            Config.starBrightness / 2.0F, 200.0F,
            value -> Config.setStarBrightness(value * 2.0)
        ));
        this.addRenderableWidget(new ConfigSlider(
            this.rightColumnX, rowStartY + rowStep, this.columnWidth,
            "config.lumispheric.milky_way_brightness", "config.lumispheric.tooltip.milky_way_brightness",
            Config.milkyWayBrightness / 2.0F, 200.0F,
            value -> Config.setMilkyWayBrightness(value * 2.0)
        ));
        this.addRenderableWidget(new ConfigSlider(
            this.rightColumnX, rowStartY + rowStep * 2, this.columnWidth,
            "config.lumispheric.celestial_size", "config.lumispheric.tooltip.celestial_size",
            (Config.celestialSize - 0.25F) / 0.75F,
            value -> String.format(Locale.ROOT, "%.0f%%", 25.0 + value * 75.0),
            value -> Config.setCelestialSize(0.25 + value * 0.75)
        ));
        this.addRenderableWidget(new ConfigSlider(
            this.rightColumnX, rowStartY + rowStep * 3, this.columnWidth,
            "config.lumispheric.cloud_distance", "config.lumispheric.tooltip.cloud_distance",
            Config.cloudDistance,
            value -> String.format(Locale.ROOT, "%.0f%%", value * 100.0),
            Config::setCloudDistance
        ));
        this.addRenderableWidget(Button.builder(
            Component.translatable("config.lumispheric.reset"),
            button -> {
                Config.setEnabled(true);
                Config.setLatitude(40.0);
                Config.setEndSkyEnabled(true);
                Config.setLunarNightDarkness(true);
                Config.setSpyglassHudEnabled(true);
                Profile.VANILLA_PLUS.apply();
                this.rebuildWidgets();
            }
        )
            .tooltip(Tooltip.create(Component.translatable("config.lumispheric.tooltip.reset")))
            .bounds(this.rightColumnX, rowStartY + rowStep * 4, this.columnWidth, 18)
            .build());

        // Feature toggle row: End Sky, Lunar Nights, Spyglass HUD.
        int featureRowY = rowStartY + rowStep * 5;
        int featureGap = 4;
        int featureWidth = (contentWidth - featureGap * 2) / 3;
        int thirdX = contentLeft + (featureWidth + featureGap) * 2;
        int thirdWidth = (contentLeft + contentWidth) - thirdX;

        this.addRenderableWidget(Button.builder(endSkyLabel(), button -> {
            Config.setEndSkyEnabled(!Config.endSkyEnabled);
            button.setMessage(endSkyLabel());
        })
            .tooltip(Tooltip.create(Component.translatable("config.lumispheric.tooltip.end_sky")))
            .bounds(contentLeft, featureRowY, featureWidth, 18)
            .build());

        this.addRenderableWidget(Button.builder(lunarNightsLabel(), button -> {
            Config.setLunarNightDarkness(!Config.lunarNightDarkness);
            button.setMessage(lunarNightsLabel());
        })
            .tooltip(Tooltip.create(Component.translatable("config.lumispheric.tooltip.lunar_nights")))
            .bounds(contentLeft + featureWidth + featureGap, featureRowY, featureWidth, 18)
            .build());

        this.addRenderableWidget(Button.builder(spyglassHudLabel(), button -> {
            Config.setSpyglassHudEnabled(!Config.spyglassHudEnabled);
            button.setMessage(spyglassHudLabel());
        })
            .tooltip(Tooltip.create(Component.translatable("config.lumispheric.tooltip.spyglass_hud")))
            .bounds(thirdX, featureRowY, thirdWidth, 18)
            .build());

        // Footer Done button.
        int doneWidth = 150;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
            .bounds((this.width - doneWidth) / 2, this.layoutTop + LAYOUT_HEIGHT - 22, doneWidth, 20)
            .build());
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (this.minecraft == null || this.minecraft.level == null) {
            super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(
            this.font,
            this.title,
            this.width / 2,
            this.layoutTop + 4,
            0xFFFFFF
        );
        guiGraphics.drawString(
            this.font,
            Component.translatable("config.lumispheric.section.atmosphere"),
            this.leftColumnX,
            this.sectionHeaderY,
            0xE0E0E0,
            true
        );
        guiGraphics.drawString(
            this.font,
            Component.translatable("config.lumispheric.section.night_clouds"),
            this.rightColumnX,
            this.sectionHeaderY,
            0xE0E0E0,
            true
        );
    }

    @Override
    public void onClose() {
        Config.save();
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private static String formatLatitude(double normalized) {
        long deg = Math.round(normalized * 180.0 - 90.0);
        if (deg == 0L) {
            return "0°";
        }
        return deg > 0L ? deg + "° N" : Math.abs(deg) + "° S";
    }

    private static Component enabledLabel() {
        return Component.translatable(
            Config.enabled ? "config.lumispheric.toggle.on" : "config.lumispheric.toggle.off"
        );
    }

    private static Component styleLabel() {
        return Component.translatable(
            Config.pixelatedStyle
                ? "config.lumispheric.pixelated_style.minecraft"
                : "config.lumispheric.pixelated_style.realistic"
        );
    }

    private static Component endSkyLabel() {
        return Component.translatable(
            Config.endSkyEnabled ? "config.lumispheric.end_sky.on" : "config.lumispheric.end_sky.off"
        );
    }

    private static Component lunarNightsLabel() {
        return Component.translatable(
            Config.lunarNightDarkness ? "config.lumispheric.lunar_nights.on" : "config.lumispheric.lunar_nights.off"
        );
    }

    private static Component spyglassHudLabel() {
        return Component.translatable(
            Config.spyglassHudEnabled ? "config.lumispheric.spyglass_hud.on" : "config.lumispheric.spyglass_hud.off"
        );
    }

    private static Component profileLabel(Profile profile) {
        Component base = Component.translatable(profile.translationKey);
        return profile.matchesCurrent()
            ? Component.literal("• ").append(base)
            : base;
    }

    private enum Profile {
        VANILLA_PLUS(
            "config.lumispheric.profile.vanilla_plus",
            "config.lumispheric.tooltip.profile.vanilla_plus",
            0.80, 1.00, 0.90, 0.55, 0.70, 0.65, 48.0, true
        ),
        REALISTIC(
            "config.lumispheric.profile.realistic",
            "config.lumispheric.tooltip.profile.realistic",
            0.90, 1.10, 1.15, 0.75, 1.00, 0.65, 365.2422, false
        ),
        PERFORMANCE(
            "config.lumispheric.profile.performance",
            "config.lumispheric.tooltip.profile.performance",
            0.75, 0.85, 0.65, 0.45, 0.00, 0.65, 48.0, true
        );

        private final String translationKey;
        private final String tooltipKey;
        private final double sunset;
        private final double stars;
        private final double milkyWay;
        private final double mie;
        private final double cloudDistance;
        private final double celestialSize;
        private final double yearLengthDays;
        private final boolean pixelatedStyle;

        Profile(
            String translationKey, String tooltipKey,
            double sunset, double stars, double milkyWay,
            double mie, double cloudDistance, double celestialSize,
            double yearLengthDays, boolean pixelatedStyle
        ) {
            this.translationKey = translationKey;
            this.tooltipKey = tooltipKey;
            this.sunset = sunset;
            this.stars = stars;
            this.milkyWay = milkyWay;
            this.mie = mie;
            this.cloudDistance = cloudDistance;
            this.celestialSize = celestialSize;
            this.yearLengthDays = yearLengthDays;
            this.pixelatedStyle = pixelatedStyle;
        }

        private void apply() {
            Config.setSunsetIntensity(this.sunset);
            Config.setStarBrightness(this.stars);
            Config.setMilkyWayBrightness(this.milkyWay);
            Config.setMieIntensity(this.mie);
            Config.setCloudDistance(this.cloudDistance);
            Config.setCelestialSize(this.celestialSize);
            Config.setYearLengthDays(this.yearLengthDays);
            Config.setPixelatedStyle(this.pixelatedStyle);
        }

        private boolean matchesCurrent() {
            return Config.pixelatedStyle == this.pixelatedStyle
                && Math.abs(Config.sunsetIntensity - this.sunset) < 0.03
                && Math.abs(Config.starBrightness - this.stars) < 0.05
                && Math.abs(Config.milkyWayBrightness - this.milkyWay) < 0.05
                && Math.abs(Config.mieIntensity - this.mie) < 0.03
                && Math.abs(Config.cloudDistance - this.cloudDistance) < 0.03
                && Math.abs(Config.celestialSize - this.celestialSize) < 0.03
                && Math.abs(Config.yearLengthDays - this.yearLengthDays) < 2.0;
        }
    }

    private static final class ConfigSlider extends AbstractSliderButton {
        private final String labelKey;
        private final DoubleFunction<String> valueLabel;
        private final DoubleConsumer save;

        private ConfigSlider(
            int x, int y, int width, String labelKey, String tooltipKey, float value, DoubleConsumer save
        ) {
            this(x, y, width, labelKey, tooltipKey, value, 100.0F, save);
        }

        private ConfigSlider(
            int x, int y, int width, String labelKey, String tooltipKey,
            float value, float maximumPercent, DoubleConsumer save
        ) {
            this(
                x, y, width, labelKey, tooltipKey, value,
                normalizedValue -> String.format(Locale.ROOT, "%.0f%%", normalizedValue * maximumPercent),
                save
            );
        }

        private ConfigSlider(
            int x, int y, int width, String labelKey, String tooltipKey, float value,
            DoubleFunction<String> valueLabel, DoubleConsumer save
        ) {
            super(x, y, width, 18, Component.empty(), Math.max(0.0, Math.min(1.0, value)));
            this.labelKey = labelKey;
            this.valueLabel = valueLabel;
            this.save = save;
            this.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.translatable(
                this.labelKey, Component.literal(this.valueLabel.apply(this.value))
            ));
        }

        @Override
        protected void applyValue() {
            this.save.accept(this.value);
        }
    }
}
