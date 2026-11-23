package dinner.dev.lumispheric;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class CelestialModel {
    private static final double REFERENCE_DAY_OF_YEAR = 79.0;
    private static final double REFERENCE_TICK = 6_000.0;
    private static final double DAYS_PER_YEAR = 365.2422;
    private static final double SYNODIC_MONTH_DAYS = 29.530588853;
    private static final double LUNAR_INCLINATION = Math.toRadians(5.145);
    private static final double EARTH_OBLIQUITY = Math.toRadians(23.43928);
    private static final double LUNAR_ANGULAR_RADIUS = Math.toRadians(0.26);
    private static final double EARTH_UMBRA_ANGULAR_RADIUS = Math.toRadians(0.70);
    private static final double EARTH_PENUMBRA_ANGULAR_RADIUS = Math.toRadians(1.22);
    private static final Equatorial GALACTIC_NORTH = new Equatorial(
        Math.toRadians(192.85948), Math.toRadians(27.12825)
    );
    private static final Equatorial GALACTIC_CENTER = new Equatorial(
        Math.toRadians(266.4051), Math.toRadians(-28.936175)
    );
    private static final PlanetElements EARTH_ELEMENTS = new PlanetElements(
        "Earth", 0.0, 0.0, 282.9404, 1.000000, 0.016709,
        356.0470, 0.9856002585,
        0.0, 0.0, 0.0, 0.0,
        1.0F, 1.0F, 1.0F, 0.0F, 0.0
    );
    private static final PlanetElements[] PLANET_ELEMENTS = {
        new PlanetElements("Mercury", 48.3313, 7.0047, 29.1241, 0.387098,
            0.205635, 168.6562, 4.0923344368, 3.24587e-5, 5.0e-8,
            1.01444e-5, 5.59e-10, 0.94F, 0.90F, 0.82F, 0.052F, -0.42),
        new PlanetElements("Venus", 76.6799, 3.3946, 54.8910, 0.723330,
            0.006773, 48.0052, 1.6021302244, 2.4659e-5, 2.75e-8,
            1.3830e-5, -1.302e-9, 1.00F, 0.94F, 0.78F, 0.088F, -4.40),
        EARTH_ELEMENTS,
        new PlanetElements("Mars", 49.5574, 1.8497, 286.5016, 1.523688,
            0.093405, 18.5987, 0.5240207766, 2.11081e-5, -1.78e-8,
            2.92961e-5, 2.516e-9, 1.00F, 0.62F, 0.42F, 0.068F, -1.52),
        new PlanetElements("Jupiter", 100.4542, 1.3030, 273.8777, 5.202560,
            0.048498, 19.8950, 0.0830853001, 2.76854e-5, -1.557e-7,
            1.64505e-5, 4.469e-9, 0.98F, 0.90F, 0.74F, 0.132F, -9.40),
        new PlanetElements("Saturn", 113.6634, 2.4886, 339.3939, 9.554750,
            0.055546, 316.9670, 0.0334442282, 2.38980e-5, -1.081e-7,
            2.97661e-5, -9.499e-9, 0.98F, 0.88F, 0.68F, 0.115F, -8.88),
        new PlanetElements("Uranus", 74.0005, 0.7733, 96.6612, 19.18171,
            0.047318, 142.5905, 0.011725806, 1.3978e-5, 1.9e-8,
            3.0565e-5, 7.45e-9, 0.68F, 0.90F, 0.96F, 0.062F, -7.19),
        new PlanetElements("Neptune", 131.7806, 1.7700, 272.8461, 30.05826,
            0.008606, 260.5944, 0.005995147, 3.0173e-5, -2.55e-7,
            -6.027e-6, 2.15e-9, 0.48F, 0.66F, 1.00F, 0.062F, -6.87)
    };
    private static final double TWO_PI = Math.PI * 2.0;

    private CelestialModel() {}

    public static double latitudeRadians() {
        return Math.toRadians(Config.latitude);
    }

    public static double configuredDaysPerYear() {
        return Math.max(16.0, Config.yearLengthDays);
    }

    public static Direction sunDirection(double dayTime, double latitude) {
        return localDirection(toEquatorial(solarLongitude(dayTime), 0.0), dayTime, latitude);
    }

    public static Direction moonDirection(double dayTime, double latitude) {
        double longitude = solarLongitude(dayTime) + Math.PI + TWO_PI * lunarCycle(dayTime);
        double nodeLongitude = Math.toRadians(125.04452 - 0.0529539 * scaledYearDays(dayTime));
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

    public static BillboardBasis billboardBasis(Direction direction) {
        Vector3f forward = new Vector3f(
            (float) direction.east(), (float) direction.up(), (float) direction.south()
        ).normalize();
        Vector3f referenceUp = Math.abs(forward.y) > 0.97F
            ? new Vector3f(0.0F, 0.0F, 1.0F)
            : new Vector3f(0.0F, 1.0F, 0.0F);
        Vector3f right = new Vector3f(referenceUp).cross(forward).normalize();
        Vector3f up = new Vector3f(forward).cross(right).normalize();
        return new BillboardBasis(forward, right, up);
    }

    public static QuadVertex[] buildOrientedQuad(
        Direction direction,
        float halfSize,
        float u0,
        float v0,
        float u1,
        float v1,
        float vanillaSkyAngle,
        BillboardBasis basis
    ) {
        Vector3f p00 = localToPreSky(
            new Vector3f(basis.forward()).mul(100.0F)
                .add(new Vector3f(basis.right()).mul(-halfSize))
                .add(new Vector3f(basis.up()).mul(halfSize)),
            vanillaSkyAngle
        );
        Vector3f p10 = localToPreSky(
            new Vector3f(basis.forward()).mul(100.0F)
                .add(new Vector3f(basis.right()).mul(halfSize))
                .add(new Vector3f(basis.up()).mul(halfSize)),
            vanillaSkyAngle
        );
        Vector3f p11 = localToPreSky(
            new Vector3f(basis.forward()).mul(100.0F)
                .add(new Vector3f(basis.right()).mul(halfSize))
                .add(new Vector3f(basis.up()).mul(-halfSize)),
            vanillaSkyAngle
        );
        Vector3f p01 = localToPreSky(
            new Vector3f(basis.forward()).mul(100.0F)
                .add(new Vector3f(basis.right()).mul(-halfSize))
                .add(new Vector3f(basis.up()).mul(-halfSize)),
            vanillaSkyAngle
        );
        return new QuadVertex[] {
            new QuadVertex(p00.x, p00.y, p00.z, u0, v0, localUpFromPreSky(p00, vanillaSkyAngle)),
            new QuadVertex(p10.x, p10.y, p10.z, u1, v0, localUpFromPreSky(p10, vanillaSkyAngle)),
            new QuadVertex(p11.x, p11.y, p11.z, u1, v1, localUpFromPreSky(p11, vanillaSkyAngle)),
            new QuadVertex(p01.x, p01.y, p01.z, u0, v1, localUpFromPreSky(p01, vanillaSkyAngle))
        };
    }

    public static Vector3f lightInBillboardFrame(Direction lightWorldDir, BillboardBasis basis) {
        Vector3f light = new Vector3f(
            (float) lightWorldDir.east(), (float) lightWorldDir.up(), (float) lightWorldDir.south()
        ).normalize();
        // +X = quad right (u increases), +Y = quad down (v increases -> flip to match top-down UV or shader),
        // Wait: in shader uv=(0,0) is top-left (+up, -right), uv=(1,1) is bottom-right (-up, +right).
        // So quadPosition = uv * 2 - 1 has +X = right, +Y = -up, +Z = toward observer (-forward).
        return new Vector3f(
            light.dot(basis.right()),
            -light.dot(basis.up()),
            -light.dot(basis.forward())
        ).normalize();
    }

    public static float localUpFromPreSky(Vector3f position, float vanillaSkyAngle) {
        double angle = vanillaSkyAngle * TWO_PI;
        double localUp = position.y * Math.cos(angle) - position.z * Math.sin(angle);
        return (float) (localUp / Math.max(position.length(), 0.0001F));
    }

    public static float moonIllumination(double dayTime) {
        return (1.0F + (float) Math.cos(lunarCycle(dayTime) * TWO_PI)) * 0.5F;
    }

    public static int moonPhase(double dayTime) {
        double cycle = lunarCycle(dayTime);
        int phase = (int) (cycle * 8.0) & 7;
        if (phase == 4 && moonIllumination(dayTime) > 0.01F) {
            return cycle < 0.5 ? 3 : 5;
        }
        return phase;
    }

    public static float eclipseStrength(double dayTime, double latitude) {
        double cycle = lunarCycle(dayTime);
        double newMoonDistance = Math.abs(cycle - 0.5) * SYNODIC_MONTH_DAYS;
        float newMoon = 1.0F - smoothstep(0.0F, 0.45F, (float) newMoonDistance);
        if (newMoon <= 0.0F) {
            return 0.0F;
        }

        double longitude = solarLongitude(dayTime) + Math.PI + TWO_PI * cycle;
        double nodeLongitude = Math.toRadians(125.04452 - 0.0529539 * scaledYearDays(dayTime));
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

    public static LunarEclipse lunarEclipse(double dayTime, double latitude) {
        Direction sun = sunDirection(dayTime, latitude);
        Direction moon = moonDirection(dayTime, latitude);
        double dot = -sun.east * moon.east - sun.up * moon.up - sun.south * moon.south;
        double separation = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        double longitude = solarLongitude(dayTime) + Math.PI + TWO_PI * lunarCycle(dayTime);
        double longitudeOffset = wrapRadians(longitude - solarLongitude(dayTime) - Math.PI);
        double nodeLongitude = Math.toRadians(125.04452 - 0.0529539 * scaledYearDays(dayTime));
        double eclipticLatitude = Math.asin(
            Math.sin(LUNAR_INCLINATION) * Math.sin(longitude - nodeLongitude)
        );
        return new LunarEclipse(
            circleOverlapFraction(separation, EARTH_PENUMBRA_ANGULAR_RADIUS),
            circleOverlapFraction(separation, EARTH_UMBRA_ANGULAR_RADIUS),
            (float) (-longitudeOffset * Math.cos(eclipticLatitude) / LUNAR_ANGULAR_RADIUS),
            (float) (-eclipticLatitude / LUNAR_ANGULAR_RADIUS)
        );
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

    public static Planet[] planetEquatorialDirections(double dayTime) {
        double days = scaledYearDays(dayTime) - REFERENCE_DAY_OF_YEAR;
        double[] earthPosition = planetEclipticPosition(EARTH_ELEMENTS, days);
        Planet[] planets = new Planet[8];
        PlanetElements[] elements = PLANET_ELEMENTS;
        for (int i = 0; i < elements.length; i++) {
            PlanetElements planet = elements[i];
            double[] heliocentric = planetEclipticPosition(planet, days);
            double x = heliocentric[0] - earthPosition[0];
            double y = heliocentric[1] - earthPosition[1];
            double z = heliocentric[2] - earthPosition[2];
            Equatorial geoEq = eclipticVectorToEquatorial(x, y, z);

            double sunDist = Math.max(0.001, Math.sqrt(
                heliocentric[0] * heliocentric[0]
                    + heliocentric[1] * heliocentric[1]
                    + heliocentric[2] * heliocentric[2]
            ));
            double earthDist = Math.max(0.001, Math.sqrt(x * x + y * y + z * z));
            double cosPhase = (heliocentric[0] * x + heliocentric[1] * y + heliocentric[2] * z)
                / (sunDist * earthDist);
            double phaseAngleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosPhase))));
            // Approximate apparent visual magnitude V = H + 5*log10(r*delta) + phase term
            double phaseTerm = 0.015 * phaseAngleDeg + 0.00012 * phaseAngleDeg * phaseAngleDeg;
            float apparentMagnitude = (float) (
                planet.absoluteMagnitude + 5.0 * Math.log10(sunDist * earthDist) + phaseTerm
            );
            float intensity = Math.max(0.08F, Math.min(1.0F, (6.5F - apparentMagnitude) / 10.5F));

            // Direction from planet to Sun in equatorial coordinates for realistic phase lighting
            Equatorial sunFromPlanetEq = eclipticVectorToEquatorial(
                -heliocentric[0], -heliocentric[1], -heliocentric[2]
            );

            planets[i] = new Planet(
                planet.name,
                geoEq.rightAscension,
                geoEq.declination,
                sunFromPlanetEq.rightAscension,
                sunFromPlanetEq.declination,
                planet.red,
                planet.green,
                planet.blue,
                planet.size,
                intensity,
                apparentMagnitude
            );
        }
        return planets;
    }

    public static PlanetDirection[] planetDirections(double dayTime, double latitude) {
        Planet[] planets = planetEquatorialDirections(dayTime);
        PlanetDirection[] directions = new PlanetDirection[planets.length - 1];
        int index = 0;
        for (Planet planet : planets) {
            if (planet.name().equals("Earth")) {
                continue;
            }
            Equatorial equatorial = new Equatorial(planet.rightAscension(), planet.declination());
            Equatorial sunFromPlanet = new Equatorial(planet.sunRightAscension(), planet.sunDeclination());
            directions[index++] = new PlanetDirection(
                planet,
                localDirection(equatorial, dayTime, latitude),
                localDirection(sunFromPlanet, dayTime, latitude)
            );
        }
        return directions;
    }

    private static Equatorial eclipticVectorToEquatorial(double x, double y, double z) {
        double equatorialX = x;
        double equatorialY = y * Math.cos(EARTH_OBLIQUITY) - z * Math.sin(EARTH_OBLIQUITY);
        double equatorialZ = y * Math.sin(EARTH_OBLIQUITY) + z * Math.cos(EARTH_OBLIQUITY);
        double rightAscension = Math.atan2(equatorialY, equatorialX);
        if (rightAscension < 0.0) {
            rightAscension += TWO_PI;
        }
        double declination = Math.atan2(
            equatorialZ,
            Math.sqrt(equatorialX * equatorialX + equatorialY * equatorialY)
        );
        return new Equatorial(rightAscension, declination);
    }

    private static Vector3f localToPreSky(Vector3f local, float vanillaSkyAngle) {
        double angle = vanillaSkyAngle * TWO_PI;
        double sine = Math.sin(angle);
        double cosine = Math.cos(angle);
        return new Vector3f(
            local.z,
            (float) (local.y * cosine - local.x * sine),
            (float) (-local.x * cosine - local.y * sine)
        );
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
        double daysFromEquinox = (dayTime - REFERENCE_TICK) / 24_000.0;
        return TWO_PI * daysFromEquinox / configuredDaysPerYear();
    }

    private static double lunarCycle(double dayTime) {
        double cycle = dayTime / 24_000.0 / SYNODIC_MONTH_DAYS;
        return cycle - Math.floor(cycle);
    }

    private static double scaledYearDays(double dayTime) {
        double mcDays = (dayTime - REFERENCE_TICK) / 24_000.0;
        return REFERENCE_DAY_OF_YEAR + mcDays * (DAYS_PER_YEAR / configuredDaysPerYear());
    }

    private static double[] planetEclipticPosition(PlanetElements planet, double days) {
        double node = Math.toRadians(planet.node + planet.nodeRate * days);
        double inclination = Math.toRadians(planet.inclination + planet.inclinationRate * days);
        double perihelion = Math.toRadians(planet.perihelion + planet.perihelionRate * days);
        double eccentricity = planet.eccentricity + planet.eccentricityRate * days;
        double meanAnomaly = Math.toRadians(
            wrapDegrees(planet.meanAnomaly + planet.meanMotion * days)
        );

        double eccentricAnomaly = meanAnomaly;
        for (int iteration = 0; iteration < 8; iteration++) {
            eccentricAnomaly -= (
                eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly) - meanAnomaly
            ) / (1.0 - eccentricity * Math.cos(eccentricAnomaly));
        }

        double xv = planet.semiMajorAxis
            * (Math.cos(eccentricAnomaly) - eccentricity);
        double yv = planet.semiMajorAxis
            * Math.sqrt(1.0 - eccentricity * eccentricity)
            * Math.sin(eccentricAnomaly);

        double cosNode = Math.cos(node);
        double sinNode = Math.sin(node);
        double cosPerihelion = Math.cos(perihelion);
        double sinPerihelion = Math.sin(perihelion);
        double cosInclination = Math.cos(inclination);
        double sinInclination = Math.sin(inclination);

        double x = xv * (cosNode * cosPerihelion - sinNode * sinPerihelion * cosInclination)
            - yv * (cosNode * sinPerihelion + sinNode * cosPerihelion * cosInclination);
        double y = xv * (sinNode * cosPerihelion + cosNode * sinPerihelion * cosInclination)
            - yv * (sinNode * sinPerihelion - cosNode * cosPerihelion * cosInclination);
        double z = xv * (sinPerihelion * sinInclination)
            + yv * (cosPerihelion * sinInclination);
        return new double[] { x, y, z };
    }

    private static double wrapDegrees(double degrees) {
        return degrees - Math.floor(degrees / 360.0) * 360.0;
    }

    private static double wrapRadians(double angle) {
        return angle - Math.floor((angle + Math.PI) / TWO_PI) * TWO_PI;
    }

    private static float circleOverlapFraction(double separation, double shadowRadius) {
        double moonRadius = LUNAR_ANGULAR_RADIUS;
        if (separation >= shadowRadius + moonRadius) {
            return 0.0F;
        }
        if (separation <= Math.abs(shadowRadius - moonRadius)) {
            return shadowRadius >= moonRadius ? 1.0F : (float) (shadowRadius * shadowRadius
                / (moonRadius * moonRadius));
        }

        double moonAngle = Math.acos(
            (separation * separation + moonRadius * moonRadius - shadowRadius * shadowRadius)
                / (2.0 * separation * moonRadius)
        );
        double shadowAngle = Math.acos(
            (separation * separation + shadowRadius * shadowRadius - moonRadius * moonRadius)
                / (2.0 * separation * shadowRadius)
        );
        double overlapArea = moonRadius * moonRadius * moonAngle
            + shadowRadius * shadowRadius * shadowAngle
            - 0.5 * Math.sqrt(
                (-separation + moonRadius + shadowRadius)
                    * (separation + moonRadius - shadowRadius)
                    * (separation - moonRadius + shadowRadius)
                    * (separation + moonRadius + shadowRadius)
            );
        return (float) Math.max(0.0, Math.min(1.0, overlapArea / (Math.PI * moonRadius * moonRadius)));
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float x = Math.max(0.0F, Math.min(1.0F, (value - edge0) / (edge1 - edge0)));
        return x * x * (3.0F - 2.0F * x);
    }

    public record Direction(double east, double up, double south) {}

    public record BillboardBasis(Vector3f forward, Vector3f right, Vector3f up) {}

    public record LunarEclipse(
        float penumbra, float umbra, float shadowOffsetX, float shadowOffsetY
    ) {}

    public record QuadVertex(float x, float y, float z, float u, float v, float localUp) {}

    public record Planet(
        String name,
        double rightAscension,
        double declination,
        double sunRightAscension,
        double sunDeclination,
        float red,
        float green,
        float blue,
        float halfSize,
        float intensity,
        float apparentMagnitude
    ) {}

    public record PlanetDirection(Planet planet, Direction direction, Direction sunDirectionFromPlanet) {}

    private record Equatorial(double rightAscension, double declination) {}

    private record PlanetElements(
        String name,
        double node,
        double inclination,
        double perihelion,
        double semiMajorAxis,
        double eccentricity,
        double meanAnomaly,
        double meanMotion,
        double nodeRate,
        double inclinationRate,
        double perihelionRate,
        double eccentricityRate,
        float red,
        float green,
        float blue,
        float size,
        double absoluteMagnitude
    ) {}
}
