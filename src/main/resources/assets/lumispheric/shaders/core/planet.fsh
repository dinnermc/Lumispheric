#version 150

uniform vec3 SunDirection;
uniform vec3 MoonDirection;
// x = SunElevation, y = MoonIllumination, z = Rain, w = EclipseStrength
uniform vec4 SkyParams0;
// x = HorizonDip, y = GeometricDip, z = CameraHeight, w = SpaceBlend
uniform vec4 SkyParams1;
// x = SunsetIntensity, y = MieIntensity, z = BiomeDust, w = BiomeClearness
uniform vec4 SkyParams2;
// rgb = twilight warm color, a = twilight strength
uniform vec4 TwilightColor;
// x = blueHour, y = auroraStrength, z = auroraHemisphere (+1 north / -1 south), w = pixelatedStyle
uniform vec4 TwilightExtra;
// x = startAzimuth, y = startElevation, z = azimuthDelta, w = elevationDelta
uniform vec4 MeteorStart;
// x = progress, y = active (0/1), z = gameTimeSeconds, w = cloudWindAngle
uniform vec4 MeteorParams;
// x = PlanetRadius, y = PlanetBlend, z = PlanetRotation.x, w = PlanetRotation.y
uniform vec4 PlanetParams;
uniform vec4 ColorModulator;

in vec3 localPosition;
in vec4 vertexColor;

out vec4 fragColor;

const float PI = 3.14159265359;
const float TWO_PI = 6.28318530718;
const float PLANET_HORIZON_TOP_FADE = 0.018;

float hashCell(vec3 cell) {
    vec3 value = fract(cell * vec3(0.1031, 0.1030, 0.0973));
    value += dot(value, value.yzx + 33.33);
    return fract((value.x + value.y) * value.z);
}

float valueNoise(vec3 position) {
    vec3 cell = floor(position);
    vec3 blend = fract(position);
    // Quintic C2-continuous interpolation prevents grid-edge creases in lighting & relief!
    blend = blend * blend * blend * (blend * (blend * 6.0 - 15.0) + 10.0);

    float z0 = mix(
        mix(hashCell(cell), hashCell(cell + vec3(1.0, 0.0, 0.0)), blend.x),
        mix(hashCell(cell + vec3(0.0, 1.0, 0.0)), hashCell(cell + vec3(1.0, 1.0, 0.0)), blend.x),
        blend.y
    );
    float z1 = mix(
        mix(hashCell(cell + vec3(0.0, 0.0, 1.0)), hashCell(cell + vec3(1.0, 0.0, 1.0)), blend.x),
        mix(hashCell(cell + vec3(0.0, 1.0, 1.0)), hashCell(cell + vec3(1.0, 1.0, 1.0)), blend.x),
        blend.y
    );
    return mix(z0, z1, blend.z);
}

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise2D(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(cell);
    float b = hash21(cell + vec2(1.0, 0.0));
    float c = hash21(cell + vec2(0.0, 1.0));
    float d = hash21(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float wrapRadians(float angle) {
    return mod(angle + PI, TWO_PI) - PI;
}

vec3 quantizeCubeSphere(vec3 dir, float cellsPerFace) {
    vec3 ad = abs(dir);
    float m = max(ad.x, max(ad.y, ad.z));
    vec3 proj = dir / max(m, 1.0e-5);
    vec3 snapped = (floor(proj * cellsPerFace) + 0.5) / cellsPerFace;
    return normalize(snapped);
}

vec3 rotateAroundAxis(vec3 value, vec3 axis, float angle) {
    float sine = sin(angle);
    float cosine = cos(angle);
    return value * cosine
        + cross(axis, value) * sine
        + axis * dot(axis, value) * (1.0 - cosine);
}

vec3 toPlanetCoordinates(vec3 normal) {
    vec3 rotated = rotateAroundAxis(normal, vec3(0.0, 0.0, 1.0), PlanetParams.z);
    return rotateAroundAxis(rotated, vec3(1.0, 0.0, 0.0), PlanetParams.w);
}

float filteredNoise(vec3 coords) {
    float pixelFootprint = max(
        max(fwidth(coords.x), fwidth(coords.y)),
        fwidth(coords.z)
    );
    float detailVisibility = 1.0 - smoothstep(0.45, 1.05, pixelFootprint);
    if (detailVisibility <= 0.0) {
        return 0.5;
    }
    return mix(0.5, valueNoise(coords), detailVisibility);
}

// Smooth orbital weather cloud field: swirling cyclone fronts, trade-wind belts, puffy cumulus & wispy cirrus.
// Uses identical octaves when called for bump-map neighbors so there are ZERO crease or maze artifacts.
vec2 orbitalCloudField(vec3 normal, bool includeFineWisps) {
    vec3 windAxis = normalize(vec3(0.12, 0.98, 0.14));
    vec3 moved = rotateAroundAxis(normal, windAxis, MeteorParams.w);

    float lat = moved.y;
    vec3 stretched = vec3(moved.x, moved.y * 1.40, moved.z);

    // Smooth macro cyclone / weather-front domain warp.
    vec3 macroP = stretched * 4.6 + vec3(11.3, 4.7, 19.1);
    float swirlA = valueNoise(macroP) - 0.5;
    float swirlB = valueNoise(macroP + vec3(23.1, 17.4, 9.2)) - 0.5;
    float swirlC = valueNoise(macroP * 1.85 + vec3(5.4, 31.2, 14.8)) - 0.5;

    float swirlAngle = (swirlA * 1.9 + swirlC * 0.8) * (0.50 + 0.65 * abs(lat));
    vec3 cycloneP = rotateAroundAxis(stretched, normalize(moved + vec3(0.0, 0.001, 0.0)), swirlAngle * 0.14);
    vec3 warped = cycloneP * 15.5
        + vec3(swirlA * 2.8 - swirlB * 1.4, swirlB * 2.1 + swirlC * 1.2, swirlC * 2.5 + swirlA * 1.3);

    float weatherFront = valueNoise(warped * 0.42);
    float stormBanks = valueNoise(warped * 0.92 + vec3(7.3, 19.1, 37.4));
    float cumulus = valueNoise(warped * 2.05 + vec3(23.2, 5.7, 13.9));
    float fineDetail = includeFineWisps
        ? valueNoise(warped * 4.4 + vec3(41.1, 11.8, 27.3))
        : 0.5;
    float microWisps = includeFineWisps
        ? filteredNoise(warped * 8.8 + vec3(17.9, 53.2, 8.4))
        : 0.5;

    // Balanced ~38% global cloud coverage with ITCZ tropical band & stormy mid-latitudes so oceans & continents shine.
    float beltBias = 0.03 * exp(-lat * lat * 18.0)
        + 0.045 * exp(-pow((abs(lat) - 0.60) / 0.24, 2.0))
        - 0.045 * exp(-pow((abs(lat) - 0.28) / 0.16, 2.0));

    float baseShape = 0.46 * weatherFront + 0.28 * stormBanks + 0.16 * cumulus
        + (includeFineWisps ? 0.065 * fineDetail + 0.035 * microWisps : 0.05)
        + beltBias;

    float thickClouds = smoothstep(0.54, 0.69, baseShape);
    float coreThickness = smoothstep(0.61, 0.84, baseShape);
    float cirrusMask = smoothstep(0.49, 0.66, weatherFront * 0.65 + fineDetail * 0.35 + beltBias * 0.5);

    float density = clamp(
        thickClouds * mix(0.72, 1.0, coreThickness) + cirrusMask * 0.20 * (1.0 - thickClouds),
        0.0,
        1.0
    );
    float thickness = clamp(coreThickness * thickClouds + 0.20 * thickClouds, 0.0, 1.0);
    return vec2(density, thickness);
}

// Smooth organic elevation function (C2 smooth FBM — NO sharp abs() contour creases!).
float smoothMountainHeight(vec3 p) {
    float h1 = valueNoise(p * 14.0 + vec3(41.0, 13.0, 5.0));
    float h2 = valueNoise(p * 29.0 + vec3(7.0, 37.0, 17.0));
    float h3 = valueNoise(p * 60.0 + vec3(29.0, 5.0, 43.0));
    return 0.56 * h1 + 0.30 * h2 + 0.14 * h3;
}

float twilightScattering(float sunCosine) {
    return exp(-max(sunCosine, 0.0) / 0.22)
        * exp(-max(-sunCosine, 0.0) / 0.055);
}

vec3 solarTransmission(float sunCosine) {
    float airMass = min(12.0, 1.0 / max(sunCosine, 0.035));
    float opticalDepth = max(airMass - 1.0, 0.0);
    return exp(-vec3(0.11, 0.34, 0.80) * opticalDepth * 0.048);
}

vec3 sphereNormalAt(vec3 direction, float cameraScale, float sphereScale) {
    float rayProjection = cameraScale * direction.y;
    float discriminant = max(
        0.0,
        rayProjection * rayProjection - (cameraScale * cameraScale - sphereScale * sphereScale)
    );
    float distanceToSurface = -rayProjection - sqrt(discriminant);
    vec3 surfacePosition = vec3(
        direction.x * distanceToSurface,
        cameraScale + direction.y * distanceToSurface,
        direction.z * distanceToSurface
    );
    return normalize(surfacePosition);
}

vec3 softFilmicRollOff(vec3 c) {
    return c / (1.0 + max(c - vec3(0.75), vec3(0.0)) * 0.55);
}

vec3 computeAurora(vec3 viewDir, float elevation, float auroraStrength, float hemisphere, float timeSec, float pixelatedStyle) {
    if (auroraStrength <= 0.001 || elevation < 0.04) {
        return vec3(0.0);
    }

    vec3 sampleDir = pixelatedStyle > 0.5 ? quantizeCubeSphere(viewDir, 128.0) : viewDir;
    float sampleElev = asin(clamp(sampleDir.y, -1.0, 1.0));
    if (sampleElev < 0.04) {
        return vec3(0.0);
    }

    float poleDot = clamp(-sampleDir.z * hemisphere, -1.0, 1.0);
    float polarMask = smoothstep(-0.15, 0.55, poleDot);
    if (polarMask <= 0.001) {
        return vec3(0.0);
    }

    vec2 skyPlane = sampleDir.xz / (sampleDir.y + 0.24);
    vec3 auroraColor = vec3(0.0);

    for (int layer = 0; layer < 3; layer++) {
        float fl = float(layer);
        float shift = fl * 0.42;
        float sway = sin(skyPlane.x * (1.35 + fl * 0.35) + timeSec * (0.09 + fl * 0.025) + shift * 5.0) * 0.28
            + sin(skyPlane.x * 2.9 - timeSec * 0.14 + fl * 2.1) * 0.11;
        float ribbonCenter = (-1.15 - fl * 0.48) * hemisphere + sway;
        float distAcross = (skyPlane.y - ribbonCenter) / (0.28 + fl * 0.06);
        float ribbon = exp(-distAcross * distAcross);

        float rayCoord = skyPlane.x * (7.5 + fl * 3.0) + timeSec * (0.18 - fl * 0.05) + sway * 4.0;
        float rays = 0.40 + 0.60 * noise2D(vec2(rayCoord, timeSec * 0.12 + fl * 7.3));
        float fineShimmer = 0.72 + 0.28 * sin(rayCoord * 2.6 + timeSec * 0.8 + fl * 3.1);

        float baseElev = 0.18 + fl * 0.07 + 0.04 * sin(skyPlane.x * 1.8 + timeSec * 0.11);
        float heightAboveBase = sampleElev - baseElev;
        float bottomFade = smoothstep(-0.04, 0.08, heightAboveBase);
        float topFade = exp(-max(heightAboveBase, 0.0) * (3.8 - fl * 0.4));
        float curtain = ribbon * rays * fineShimmer * bottomFade * topFade;

        float altFrac = clamp(heightAboveBase / 0.38, 0.0, 1.0);
        vec3 lowColor = vec3(0.14, 0.92, 0.52);
        vec3 midColor = vec3(0.16, 0.78, 0.74);
        vec3 highColor = vec3(0.62, 0.22, 0.80);
        vec3 layerCol = mix(lowColor, midColor, smoothstep(0.08, 0.45, altFrac));
        layerCol = mix(layerCol, highColor, smoothstep(0.45, 0.92, altFrac));

        auroraColor += layerCol * curtain * (0.68 - fl * 0.14);
    }

    float horizonClip = smoothstep(0.04, 0.16, sampleElev);
    return auroraColor * polarMask * horizonClip * auroraStrength;
}

vec3 computeMeteor(float elevation, float azimuth) {
    if (MeteorParams.y < 0.5) {
        return vec3(0.0);
    }
    float progress = MeteorParams.x;
    float tail = max(0.0, progress - 0.28);
    float headAz = MeteorStart.x + MeteorStart.z * progress;
    float tailAz = MeteorStart.x + MeteorStart.z * tail;
    float headEl = MeteorStart.y + MeteorStart.w * progress;
    float tailEl = MeteorStart.y + MeteorStart.w * tail;

    float azScale = max(0.15, cos((headEl + tailEl) * 0.5));
    float dx = wrapRadians(azimuth - tailAz) * azScale;
    float dy = elevation - tailEl;
    float vx = wrapRadians(headAz - tailAz) * azScale;
    float vy = headEl - tailEl;
    float lenSq = vx * vx + vy * vy;
    float along = lenSq > 1.0e-7 ? clamp((dx * vx + dy * vy) / lenSq, 0.0, 1.0) : 0.0;
    float dist = length(vec2(dx - along * vx, dy - along * vy));

    float width = mix(0.0020, 0.0052, along);
    float core = exp(-(dist * dist) / (width * width));
    float envelope = smoothstep(0.0, 0.12, progress) * (1.0 - smoothstep(0.75, 1.0, progress));
    float trailProfile = pow(along, 1.65);
    float intensity = core * trailProfile * envelope;
    if (intensity <= 0.001) {
        return vec3(0.0);
    }
    vec3 color = mix(vec3(0.65, 0.85, 1.0), vec3(1.0, 0.97, 0.88), smoothstep(0.5, 0.98, along));
    return color * intensity * 1.25;
}

vec3 computeAtmosphere(vec3 viewDir, float elevation, float azimuth) {
    float sunElevationSin = SkyParams0.x;
    float moonIllumination = SkyParams0.y;
    float rain = SkyParams0.z;
    float eclipse = SkyParams0.w;

    float horizonDip = SkyParams1.x;
    float geometricDip = SkyParams1.y;
    float altitude = SkyParams1.z;
    float spaceBlend = SkyParams1.w;

    float mieIntensity = SkyParams2.y;
    float biomeDust = SkyParams2.z;
    float biomeClearness = SkyParams2.w;

    vec3 twilightRgb = TwilightColor.rgb;
    float twilightStrength = TwilightColor.a;
    float blueHour = TwilightExtra.x;
    float auroraStrength = TwilightExtra.y;
    float auroraHemisphere = TwilightExtra.z;
    float pixelatedStyle = TwilightExtra.w;

    vec3 sunDir = normalize(SunDirection);
    vec3 moonDir = normalize(MoonDirection);

    float horizonDrop = spaceBlend * 0.10;
    float skyElevation = elevation + horizonDip + horizonDrop;
    float altitudeBlend = smoothstep(0.0, 256.0, altitude);
    float horizonSharpness = mix(2.7 + biomeClearness * 0.45 - biomeDust * 0.35, 12.0, spaceBlend);
    float horizon = exp(-abs(skyElevation) * horizonSharpness);

    vec3 zenithDay = mix(vec3(0.13, 0.27, 0.66), vec3(0.07, 0.19, 0.56), biomeClearness * 0.45);
    zenithDay = mix(zenithDay, vec3(0.19, 0.33, 0.63), biomeDust * 0.22);

    vec3 horizonDay = mix(vec3(0.60, 0.76, 0.92), vec3(0.12, 0.30, 0.68), spaceBlend);
    horizonDay = mix(horizonDay, vec3(0.75, 0.73, 0.67), biomeDust * 0.22 * (1.0 - spaceBlend));

    vec3 sky = mix(zenithDay, horizonDay, horizon);

    float sunDot = clamp(dot(viewDir, sunDir), -1.0, 1.0);
    float moonDot = clamp(dot(viewDir, moonDir), -1.0, 1.0);
    float sunElev = asin(clamp(sunElevationSin, -1.0, 1.0));
    float apparentSunElev = sunElev + geometricDip;

    float groundNight = 1.0 - smoothstep(-0.28, 0.04, sunElev);
    float highAltNight = 1.0 - smoothstep(-0.32, 0.02, apparentSunElev);
    float nightBlend = mix(groundNight, highAltNight, altitudeBlend);
    float horizonNight = mix(nightBlend, 1.0 - smoothstep(-0.16, 0.22, sunDot), spaceBlend);

    float twilightLight = twilightStrength * (1.0 - rain * 0.75);
    vec3 twilightZenith = mix(vec3(0.07, 0.14, 0.38), vec3(0.03, 0.055, 0.17), blueHour);
    sky = mix(sky, twilightZenith, twilightLight * (1.0 - horizon) * (1.0 - spaceBlend) * 0.72);

    vec3 nightSky = mix(vec3(0.004, 0.007, 0.017), vec3(0.015, 0.024, 0.042), horizon);
    sky = mix(sky, nightSky, horizonNight);

    float atmosphereHeight = max(0.0, skyElevation) / 0.045;
    float upperAtmosphere = spaceBlend * exp(-atmosphereHeight * atmosphereHeight);
    vec3 upperColor = mix(vec3(0.06, 0.17, 0.48), vec3(0.005, 0.008, 0.022), horizonNight);
    sky = mix(sky, upperColor, upperAtmosphere * 0.85);

    float moonAbove = smoothstep(-0.08, 0.14, moonDir.y);
    float moonHalo = pow(max(moonDot, 0.0), 10.0) * 0.036 + pow(max(moonDot, 0.0), 48.0) * 0.045;
    float moonAmbient = (0.004 + 0.006 * horizon) * moonAbove;
    float lunarFactor = moonIllumination * horizonNight * (1.0 - rain * 0.85) * (1.0 - spaceBlend * 0.75);
    sky += vec3(0.42, 0.62, 0.96) * (moonHalo * moonAbove + moonAmbient) * lunarFactor;

    float sunAngle = acos(sunDot);
    float rayleighPhase = 0.75 * (1.0 + sunDot * sunDot);
    float rayleighScatter = (1.0 - horizonNight) * (1.0 - rain)
        * rayleighPhase * (0.35 + 0.65 * horizon);
    sky += vec3(0.012, 0.036, 0.090) * rayleighScatter;

    float limbCenter = elevation + geometricDip - 0.028;
    float blueLimbWidth = 0.032 + spaceBlend * 0.014;
    float blueLimbDist = limbCenter / blueLimbWidth;
    float blueLimb = exp(-blueLimbDist * blueLimbDist)
        * spaceBlend * (1.0 - horizonNight * 0.65) * (1.0 - rain * 0.5)
        * smoothstep(-0.18, 0.30, sunDot);
    sky += vec3(0.04, 0.16, 0.42) * blueLimb;

    float warmLimbWidth = 0.042 + spaceBlend * 0.02;
    float warmLimbDist = (elevation + geometricDip - 0.006) / warmLimbWidth;
    float warmLimb = exp(-warmLimbDist * warmLimbDist)
        * spaceBlend * twilightStrength * smoothstep(0.50, 0.95, sunDot)
        * (1.0 - rain * 0.5) * 0.95;
    sky += vec3(0.24, 0.08, 0.025) * warmLimb;

    // Multi-layered realistic sunset / sunrise spectral gradient.
    float sunwardHorizon = exp(-(sunAngle * sunAngle) / 0.88);
    float tighterSunward = exp(-(sunAngle * sunAngle) / 0.30);
    float lowHorizonBand = exp(-abs(skyElevation) * mix(5.2, 13.0, spaceBlend));
    float wideHorizonBand = exp(-abs(skyElevation) * mix(2.4, 9.0, spaceBlend));

    float ozoneHeight = (skyElevation - 0.24) / 0.20;
    float ozoneBand = exp(-ozoneHeight * ozoneHeight) * twilightLight * (1.0 - blueHour * 0.55)
        * (1.0 - spaceBlend) * (0.45 + 0.55 * sunwardHorizon);
    sky = mix(sky, vec3(0.18, 0.42, 0.64), ozoneBand * 0.34);

    vec3 flankColor = mix(vec3(0.88, 0.46, 0.48), vec3(0.58, 0.32, 0.56), blueHour);
    vec3 solarHorizonColor = mix(vec3(1.00, 0.54, 0.18), vec3(0.95, 0.30, 0.22), blueHour * 0.72);
    vec3 horizonSweepColor = mix(flankColor, mix(twilightRgb, solarHorizonColor, 0.55), 0.22 + 0.78 * sunwardHorizon);
    float horizonSweepWeight = wideHorizonBand * twilightLight * (0.48 + 0.52 * sunwardHorizon) * 0.84;
    sky = mix(sky, horizonSweepColor, clamp(horizonSweepWeight, 0.0, 0.92));

    vec3 blazingCoreColor = mix(vec3(1.05, 0.76, 0.32), vec3(1.02, 0.42, 0.16), blueHour * 0.85);
    float blazingBand = lowHorizonBand * tighterSunward * twilightLight * 0.85;
    sky = mix(sky, blazingCoreColor, clamp(blazingBand, 0.0, 0.90));

    float roseHeight = (skyElevation - 0.11) / 0.18;
    float roseBand = exp(-roseHeight * roseHeight) * wideHorizonBand * twilightLight
        * (0.55 + 0.45 * (1.0 - sunwardHorizon)) * 0.44;
    vec3 roseColor = mix(vec3(0.92, 0.46, 0.48), vec3(0.68, 0.34, 0.62), blueHour);
    sky = mix(sky, roseColor, clamp(roseBand, 0.0, 0.75));

    float antisolarDist = PI - sunAngle;
    float antisolarMask = exp(-(antisolarDist * antisolarDist) / 0.62) * twilightLight * (1.0 - spaceBlend);
    float shadowTop = clamp(0.025 + max(-apparentSunElev, 0.0) * 1.55, 0.025, 0.24);
    float beltHeight = (skyElevation - (shadowTop + 0.068)) / 0.105;
    float beltOfVenus = exp(-beltHeight * beltHeight) * antisolarMask * (0.55 + 0.45 * blueHour) * 0.54;
    sky = mix(sky, vec3(0.82, 0.46, 0.58), clamp(beltOfVenus, 0.0, 0.75));

    float earthShadow = (1.0 - smoothstep(shadowTop - 0.042, shadowTop + 0.042, skyElevation))
        * smoothstep(-0.18, 0.01, skyElevation)
        * antisolarMask * smoothstep(-0.03, 0.12, -apparentSunElev + 0.04) * 0.66;
    sky = mix(sky, vec3(0.06, 0.09, 0.21), earthShadow);

    float wideGlow = exp(-(sunAngle * sunAngle) / 0.32) * twilightLight;
    float coreGlow = exp(-(sunAngle * sunAngle) / 0.065) * twilightLight;
    sky += mix(vec3(0.42, 0.20, 0.06), vec3(0.32, 0.10, 0.08), blueHour) * wideGlow * 0.65;
    sky += mix(vec3(0.58, 0.40, 0.16), vec3(0.42, 0.18, 0.08), blueHour) * coreGlow * 0.85;

    float mieAsymmetry = mix(0.79, 0.73, biomeDust * 0.4);
    float mieDenom = 1.0 + mieAsymmetry * mieAsymmetry - 2.0 * mieAsymmetry * sunDot;
    float mieRatio = (1.0 - mieAsymmetry) * (1.0 - mieAsymmetry) / max(mieDenom, 1.0e-4);
    float miePhase = mieRatio * sqrt(mieRatio);
    float mieStrength = clamp(
        miePhase * mieIntensity * (1.0 + biomeDust * 0.45) * (1.0 - horizonNight)
            * (1.0 - rain * 0.75) * 0.18 * (0.35 + 0.65 * twilightStrength),
        0.0,
        0.88
    );
    vec3 mieColor = vec3(
        1.04,
        mix(0.95, twilightRgb.g + 0.24, twilightStrength),
        mix(0.84, twilightRgb.b + 0.18, twilightStrength)
    );
    sky = mix(sky, mieColor, mieStrength);

    float luminance = dot(sky, vec3(0.2126, 0.7152, 0.0722));
    sky.r = mix(sky.r, luminance * 0.88, rain * 0.62);
    sky.g = mix(sky.g, luminance * 0.94, rain * 0.48);
    sky.b = mix(sky.b, min(1.0, luminance * 1.04 + 0.045), rain * 0.42);

    sky += computeAurora(viewDir, elevation, auroraStrength, auroraHemisphere, MeteorParams.z, pixelatedStyle);
    sky += computeMeteor(elevation, azimuth);

    if (eclipse > 0.001) {
        float eclipseDarkening = eclipse * 0.78;
        sky *= 1.0 - eclipseDarkening;
        float eclipseHorizon = exp(-abs(skyElevation) * 7.5) * eclipse * (1.0 - spaceBlend);
        sky = mix(sky, vec3(0.65, 0.34, 0.16), eclipseHorizon * 0.52);
        float coronaDist = (sunAngle - 0.085) / 0.055;
        float corona = exp(-coronaDist * coronaDist) * eclipse;
        float innerCorona = exp(-pow(max(sunAngle - 0.04, 0.0) / 0.032, 2.0)) * eclipse * 0.65;
        sky = mix(sky, vec3(0.98, 0.86, 0.64), clamp(corona + innerCorona, 0.0, 0.92));
    }

    vec3 spaceColor = mix(vec3(0.002, 0.004, 0.012), vec3(0.001, 0.002, 0.006), horizonNight);
    float apparentElev = elevation + horizonDip + horizonDrop * 0.5;
    float spaceFacing = smoothstep(0.02, 0.14, abs(apparentElev));
    sky = mix(sky, spaceColor, spaceBlend * spaceFacing);

    return clamp(softFilmicRollOff(sky), 0.0, 1.0);
}

void main() {
    vec3 viewDirection = normalize(localPosition);
    float elevation = asin(clamp(viewDirection.y, -1.0, 1.0));
    float azimuth = atan(viewDirection.z, viewDirection.x);
    vec3 skyRgb = computeAtmosphere(viewDirection, elevation, azimuth);
    vec4 skyColor = vec4(skyRgb, 1.0) * vertexColor * ColorModulator;

    float planetBlend = PlanetParams.y;
    if (planetBlend <= 0.001) {
        fragColor = skyColor;
        return;
    }

    float horizon = -SkyParams1.y;
    float coverage = planetBlend * (1.0 - smoothstep(
        horizon - 0.012, horizon + PLANET_HORIZON_TOP_FADE, elevation
    ));
    if (coverage <= 0.001) {
        fragColor = skyColor;
        return;
    }

    float planetRadius = PlanetParams.x;
    float cameraHeight = SkyParams1.z;
    bool isPixelStyle = TwilightExtra.w > 0.5;

    float cameraScale = 1.0 + cameraHeight / planetRadius;
    vec3 worldSurfaceNormal = sphereNormalAt(viewDirection, cameraScale, 1.0);
    vec3 smoothSurfaceNormal = toPlanetCoordinates(worldSurfaceNormal);
    // In Minecraft (Pixel) style, sample clean crisp pixel texels (NO fwidth/dFdx so zero noisy seams!).
    // In Realistic (Smooth) style, use silky continuous coordinates + mountain relief + KSP cyclones + city lights.
    vec3 surfaceNormal = isPixelStyle
        ? quantizeCubeSphere(smoothSurfaceNormal, 140.0)
        : smoothSurfaceNormal;

    vec3 sunDirection = normalize(SunDirection);
    vec3 moonDirection = normalize(MoonDirection);

    // Continents, archipelagos & coastlines (pure C2 smooth noise -> zero maze/contour artifacts!).
    float continentalWarp = valueNoise(surfaceNormal * 2.5 + vec3(17.0, 41.0, 9.0)) - 0.5;
    float regionalWarp = valueNoise(surfaceNormal * 5.6 + vec3(7.0, 23.0, 37.0)) - 0.5;
    float fineWarp = valueNoise(surfaceNormal * 12.0 + vec3(31.0, 5.0, 19.0)) - 0.5;
    vec3 continentalCoordinates = surfaceNormal * 4.4
        + vec3(
            continentalWarp * 1.15 + fineWarp * 0.14,
            regionalWarp * 1.10 - fineWarp * 0.12,
            continentalWarp * 0.45 - regionalWarp * 0.35 + fineWarp * 0.12
        );
    float continentalMass = valueNoise(continentalCoordinates);
    float regionalNoise = valueNoise(continentalCoordinates * 1.80 + vec3(13.0, 29.0, 7.0));
    float islandNoise = valueNoise(continentalCoordinates * 3.6 + vec3(31.0, 11.0, 23.0));
    float archipelagoNoise = isPixelStyle
        ? valueNoise(continentalCoordinates * 7.2 + vec3(5.0, 43.0, 19.0))
        : filteredNoise(continentalCoordinates * 7.5 + vec3(5.0, 43.0, 19.0));
    float continentalCore = 0.64 * continentalMass
        + 0.22 * regionalNoise
        + 0.09 * islandNoise
        + 0.05 * archipelagoNoise;

    float shoreline = 1.0 - smoothstep(0.015, 0.11, abs(continentalCore - 0.495));
    float coastlineDetail = isPixelStyle
        ? valueNoise(continentalCoordinates * 10.0 + vec3(23.0, 7.0, 31.0))
        : filteredNoise(continentalCoordinates * 11.0 + vec3(23.0, 7.0, 31.0));
    float continentNoise = continentalCore + (coastlineDetail - 0.5) * 0.09 * shoreline;
    float land = isPixelStyle
        ? step(0.498, continentNoise)
        : smoothstep(0.488, 0.512, continentNoise);
    float coast = smoothstep(0.35, 0.45, continentNoise)
        * (1.0 - smoothstep(0.47, 0.52, continentNoise));

    float moisture = 0.62 * valueNoise(surfaceNormal * 6.8 + vec3(19.0, 7.0, 29.0))
        + 0.38 * valueNoise(surfaceNormal * 15.5 + vec3(3.0, 23.0, 11.0));
    float climateNoise = valueNoise(surfaceNormal * 5.2 + vec3(31.0, 17.0, 9.0));
    float temperature = clamp(
        1.0 - abs(surfaceNormal.y) * 0.84 + (climateNoise - 0.5) * 0.24,
        0.0,
        1.0
    );

    // Organic smooth mountain ranges (strictly confined to continental interiors; zero sharp abs() maze lines!).
    float mountainField = smoothMountainHeight(surfaceNormal);
    float inlandMask = smoothstep(0.54, 0.70, continentalMass);
    float highland = inlandMask * smoothstep(0.56, 0.78, mountainField);

    // Subtle organic surface detail (anti-aliased in smooth mode, crisp macro variation in pixel mode).
    float fineSurfaceDetail = isPixelStyle
        ? (0.65 * valueNoise(surfaceNormal * 42.0 + vec3(11.0, 23.0, 7.0))
            + 0.35 * valueNoise(surfaceNormal * 84.0 + vec3(3.0, 17.0, 29.0)))
        : (0.45 * filteredNoise(surfaceNormal * 65.0 + vec3(11.0, 23.0, 7.0))
            + 0.35 * filteredNoise(surfaceNormal * 150.0 + vec3(3.0, 17.0, 29.0))
            + 0.20 * filteredNoise(surfaceNormal * 320.0 + vec3(19.0, 7.0, 37.0)));

    // Gentle analytic finite-difference relief ONLY on mountains in Smooth mode (completely flat on plains & oceans!).
    float sunDot = dot(worldSurfaceNormal, sunDirection);
    float daylight = smoothstep(-0.06, 0.06, sunDot);
    float directLight = max(sunDot, 0.0);
    if (!isPixelStyle && land > 0.05 && highland > 0.02 && daylight > 0.01) {
        vec3 tX = normalize(cross(
            worldSurfaceNormal,
            abs(worldSurfaceNormal.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)
        ));
        vec3 tY = normalize(cross(worldSurfaceNormal, tX));
        float stepSize = 0.0022;
        float hX = smoothMountainHeight(toPlanetCoordinates(normalize(worldSurfaceNormal + tX * stepSize)));
        float hY = smoothMountainHeight(toPlanetCoordinates(normalize(worldSurfaceNormal + tY * stepSize)));
        vec3 mountainNormal = normalize(
            worldSurfaceNormal
                - tX * (hX - mountainField) * highland * 3.2
                - tY * (hY - mountainField) * highland * 3.2
        );
        directLight = mix(directLight, max(dot(mountainNormal, sunDirection), 0.0), land * highland);
    }

    float moonNight = 1.0 - smoothstep(-0.08, 0.16, SkyParams0.x);
    float moonLight = SkyParams0.y * moonNight * max(dot(worldSurfaceNormal, moonDirection), 0.0);

    // Rich sapphire oceans with glowing turquoise shelves.
    vec3 deepOcean = mix(vec3(0.003, 0.010, 0.028), vec3(0.012, 0.072, 0.23), daylight);
    vec3 coastalOcean = mix(vec3(0.006, 0.024, 0.048), vec3(0.028, 0.18, 0.34), daylight);
    vec3 shallowOcean = mix(vec3(0.010, 0.040, 0.055), vec3(0.07, 0.36, 0.40), daylight);
    float shelf = smoothstep(0.32, 0.46, continentNoise);
    float shallows = smoothstep(0.41, 0.485, continentNoise) * (1.0 - land);
    vec3 ocean = mix(deepOcean, coastalOcean, max(coast * 0.78, shelf * 0.58));
    ocean = mix(ocean, shallowOcean, shallows * 0.85);
    ocean *= 0.94 + fineSurfaceDetail * 0.12;

    // Distinct Minecraft biome colors in Pixel mode vs realistic Earth/Kerbin colors in Smooth mode!
    vec3 nightLand = vec3(0.010, 0.018, 0.018);
    vec3 grassland = isPixelStyle ? vec3(0.28, 0.50, 0.18) : vec3(0.19, 0.32, 0.14);
    vec3 forest = isPixelStyle ? vec3(0.12, 0.34, 0.13) : vec3(0.08, 0.21, 0.10);
    vec3 tropicalForest = isPixelStyle ? vec3(0.10, 0.42, 0.14) : vec3(0.07, 0.27, 0.12);
    vec3 savanna = isPixelStyle ? vec3(0.48, 0.44, 0.20) : vec3(0.38, 0.33, 0.16);
    vec3 desert = isPixelStyle ? vec3(0.76, 0.68, 0.42) : vec3(0.56, 0.44, 0.26);
    vec3 badlands = isPixelStyle ? vec3(0.72, 0.34, 0.18) : vec3(0.52, 0.28, 0.16);
    vec3 rockMountain = vec3(0.36, 0.34, 0.32);
    vec3 tundra = vec3(0.30, 0.36, 0.33);
    vec3 snow = vec3(0.84, 0.89, 0.93);

    vec3 warmBiome = mix(savanna, tropicalForest, smoothstep(0.36, 0.68, moisture));
    vec3 temperateBiome = mix(grassland, forest, smoothstep(0.40, 0.70, moisture));
    vec3 dayLand = mix(tundra, temperateBiome, smoothstep(0.12, 0.34, temperature));
    dayLand = mix(dayLand, warmBiome, smoothstep(0.48, 0.76, temperature));
    float desertCoverage = smoothstep(0.48, 0.76, temperature) * (1.0 - smoothstep(0.20, 0.46, moisture));
    vec3 aridBiome = mix(desert, badlands, smoothstep(0.55, 0.80, regionalNoise) * 0.40);
    dayLand = mix(dayLand, aridBiome, desertCoverage);
    dayLand = mix(dayLand, rockMountain, highland * 0.55);
    float snowCoverage = max(
        1.0 - smoothstep(0.06, 0.19, temperature),
        highland * (1.0 - smoothstep(0.30, 0.56, temperature)) * smoothstep(0.58, 0.80, mountainField)
    );
    dayLand = mix(dayLand, snow, clamp(snowCoverage, 0.0, 1.0));
    dayLand *= 0.84 + 0.32 * regionalNoise;
    vec3 landColor = mix(nightLand, dayLand, daylight);
    landColor *= 0.76 + fineSurfaceDetail * 0.42;

    vec3 surfaceColor = mix(ocean, landColor, land);

    // Sparkling orbital ocean sun-glint.
    vec3 waterHalfVector = normalize(sunDirection - viewDirection);
    float nh = max(dot(worldSurfaceNormal, waterHalfVector), 0.0);
    float broadGlint = pow(nh, 22.0) * 0.15;
    float sharpGlint = pow(nh, 120.0) * (isPixelStyle ? 0.25 : 0.55);
    float oceanGlint = (broadGlint + sharpGlint) * daylight * (1.0 - land);
    vec3 sunlightColor = solarTransmission(sunDot);
    surfaceColor += mix(vec3(0.76, 0.88, 1.0), vec3(1.0, 0.78, 0.52), 1.0 - sunlightColor.b) * oceanGlint;

    surfaceColor *= 0.025 + directLight * 0.975;
    surfaceColor *= mix(vec3(1.0), sunlightColor, directLight * 0.85);
    float terminatorTwilight = twilightScattering(sunDot);
    surfaceColor *= mix(vec3(1.0), vec3(1.14, 0.78, 0.56), terminatorTwilight * 0.48);
    surfaceColor += vec3(0.018, 0.028, 0.055) * moonLight * 0.85;

    // Cloud shadow projection onto land & ocean below.
    const float CLOUD_SURFACE_HEIGHT = 4800.0;
    float cloudSurfaceRadius = 1.0 + CLOUD_SURFACE_HEIGHT / planetRadius;
    float cloudRayProjection = cameraScale * viewDirection.y;
    float groundDiscriminant = cloudRayProjection * cloudRayProjection
        - (cameraScale * cameraScale - 1.0);
    float groundDistance = groundDiscriminant >= 0.0
        ? -cloudRayProjection - sqrt(groundDiscriminant)
        : -1.0;

    vec3 sunTangentGround = sunDirection - worldSurfaceNormal * sunDot;
    float stLen = length(sunTangentGround);
    if (daylight > 0.01 && stLen > 0.01) {
        float shadowOffset = clamp(0.0055 / max(sunDot + 0.25, 0.25), 0.004, 0.016);
        vec3 shadowWorld = normalize(worldSurfaceNormal + (sunTangentGround / stLen) * shadowOffset);
        vec3 shadowPlanet = toPlanetCoordinates(shadowWorld);
        if (isPixelStyle) {
            shadowPlanet = quantizeCubeSphere(shadowPlanet, 110.0);
        }
        vec2 shadowCloud = orbitalCloudField(shadowPlanet, false);
        surfaceColor *= 1.0 - shadowCloud.x * daylight * 0.48;
    }

    // Clustered coastal/temperate city lights on the night side (Realistic mode only).
    if (!isPixelStyle) {
        float urbanCluster = smoothstep(0.56, 0.84, valueNoise(smoothSurfaceNormal * 28.0 + vec3(9.0, 31.0, 17.0)));
        float habitable = smoothstep(0.18, 0.42, temperature) * (1.0 - smoothstep(0.75, 0.92, temperature))
            * (1.0 - highland * 0.8);
        float coastalBoost = 0.45 + 0.55 * shoreline;
        vec3 cityGrid = smoothSurfaceNormal * 320.0;
        vec3 cityCell = floor(cityGrid);
        vec3 cityLocal = fract(cityGrid) - 0.5;
        float citySeed = hashCell(cityCell);
        float cityRadius = mix(0.04, 0.12, hashCell(cityCell + vec3(13.0, 7.0, 3.0)));
        float citySpot = 1.0 - smoothstep(cityRadius * 0.55, cityRadius, length(cityLocal));
        float fineSprawl = smoothstep(0.76, 0.94, filteredNoise(smoothSurfaceNormal * 420.0 + vec3(4.0, 19.0, 27.0)));
        float cityMask = land * (1.0 - daylight) * habitable * coastalBoost * urbanCluster;
        float cityLights = cityMask * (step(0.94, citySeed) * citySpot * 0.55 + fineSprawl * 0.28);
        surfaceColor += cityLights * vec3(1.0, 0.62, 0.24) * 0.65;
    }

    float altitudeHaze = smoothstep(1024.0, 9000.0, cameraHeight);
    float lowAtmosphereVeil = mix(0.10, 0.24, altitudeHaze);
    vec3 hazeColor = mix(vec3(0.010, 0.022, 0.048), vec3(0.30, 0.52, 0.78), daylight);
    hazeColor = mix(hazeColor, vec3(0.96, 0.46, 0.26), terminatorTwilight * 0.60);
    surfaceColor = mix(surfaceColor, hazeColor, lowAtmosphereVeil * mix(0.12, 1.0, daylight));

    // Crisp orbital cloud deck: smooth swirling KSP-EVE clouds in Realistic mode,
    // crisp blocky Minecraft-like cloud deck in Pixel mode (zero bump artifacts!).
    float cloudDiscriminant = cloudRayProjection * cloudRayProjection
        - (cameraScale * cameraScale - cloudSurfaceRadius * cloudSurfaceRadius);
    if (cloudDiscriminant >= 0.0) {
        float cloudRoot = sqrt(cloudDiscriminant);
        float cloudNear = -cloudRayProjection - cloudRoot;
        float cloudFar = -cloudRayProjection + cloudRoot;
        float cloudDistance = cloudNear > 0.0 ? cloudNear : cloudFar;
        if (cloudDistance > 0.0 && (groundDistance <= 0.0 || cloudDistance < groundDistance)) {
            vec3 cloudPosition = vec3(0.0, cameraScale, 0.0) + viewDirection * cloudDistance;
            vec3 cloudNormal = normalize(cloudPosition);
            vec3 smoothCloudPlanet = toPlanetCoordinates(cloudNormal);
            vec3 cloudMapNormal = isPixelStyle
                ? quantizeCubeSphere(smoothCloudPlanet, 110.0)
                : smoothCloudPlanet;

            vec2 cloudSample = orbitalCloudField(cloudMapNormal, !isPixelStyle);
            float cloudDensity = isPixelStyle
                ? step(0.34, cloudSample.x) * 0.88
                : cloudSample.x;
            float cloudThickness = cloudSample.y;

            if (cloudDensity > 0.004) {
                float sampleSunDot = dot(cloudNormal, sunDirection);
                float sampleDaylight = smoothstep(-0.02, 0.08, sampleSunDot);
                float sampleMoonLight = SkyParams0.y * moonNight
                    * max(dot(cloudNormal, moonDirection), 0.0);

                vec3 cloudLitColor = vec3(1.00, 1.01, 1.03);
                vec3 cloudNightColor = vec3(0.025, 0.036, 0.062);
                vec3 sampleCloudColor = mix(cloudNightColor, cloudLitColor, sampleDaylight);
                sampleCloudColor *= mix(vec3(1.0), solarTransmission(sampleSunDot), sampleDaylight * 0.78);
                float cloudTwilight = twilightScattering(sampleSunDot);
                sampleCloudColor = mix(sampleCloudColor, vec3(1.08, 0.46, 0.24), cloudTwilight * 0.68);

                float diffuseLight = 0.55 + max(sampleSunDot, 0.0) * 0.50;
                float cloudSelfShadow = 1.0;

                // Clean matched-detail finite-difference shading ONLY in Realistic (Smooth) mode!
                if (!isPixelStyle) {
                    vec3 tangentX = normalize(cross(
                        cloudNormal,
                        abs(cloudNormal.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)
                    ));
                    vec3 tangentY = normalize(cross(cloudNormal, tangentX));
                    float bumpStep = 0.0022;
                    float baseBump = orbitalCloudField(smoothCloudPlanet, false).x;
                    float densityX = orbitalCloudField(
                        toPlanetCoordinates(normalize(cloudNormal + tangentX * bumpStep)), false
                    ).x;
                    float densityY = orbitalCloudField(
                        toPlanetCoordinates(normalize(cloudNormal + tangentY * bumpStep)), false
                    ).x;
                    vec3 cloudDetailNormal = normalize(
                        cloudNormal
                            - tangentX * (densityX - baseBump) * 2.8
                            - tangentY * (densityY - baseBump) * 2.8
                    );
                    float normalSunDot = max(dot(cloudDetailNormal, sunDirection), 0.0);
                    diffuseLight = 0.48 + normalSunDot * 0.58;

                    vec3 sunTangent = sunDirection - cloudNormal * sampleSunDot;
                    float sunTangentLength = length(sunTangent);
                    float sunwardShadow = 0.0;
                    if (sunTangentLength > 0.04) {
                        vec3 sunwardNormal = normalize(
                            cloudNormal + sunTangent * (0.010 / sunTangentLength)
                        );
                        float sunwardDensity = orbitalCloudField(toPlanetCoordinates(sunwardNormal), false).x;
                        sunwardShadow = smoothstep(0.03, 0.22, sunwardDensity - baseBump);
                    }
                    cloudSelfShadow = 1.0 - sampleDaylight * (cloudThickness * 0.06 + sunwardShadow * 0.18);
                }

                float forwardScatter = pow(max(dot(viewDirection, sunDirection), 0.0), 6.0)
                    * sampleDaylight * 0.22;
                float cloudLighting = mix(1.0, diffuseLight * cloudSelfShadow, sampleDaylight)
                    + sampleMoonLight * 0.22 + forwardScatter;

                sampleCloudColor += vec3(0.08, 0.12, 0.22) * sampleMoonLight;
                float cloudOpacity = isPixelStyle
                    ? cloudDensity
                    : clamp(1.0 - exp(-cloudDensity * 2.1), 0.0, 0.95);
                surfaceColor = mix(surfaceColor, sampleCloudColor * cloudLighting, cloudOpacity);
            }
        }
    }

    // KSP Scatterer-style crisp atmospheric Fresnel scattering & blazing blue/amber planetary limb!
    float viewCosine = clamp(dot(worldSurfaceNormal, -viewDirection), 0.0, 1.0);
    float airDensity = exp(-cameraHeight / 10000.0);
    float atmosphericOpacity = exp(-viewCosine * 10.5) * (0.24 + airDensity * 0.22);
    float viewSunAlignment = dot(viewDirection, sunDirection);
    float forwardScatter = pow(max(viewSunAlignment, 0.0), 7.0);
    float atmosphericDaylight = smoothstep(-0.06, 0.12, sunDot);
    float atmosphericTwilight = twilightScattering(sunDot);

    vec3 atmosphereColor = mix(
        vec3(0.001, 0.004, 0.014),
        vec3(0.28, 0.58, 0.96),
        atmosphericDaylight
    );
    float warmAtmosphere = atmosphericTwilight
        * mix(0.40, 1.0, smoothstep(-0.15, 0.45, viewSunAlignment));
    atmosphereColor = mix(atmosphereColor, vec3(1.02, 0.44, 0.22), warmAtmosphere * 0.78);
    atmosphereColor += forwardScatter * atmosphericDaylight * vec3(0.12, 0.16, 0.24);

    float rimSunlight = smoothstep(-0.02, 0.16, sunDot);
    float rimTwilight = atmosphericTwilight
        * mix(0.40, 1.0, smoothstep(-0.15, 0.45, viewSunAlignment));
    vec3 rimColor = mix(
        vec3(0.015, 0.045, 0.11),
        vec3(0.26, 0.64, 1.05),
        rimSunlight
    );
    rimColor = mix(rimColor, vec3(1.08, 0.42, 0.18), rimTwilight);

    atmosphereColor = mix(atmosphereColor, rimColor, 0.48 * max(rimSunlight, rimTwilight));
    surfaceColor = mix(surfaceColor, atmosphereColor, clamp(atmosphericOpacity, 0.0, 0.92));

    float rimWide = exp(-viewCosine * 5.2);
    float rimTight = exp(-viewCosine * 14.0);
    float rimIntensity = (rimWide * 0.55 + rimTight * 0.65)
        * (0.002 + 0.75 * rimSunlight + 0.95 * rimTwilight);
    surfaceColor += rimColor * rimIntensity;

    vec3 finalRgb = mix(skyColor.rgb, softFilmicRollOff(surfaceColor), coverage);
    fragColor = vec4(clamp(finalRgb, 0.0, 1.0), skyColor.a);
}
