package dinner.dev.lumispheric;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.HitResult;
import org.joml.Vector3f;

import java.util.Locale;

public final class SpyglassHud {
    private static final double OVERWORLD_SEA_LEVEL = 63.0;
    private static final float COS_EYE = (float) Math.cos(Math.toRadians(14.0));
    private static final float COS_SUN = (float) Math.cos(Math.toRadians(4.5));
    private static final float COS_MOON = (float) Math.cos(Math.toRadians(3.8));
    private static final float COS_PLANET = (float) Math.cos(Math.toRadians(2.4));
    private static final float COS_RADIANT = (float) Math.cos(Math.toRadians(5.5));
    private static final float COS_STAR = (float) Math.cos(Math.toRadians(1.6));
    private static final float COS_GALACTIC_CENTER = (float) Math.cos(Math.toRadians(6.5));

    // Iconic naked-eye stars matching J2000 coordinates in stars.csv.
    private static final NamedStar[] NAMED_STARS = {
        new NamedStar("sirius", "α CMa", 6.7525, -16.7161, -1.46F),
        new NamedStar("canopus", "α Car", 6.3992, -52.6957, -0.74F),
        new NamedStar("rigil_kentaurus", "α Cen", 14.6601, -60.8340, -0.27F),
        new NamedStar("arcturus", "α Boo", 14.2610, 19.1824, -0.05F),
        new NamedStar("vega", "α Lyr", 18.6156, 38.7837, 0.03F),
        new NamedStar("capella", "α Aur", 5.2782, 45.9980, 0.08F),
        new NamedStar("rigel", "β Ori", 5.2423, -8.2016, 0.13F),
        new NamedStar("procyon", "α CMi", 7.6550, 5.2250, 0.34F),
        new NamedStar("achernar", "α Eri", 1.6286, -57.2368, 0.46F),
        new NamedStar("betelgeuse", "α Ori", 5.9195, 7.4071, 0.50F),
        new NamedStar("altair", "α Aql", 19.8464, 8.8683, 0.76F),
        new NamedStar("aldebaran", "α Tau", 4.5987, 16.5093, 0.86F),
        new NamedStar("spica", "α Vir", 13.4199, -11.1613, 0.97F),
        new NamedStar("antares", "α Sco", 16.4901, -26.4320, 0.96F),
        new NamedStar("pollux", "β Gem", 7.7553, 28.0262, 1.14F),
        new NamedStar("fomalhaut", "α PsA", 22.9608, -29.6222, 1.16F),
        new NamedStar("deneb", "α Cyg", 20.6905, 45.2803, 1.25F),
        new NamedStar("regulus", "α Leo", 10.1395, 11.9672, 1.35F),
        new NamedStar("castor", "α Gem", 7.5767, 31.8883, 1.58F),
        new NamedStar("bellatrix", "γ Ori", 5.4189, 6.3497, 1.64F),
        new NamedStar("alnilam", "ε Ori", 5.6036, -1.2019, 1.69F),
        new NamedStar("dubhe", "α UMa", 11.0621, 61.7510, 1.79F),
        new NamedStar("mirfak", "α Per", 3.4054, 49.8612, 1.79F),
        new NamedStar("polaris", "α UMi", 2.5303, 89.2641, 1.98F),
        new NamedStar("algol", "β Per", 3.1361, 40.9556, 2.12F),
        new NamedStar("mizar", "ζ UMa", 13.3987, 54.9254, 2.23F)
    };

    private static Component displayedTitle;
    private static Component displayedSubtitle;
    private static float fadeAlpha;
    private static long lastFrameNanos;

    private SpyglassHud() {}

    public static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!Config.enabled || !Config.spyglassHudEnabled || level == null || mc.player == null || mc.options.hideGui || mc.screen != null) {
            fadeAlpha = 0.0F;
            return;
        }

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0.016F : Math.max(0.0F, Math.min(0.1F, (now - lastFrameNanos) * 1.0e-9F));
        lastFrameNanos = now;

        boolean scoping = mc.player.isScoping();
        TargetInfo target = null;
        if (scoping) {
            boolean blockedByNearbyBlock = mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK;
            if (!blockedByNearbyBlock) {
                float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
                target = findTarget(mc, level, partialTick);
            }
        }

        if (target != null) {
            displayedTitle = target.title();
            displayedSubtitle = target.subtitle();
            fadeAlpha = Math.min(1.0F, fadeAlpha + dt * 5.0F);
        } else {
            fadeAlpha = Math.max(0.0F, fadeAlpha - dt * (scoping ? 4.2F : 9.0F));
            if (fadeAlpha <= 0.01F) {
                displayedTitle = null;
                displayedSubtitle = null;
                return;
            }
        }

        if (displayedTitle == null || fadeAlpha <= 0.02F) {
            return;
        }

        int alphaByte = Math.max(8, Math.min(255, Math.round(fadeAlpha * 235.0F)));
        int subAlphaByte = Math.max(8, Math.min(255, Math.round(fadeAlpha * 185.0F)));
        int titleColor = (alphaByte << 24) | 0xF5F8FF;
        int subtitleColor = (subAlphaByte << 24) | 0xB8C7DE;

        int centerX = guiGraphics.guiWidth() / 2;
        int baseY = Math.round(guiGraphics.guiHeight() * 0.77F);
        guiGraphics.drawCenteredString(mc.font, displayedTitle, centerX, baseY, titleColor);
        if (displayedSubtitle != null) {
            guiGraphics.drawCenteredString(mc.font, displayedSubtitle, centerX, baseY + 11, subtitleColor);
        }
    }

    private static TargetInfo findTarget(Minecraft mc, ClientLevel level, float partialTick) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vector3f look = new Vector3f(camera.getLookVector()).normalize();

        if (SkyRenderer.shouldRenderEnd(level)) {
            float dotEye = look.dot(SkyRenderer.END_EYE_DIRECTION);
            if (dotEye >= COS_EYE) {
                return new TargetInfo(
                    Component.translatable("hud.lumispheric.end.eye"),
                    Component.translatable("hud.lumispheric.end.eye_sub")
                );
            }
            return null;
        }

        if (!SkyRenderer.shouldRender(level)) {
            return null;
        }

        double cameraY = camera.getPosition().y;
        float horizon = SkyRenderer.celestialHorizonLevel(cameraY);
        if (look.y < horizon - 0.03F) {
            return null;
        }

        float altitude = (float) Math.max(0.0, cameraY - OVERWORLD_SEA_LEVEL);
        float weather = SkyRenderer.weatherStrength(level, cameraY, partialTick);
        SkyRenderer.FrameAstronomy astro = SkyRenderer.frameAstronomy(level, partialTick);
        float sunUp = (float) astro.sun().up();
        float nightStrength = SkyRenderer.astronomicalNightStrength(sunUp, altitude);
        float spaceBlend = SkyRenderer.spaceTransition(cameraY);

        // 1. Sun
        float sunDot = dotDirection(look, astro.sun());
        if (sunDot >= COS_SUN && astro.sun().up() > horizon) {
            float eclipse = astro.eclipseStrength() * (1.0F - weather);
            Component sub = eclipse > 0.08F
                ? Component.translatable("hud.lumispheric.solar_eclipse")
                : Component.translatable("hud.lumispheric.altitude", formatAltitudeDegrees(astro.sun().up()));
            return new TargetInfo(Component.translatable("hud.lumispheric.sun"), sub);
        }

        // 2. Moon
        float moonDot = dotDirection(look, astro.moon());
        if (moonDot >= COS_MOON && astro.moon().up() > horizon) {
            CelestialModel.LunarEclipse lunarEclipse = astro.lunarEclipse();
            int illumPct = Math.round(astro.moonIllumination() * 100.0F);
            Component sub;
            if (lunarEclipse.umbra() > 0.05F || lunarEclipse.penumbra() > 0.30F) {
                sub = Component.translatable("hud.lumispheric.lunar_eclipse");
            } else {
                int phase = CelestialModel.moonPhase(astro.astronomicalTime());
                sub = Component.translatable(
                    "hud.lumispheric.moon_detail",
                    Component.translatable("hud.lumispheric.moon_phase." + phase),
                    illumPct + "%"
                );
            }
            return new TargetInfo(Component.translatable("hud.lumispheric.moon"), sub);
        }

        // 3. Solar System Planets (respecting real Spyglass limiting magnitude)
        float eclipse = astro.eclipseStrength() * (1.0F - weather);
        float skyMagLimit = Mth.lerp(Math.max(nightStrength, spaceBlend), -3.3F, 9.2F)
            + eclipse * 7.5F
            + spaceBlend * 2.5F;
        CelestialModel.PlanetDirection bestPlanet = null;
        float bestPlanetDot = COS_PLANET;
        for (CelestialModel.PlanetDirection pd : CelestialModel.planetDirections(
            astro.astronomicalTime(), astro.latitude()
        )) {
            if (pd.direction().up() <= horizon || pd.planet().apparentMagnitude() > skyMagLimit + 0.5F) {
                continue;
            }
            float dot = dotDirection(look, pd.direction());
            if (dot > bestPlanetDot) {
                bestPlanetDot = dot;
                bestPlanet = pd;
            }
        }
        if (bestPlanet != null) {
            String key = "hud.lumispheric.planet." + bestPlanet.planet().name().toLowerCase(Locale.ROOT);
            String mag = formatMagnitude(bestPlanet.planet().apparentMagnitude());
            return new TargetInfo(
                Component.translatable(key),
                Component.translatable("hud.lumispheric.planet_mag", mag)
            );
        }

        // 4. Active meteor shower radiant (Perseids / Geminids) at night
        CelestialModel.MeteorShower shower = astro.activeShower();
        if (shower != null && shower.strength() > 0.14F && weather < 0.35F
            && (nightStrength > 0.50F || spaceBlend > 0.45F) && shower.radiant().up() > horizon) {
            float radDot = dotDirection(look, shower.radiant());
            if (radDot >= COS_RADIANT) {
                return new TargetInfo(
                    Component.translatable("hud.lumispheric.shower." + shower.id()),
                    Component.translatable("hud.lumispheric.shower.active")
                );
            }
        }

        // 5. Famous named real stars when stars are visible
        float starBright = SkyRenderer.astronomicalStarBrightness(level, partialTick, cameraY);
        if (starBright > 0.06F) {
            NamedStar bestStar = null;
            float bestStarDot = COS_STAR;
            for (NamedStar star : NAMED_STARS) {
                CelestialModel.Direction dir = CelestialModel.equatorialToLocal(
                    star.raRad, star.decRad, astro.astronomicalTime(), astro.latitude()
                );
                if (dir.up() <= horizon) {
                    continue;
                }
                float dot = dotDirection(look, dir);
                if (dot > bestStarDot) {
                    bestStarDot = dot;
                    bestStar = star;
                }
            }
            if (bestStar != null) {
                return new TargetInfo(
                    Component.translatable("hud.lumispheric.star." + bestStar.id),
                    Component.literal(bestStar.designation + "  •  Mag " + formatMagnitude(bestStar.magnitude))
                );
            }
        }

        // 6. Galactic Center / Milky Way
        float mwStrength = SkyRenderer.milkyWayStrength(astro, altitude, weather);
        if (mwStrength > 0.12F && astro.galacticCenter().up() > horizon) {
            float gcDot = dotDirection(look, astro.galacticCenter());
            if (gcDot >= COS_GALACTIC_CENTER) {
                return new TargetInfo(
                    Component.translatable("hud.lumispheric.galactic_center"),
                    Component.translatable("hud.lumispheric.milky_way")
                );
            }
        }

        return null;
    }

    private static float dotDirection(Vector3f look, CelestialModel.Direction dir) {
        return look.x * (float) dir.east() + look.y * (float) dir.up() + look.z * (float) dir.south();
    }

    private static String formatMagnitude(float mag) {
        return String.format(Locale.ROOT, "%+.1f", mag);
    }

    private static String formatAltitudeDegrees(double sinAlt) {
        long deg = Math.round(Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, sinAlt)))));
        return deg + "°";
    }

    private record TargetInfo(Component title, Component subtitle) {}

    private static final class NamedStar {
        private final String id;
        private final String designation;
        private final double raRad;
        private final double decRad;
        private final float magnitude;

        private NamedStar(String id, String designation, double raHours, double decDeg, float magnitude) {
            this.id = id;
            this.designation = designation;
            this.raRad = raHours * Math.PI / 12.0;
            this.decRad = Math.toRadians(decDeg);
            this.magnitude = magnitude;
        }
    }
}
