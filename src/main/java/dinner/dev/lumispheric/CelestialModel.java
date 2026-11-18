package dinner.dev.lumispheric;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CelestialModel {
    private static final double REFERENCE_DAY_OF_YEAR = 79.0;
    private static final double REFERENCE_TICK = 6_000.0;
    private static final double DAYS_PER_YEAR = 365.2422;
    private static final double SYNODIC_MONTH_DAYS = 29.530588853;
    private static final double LUNAR_INCLINATION = Math.toRadians(5.145);
    private static final double EARTH_OBLIQUITY = Math.toRadians(23.43928);
    private static final Equatorial GALACTIC_NORTH = new Equatorial(
        Math.toRadians(192.85948), Math.toRadians(27.12825)
    );
    private static final Equatorial GALACTIC_CENTER = new Equatorial(
        Math.toRadians(266.4051), Math.toRadians(-28.936175)
    );
    private static final double TWO_PI = Math.PI * 2.0;

    private CelestialModel() {}

    public static double latitudeRadians() {
        return Math.toRadians(Config.latitude);
    }

    public static Direction sunDirection(double dayTime, double latitude) {
        return localDirection(toEquatorial(solarLongitude(dayTime), 0.0), dayTime, latitude);
    }

    public static Direction moonDirection(double dayTime, double latitude) {
        double longitude = solarLongitude(dayTime) + Math.PI + TWO_PI * lunarCycle(dayTime);
        double nodeLongitude = Math.toRadians(125.04452 - 0.0529539 * daysSinceReference(dayTime));
        double eclipticLatitude = Math.asin(
            Math.sin(LUNAR_INCLINATION) * Math.sin(longitude - nodeLongitude)
        );
        return localDirection(toEquatorial(longitude, eclipticLatitude), dayTime, latitude);
    }

    public static Direction galacticNorthDirection(double dayTime, double latitude) {
        return localDirection(GALACTIC_NORTH, dayTime, latitude);
    }

    public static Direction galacticCenterDirection(double dayTime, double latitude) {
        return localDirection(GALACTIC_CENTER, dayTime, latitude);
    }

    public static Vector3f transformQuadVertex(
        float x, float y, float z, Direction direction, float vanillaSkyAngle, float centerY
    ) {
        Vector3f center = toPreSkyCoordinates(direction, vanillaSkyAngle);
        float normal = centerY > 0.0F ? 1.0F : -1.0F;
        Quaternionf rotation = new Quaternionf().rotationTo(
            0.0F, normal, 0.0F, center.x, center.y, center.z
        );
        Vector3f offset = new Vector3f(x, y - centerY, z).rotate(rotation);
        return center.mul(100.0F).add(offset);
    }

    public static float moonIllumination(double dayTime) {
        return (1.0F + (float) Math.cos(lunarCycle(dayTime) * TWO_PI)) * 0.5F;
    }

    public static int moonPhase(double dayTime) {
        return (int) (lunarCycle(dayTime) * 8.0) & 7;
    }

    public static float eclipseStrength(double dayTime, double latitude) {
        double cycle = lunarCycle(dayTime);
        double newMoonDistance = Math.abs(cycle - 0.5) * SYNODIC_MONTH_DAYS;
        float newMoon = 1.0F - smoothstep(0.0F, 0.45F, (float) newMoonDistance);
        if (newMoon <= 0.0F) {
            return 0.0F;
        }

        double longitude = solarLongitude(dayTime) + Math.PI + TWO_PI * cycle;
        double nodeLongitude = Math.toRadians(125.04452 - 0.0529539 * daysSinceReference(dayTime));
        double eclipticLatitude = Math.asin(
            Math.sin(LUNAR_INCLINATION) * Math.sin(longitude - nodeLongitude)
        );
        float nodeAlignment = 1.0F - smoothstep(
            (float) Math.toRadians(0.5),
            (float) Math.toRadians(1.8),
            (float) Math.abs(eclipticLatitude)
        );
        if (nodeAlignment <= 0.0F) {
            return 0.0F;
        }

        Direction sun = sunDirection(dayTime, latitude);
        Direction moon = moonDirection(dayTime, latitude);
        float dot = (float) (sun.east * moon.east + sun.up * moon.up + sun.south * moon.south);
        float separation = (float) Math.acos(Math.max(-1.0F, Math.min(1.0F, dot)));
        float overlap = 1.0F - smoothstep(
            (float) Math.toRadians(0.15),
            (float) Math.toRadians(1.2),
            separation
        );
        float daylight = smoothstep(-0.02F, 0.03F, (float) sun.up);
        return newMoon * nodeAlignment * overlap * daylight;
    }

    public static double localSiderealTime(double dayTime) {
        double siderealAngle = solarLongitude(dayTime) + TWO_PI * dayTime / 24_000.0 - Math.PI * 0.5;
        return siderealAngle - Math.floor(siderealAngle / TWO_PI) * TWO_PI;
    }

    public static Matrix4f celestialToLocalMatrix(double dayTime, double latitude) {
        double siderealTime = localSiderealTime(dayTime);
        double sinSidereal = Math.sin(siderealTime);
        double cosSidereal = Math.cos(siderealTime);
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);
        return new Matrix4f().set(new float[] {
            (float) -sinSidereal, (float) (cosLatitude * cosSidereal), (float) (sinLatitude * cosSidereal), 0.0F,
            (float) cosSidereal, (float) (cosLatitude * sinSidereal), (float) (sinLatitude * sinSidereal), 0.0F,
            0.0F, (float) sinLatitude, (float) -cosLatitude, 0.0F,
            0.0F, 0.0F, 0.0F, 1.0F
        });
    }

    private static Vector3f toPreSkyCoordinates(Direction direction, float vanillaSkyAngle) {
        double angle = vanillaSkyAngle * TWO_PI;
        double sine = Math.sin(angle);
        double cosine = Math.cos(angle);
        return new Vector3f(
            (float) direction.south,
            (float) (direction.up * cosine - direction.east * sine),
            (float) (-direction.east * cosine - direction.up * sine)
        ).normalize();
    }

    private static Direction localDirection(Equatorial equatorial, double dayTime, double latitude) {
        double hourAngle = localSiderealTime(dayTime) - equatorial.rightAscension;
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);
        double cosDeclination = Math.cos(equatorial.declination);
        double sinDeclination = Math.sin(equatorial.declination);
        double cosHourAngle = Math.cos(hourAngle);
        double sinHourAngle = Math.sin(hourAngle);
        return new Direction(
            -cosDeclination * sinHourAngle,
            cosDeclination * cosLatitude * cosHourAngle + sinDeclination * sinLatitude,
            cosDeclination * sinLatitude * cosHourAngle - sinDeclination * cosLatitude
        );
    }

    private static Equatorial toEquatorial(double longitude, double latitude) {
        double cosLatitude = Math.cos(latitude);
        double x = cosLatitude * Math.cos(longitude);
        double y = cosLatitude * Math.sin(longitude) * Math.cos(EARTH_OBLIQUITY)
            - Math.sin(latitude) * Math.sin(EARTH_OBLIQUITY);
        double z = cosLatitude * Math.sin(longitude) * Math.sin(EARTH_OBLIQUITY)
            + Math.sin(latitude) * Math.cos(EARTH_OBLIQUITY);
        return new Equatorial(Math.atan2(y, x), Math.asin(Math.max(-1.0, Math.min(1.0, z))));
    }

    private static double solarLongitude(double dayTime) {
        double daysFromEquinox = daysSinceReference(dayTime) - REFERENCE_DAY_OF_YEAR;
        return TWO_PI * daysFromEquinox / DAYS_PER_YEAR;
    }

    private static double lunarCycle(double dayTime) {
        double cycle = dayTime / 24_000.0 / SYNODIC_MONTH_DAYS;
        return cycle - Math.floor(cycle);
    }

    private static double daysSinceReference(double dayTime) {
        return REFERENCE_DAY_OF_YEAR + (dayTime - REFERENCE_TICK) / 24_000.0;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float x = Math.max(0.0F, Math.min(1.0F, (value - edge0) / (edge1 - edge0)));
        return x * x * (3.0F - 2.0F * x);
    }

    public record Direction(double east, double up, double south) {}

    private record Equatorial(double rightAscension, double declination) {}
}
