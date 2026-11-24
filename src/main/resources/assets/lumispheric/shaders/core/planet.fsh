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
// x = gameTimeSeconds, y = cloudWindAngle
uniform vec4 TimeParams;
// 4 simultaneous meteor slots: Start = (startAz, startEl, azDelta, elDelta), State = (progress, active, colorType, 0)
uniform vec4 Meteor0Start;
uniform vec4 Meteor0State;
uniform vec4 Meteor1Start;
uniform vec4 Meteor1State;
uniform vec4 Meteor2Start;
uniform vec4 Meteor2State;
uniform vec4 Meteor3Start;
uniform vec4 Meteor3State;
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
    // Quintic C2-continuous interpolation prevents grid-edge creases.
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

vec2 orbitalCloudField(vec3 normal, bool includeFineWisps) {
    vec3 windAxis = normalize(vec3(0.12, 0.98, 0.14));
    vec3 moved = rotateAroundAxis(normal, windAxis, TimeParams.y);

    float lat = moved.y;
    vec3 stretched = vec3(moved.x, moved.y * 1.40, moved.z);

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

// Fast screen-space normal perturbation for smooth orbital clouds (avoids 3 extra 3D-noise field evaluations per pixel).
vec3 cloudScreenSpaceNormal(vec3 spherePos, float height, float strength) {
    vec3 pDx = dFdx(spherePos);
    vec3 pDy = dFdy(spherePos);
    float hDx = dFdx(height);
    float hDy = dFdy(height);
    float xx = dot(pDx, pDx);
    float xy = dot(pDx, pDy);
    float yy = dot(pDy, pDy);
    float det = xx * yy - xy * xy;
    if (det <= 1.0e-14) {
        return spherePos;
    }
    vec3 grad = (pDx * (yy * hDx - xy * hDy) + pDy * (xx * hDy - xy * hDx)) / det;
    // Clamp gradient magnitude so distant grazing horizon pixels never spike.
    float gradLen = length(grad);
    if (gradLen > 24.0) {
        grad *= 24.0 / gradLen;
    }
    return normalize(spherePos - grad * strength);
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

vec3 evalSingleMeteor(float elevation, float azimuth, vec4 mStart, vec4 mState) {
    if (mState.y < 0.5) {
        return vec3(0.0);
    }
    float progress = mState.x;
    float tail = max(0.0, progress - 0.30);
    float headAz = mStart.x + mStart.z * progress;
    float tailAz = mStart.x + mStart.z * tail;
    float headEl = mStart.y + mStart.w * progress;
    float tailEl = mStart.y + mStart.w * tail;

    // Fast bounding check avoids per-pixel sqrt/exp far from the meteor streak.
    float minEl = min(headEl, tailEl) - 0.025;
    float maxEl = max(headEl, tailEl) + 0.025;
    if (elevation < minEl || elevation > maxEl) {
        return vec3(0.0);
    }

    float azScale = max(0.15, cos((headEl + tailEl) * 0.5));
    float dx = wrapRadians(azimuth - tailAz) * azScale;
    float dy = elevation - tailEl;
    float vx = wrapRadians(headAz - tailAz) * azScale;
    float vy = headEl - tailEl;
    float lenSq = vx * vx + vy * vy;
    float along = lenSq > 1.0e-7 ? clamp((dx * vx + dy * vy) / lenSq, 0.0, 1.0) : 0.0;
    float distSq = dot(vec2(dx - along * vx, dy - along * vy), vec2(dx - along * vx, dy - along * vy));
    if (distSq > 0.0004) {
        return vec3(0.0);
    }

    float width = mix(0.0019, 0.0052, along);
    float core = exp(-distSq / (width * width));
    float envelope = smoothstep(0.0, 0.12, progress) * (1.0 - smoothstep(0.74, 1.0, progress));
    float trailProfile = pow(along, 1.6);
    float intensity = core * trailProfile * envelope;
    if (intensity <= 0.001) {
        return vec3(0.0);
    }

    // Color by meteor origin: 0 = sporadic cool white, 1 = Perseids (cyan-emerald), 2 = Geminids (golden-amber).
    vec3 wakeColor;
    vec3 headColor;
    if (mState.z > 1.5) {
        wakeColor = vec3(1.00, 0.68, 0.28);
        headColor = vec3(1.00, 0.96, 0.80);
    } else if (mState.z > 0.5) {
        wakeColor = vec3(0.42, 0.95, 0.86);
        headColor = vec3(0.95, 1.00, 1.00);
    } else {
        wakeColor = vec3(0.65, 0.85, 1.00);
        headColor = vec3(1.00, 0.97, 0.88);
    }
    return mix(wakeColor, headColor, smoothstep(0.5, 0.98, along)) * intensity * 1.32;
}

vec3 computeMeteors(vec3 viewDir, float elevation) {
    if (Meteor0State.y + Meteor1State.y + Meteor2State.y + Meteor3State.y < 0.5) {
        return vec3(0.0);
    }
    float azimuth = atan(viewDir.z, viewDir.x);
    vec3 total = vec3(0.0);
    if (Meteor0State.y > 0.5) total += evalSingleMeteor(elevation, azimuth, Meteor0Start, Meteor0State);
    if (Meteor1State.y > 0.5) total += evalSingleMeteor(elevation, azimuth, Meteor1Start, Meteor1State);
    if (Meteor2State.y > 0.5) total += evalSingleMeteor(elevation, azimuth, Meteor2Start, Meteor2State);
    if (Meteor3State.y > 0.5) total += evalSingleMeteor(elevation, azimuth, Meteor3Start, Meteor3State);
    return total;
}

vec3 computeAtmosphere(vec3 viewDir, float elevation) {
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
    float sunElev = asin(clamp(sunElevationSin, -1.0, 1.0));
    float apparentSunElev = sunElev + geometricDip;

    float groundNight = 1.0 - smoothstep(-0.28, 0.04, sunElev);
    float highAltNight = 1.0 - smoothstep(-0.32, 0.02, apparentSunElev);
    float nightBlend = mix(groundNight, highAltNight, altitudeBlend);
    float horizonNight = mix(nightBlend, 1.0 - smoothstep(-0.16, 0.22, sunDot), spaceBlend);

    float twilightLight = twilightStrength * (1.0 - rain * 0.75);
    if (twilightLight > 0.001) {
        vec3 twilightZenith = mix(vec3(0.07, 0.14, 0.38), vec3(0.03, 0.055, 0.17), blueHour);
        sky = mix(sky, twilightZenith, twilightLight * (1.0 - horizon) * (1.0 - spaceBlend) * 0.72);
    }

    vec3 nightSky = mix(vec3(0.004, 0.007, 0.017), vec3(0.015, 0.024, 0.042), horizon);
    sky = mix(sky, nightSky, horizonNight);

    if (spaceBlend > 0.001) {
        float atmosphereHeight = max(0.0, skyElevation) / 0.045;
        float upperAtmosphere = spaceBlend * exp(-atmosphereHeight * atmosphereHeight);
        vec3 upperColor = mix(vec3(0.06, 0.17, 0.48), vec3(0.005, 0.008, 0.022), horizonNight);
        sky = mix(sky, upperColor, upperAtmosphere * 0.85);
    }

    float moonAbove = smoothstep(-0.08, 0.14, moonDir.y);
    float lunarFactor = moonIllumination * horizonNight * (1.0 - rain * 0.85) * (1.0 - spaceBlend * 0.75);
    if (lunarFactor > 0.001 && moonAbove > 0.001) {
        float moonDot = max(dot(viewDir, moonDir), 0.0);
        float moonHalo = pow(moonDot, 10.0) * 0.036 + pow(moonDot, 48.0) * 0.045;
        float moonAmbient = (0.004 + 0.006 * horizon) * moonAbove;
        sky += vec3(0.42, 0.62, 0.96) * (moonHalo * moonAbove + moonAmbient) * lunarFactor;
    }

    float dayFactor = (1.0 - horizonNight) * (1.0 - rain);
    if (dayFactor > 0.001) {
        float rayleighPhase = 0.75 * (1.0 + sunDot * sunDot);
        float rayleighScatter = dayFactor * rayleighPhase * (0.35 + 0.65 * horizon);
        sky += vec3(0.012, 0.036, 0.090) * rayleighScatter;
    }

    if (spaceBlend > 0.001) {
        float limbCenter = elevation + geometricDip - 0.028;
        float blueLimbWidth = 0.032 + spaceBlend * 0.014;
        float blueLimbDist = limbCenter / blueLimbWidth;
        float blueLimb = exp(-blueLimbDist * blueLimbDist)
            * spaceBlend * (1.0 - horizonNight * 0.65) * (1.0 - rain * 0.5)
            * smoothstep(-0.18, 0.30, sunDot);
        sky += vec3(0.04, 0.16, 0.42) * blueLimb;

        if (twilightStrength > 0.001) {
            float warmLimbWidth = 0.042 + spaceBlend * 0.02;
            float warmLimbDist = (elevation + geometricDip - 0.006) / warmLimbWidth;
            float warmLimb = exp(-warmLimbDist * warmLimbDist)
                * spaceBlend * twilightStrength * smoothstep(0.50, 0.95, sunDot)
                * (1.0 - rain * 0.5) * 0.95;
            sky += vec3(0.24, 0.08, 0.025) * warmLimb;
        }
    }

    float sunAngle = 0.0;
    if (twilightLight > 0.001 || eclipse > 0.001) {
        sunAngle = acos(sunDot);
    }

    if (twilightLight > 0.001) {
        float sunAngleSq = sunAngle * sunAngle;
        float sunwardHorizon = exp(-sunAngleSq / 0.88);
        float tighterSunward = exp(-sunAngleSq / 0.30);
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

        float wideGlow = exp(-sunAngleSq / 0.32) * twilightLight;
        float coreGlow = exp(-sunAngleSq / 0.065) * twilightLight;
        sky += mix(vec3(0.42, 0.20, 0.06), vec3(0.32, 0.10, 0.08), blueHour) * wideGlow * 0.65;
        sky += mix(vec3(0.58, 0.40, 0.16), vec3(0.42, 0.18, 0.08), blueHour) * coreGlow * 0.85;
    }

    if (mieIntensity > 0.001 && horizonNight < 0.995) {
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
    }

    if (rain > 0.001) {
        float luminance = dot(sky, vec3(0.2126, 0.7152, 0.0722));
        sky.r = mix(sky.r, luminance * 0.88, rain * 0.62);
        sky.g = mix(sky.g, luminance * 0.94, rain * 0.48);
        sky.b = mix(sky.b, min(1.0, luminance * 1.04 + 0.045), rain * 0.42);
    }

    sky += computeAurora(viewDir, elevation, auroraStrength, auroraHemisphere, TimeParams.x, pixelatedStyle);
    sky += computeMeteors(viewDir, elevation);

    if (eclipse > 0.001) {
        float eclipseDarkening = eclipse * 0.85;
        sky *= 1.0 - eclipseDarkening;
        float eclipseHorizon = exp(-abs(skyElevation) * 7.2) * eclipse * (1.0 - spaceBlend);
        sky = mix(sky, vec3(0.68, 0.34, 0.16), eclipseHorizon * 0.56);

        // Only evaluate solar corona trigonometry within ~16 degrees of the eclipsed Sun (~3% of sky).
        if (sunAngle < 0.28) {
            vec3 sunRef = abs(sunDir.y) > 0.95 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
            vec3 sunRight = normalize(cross(sunRef, sunDir));
            vec3 sunUpVec = normalize(cross(sunDir, sunRight));
            float coronaAngle = atan(dot(viewDir, sunUpVec), dot(viewDir, sunRight));
            float streamerWave = 0.72 + 0.28 * cos(coronaAngle * 2.0 + 0.45)
                + 0.14 * sin(coronaAngle * 5.0 - 0.8);
            float radialSpan = 0.055 * streamerWave;
            float coronaDist = max(sunAngle - 0.032, 0.0) / radialSpan;
            float outerCorona = exp(-coronaDist * coronaDist * 0.85) * eclipse * (0.65 + 0.35 * spaceBlend);
            float innerCorona = exp(-pow(max(sunAngle - 0.028, 0.0) / 0.024, 2.0)) * eclipse * 0.92;
            vec3 coronaCol = mix(vec3(0.96, 0.90, 0.78), vec3(0.95, 0.97, 1.00), spaceBlend * 0.75 + 0.25);
            sky += coronaCol * clamp(outerCorona * 0.72 + innerCorona, 0.0, 1.15);
        }
    }

    if (spaceBlend > 0.001) {
        vec3 spaceColor = mix(vec3(0.002, 0.004, 0.012), vec3(0.001, 0.002, 0.006), horizonNight);
        float apparentElev = elevation + horizonDip + horizonDrop * 0.5;
        float spaceFacing = smoothstep(0.02, 0.14, abs(apparentElev));
        sky = mix(sky, spaceColor, spaceBlend * spaceFacing);
    }

    return clamp(softFilmicRollOff(sky), 0.0, 1.0);
}

// Computes the Moon's cylindrical umbra/penumbra shadow cast onto the planet surface, clouds,
// and orbital atmosphere during a solar eclipse. Returns vec2(shadowDarkening, warmPenumbraRing).
vec2 orbitalEclipseShadow(vec3 normal, vec3 sunDir, vec3 moonDir, float eclipse) {
    if (eclipse <= 0.001) {
        return vec2(0.0);
    }
    vec3 shadowCenter = normalize(vec3(0.0, 1.0, 0.0) + (moonDir - sunDir) * 3.5);
    vec3 offset = normal - shadowCenter;
    vec3 perp = offset - sunDir * dot(offset, sunDir);
    float dist = length(perp);
    float umbra = (1.0 - smoothstep(0.032, 0.118, dist)) * eclipse;
    float penumbra = (1.0 - smoothstep(0.055, 0.345, dist)) * eclipse;
    float shadow = clamp(umbra * 0.90 + penumbra * 0.42, 0.0, 0.92);
    float warmRing = smoothstep(0.025, 0.115, dist) * (1.0 - smoothstep(0.115, 0.275, dist)) * eclipse;
    return vec2(shadow, warmRing);
}

void main() {
    vec3 viewDirection = normalize(localPosition);
    float elevation = asin(clamp(viewDirection.y, -1.0, 1.0));

    float planetBlend = PlanetParams.y;
    float horizon = -SkyParams1.y;
    float coverage = planetBlend <= 0.001
        ? 0.0
        : planetBlend * (1.0 - smoothstep(horizon - 0.012, horizon + PLANET_HORIZON_TOP_FADE, elevation));

    // Skip computing atmosphere completely on opaque orbital planet pixels (saves ~30% GPU time in orbit).
    vec4 skyColor = vec4(0.0, 0.0, 0.0, 1.0);
    if (coverage < 0.999) {
        vec3 skyRgb = computeAtmosphere(viewDirection, elevation);
        skyColor = vec4(skyRgb, 1.0) * vertexColor * ColorModulator;
        if (coverage <= 0.001) {
            fragColor = skyColor;
            return;
        }
    }

    float planetRadius = PlanetParams.x;
    float cameraHeight = SkyParams1.z;
    bool isPixelStyle = TwilightExtra.w > 0.5;

    float cameraScale = 1.0 + cameraHeight / planetRadius;
    vec3 worldSurfaceNormal = sphereNormalAt(viewDirection, cameraScale, 1.0);
    vec3 smoothSurfaceNormal = toPlanetCoordinates(worldSurfaceNormal);
    vec3 surfaceNormal = isPixelStyle
        ? quantizeCubeSphere(smoothSurfaceNormal, 140.0)
        : smoothSurfaceNormal;

    vec3 sunDirection = normalize(SunDirection);
    vec3 moonDirection = normalize(MoonDirection);

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

    float sunDot = dot(worldSurfaceNormal, sunDirection);
    float rawDaylight = smoothstep(-0.06, 0.06, sunDot);
    float eclipseStrength = SkyParams0.w;
    vec2 surfaceEclipse = orbitalEclipseShadow(worldSurfaceNormal, sunDirection, moonDirection, eclipseStrength);
    float surfaceEclipseShadow = surfaceEclipse.x * rawDaylight;
    float surfaceEclipseWarm = surfaceEclipse.y * rawDaylight;
    float daylight = rawDaylight * (1.0 - surfaceEclipseShadow * 0.88);
    float directLight = max(sunDot, 0.0) * (1.0 - surfaceEclipseShadow);

    // Skip expensive fine surface noise on deep night-side pixels where surface albedo is nearly dark.
    float fineSurfaceDetail = 0.5;
    if (daylight > 0.002) {
        fineSurfaceDetail = isPixelStyle
            ? (0.65 * valueNoise(surfaceNormal * 42.0 + vec3(11.0, 23.0, 7.0))
                + 0.35 * valueNoise(surfaceNormal * 84.0 + vec3(3.0, 17.0, 29.0)))
            : (0.45 * filteredNoise(surfaceNormal * 65.0 + vec3(11.0, 23.0, 7.0))
                + 0.35 * filteredNoise(surfaceNormal * 150.0 + vec3(3.0, 17.0, 29.0))
                + 0.20 * filteredNoise(surfaceNormal * 320.0 + vec3(19.0, 7.0, 37.0)));
    }
    float temperature = 0.5;
    float highland = 0.0;

    vec3 landColor = vec3(0.0);
    // Skip biome, climate, and mountain noise completely on pure ocean pixels!
    if (land > 0.005) {
        float moisture = 0.62 * valueNoise(surfaceNormal * 6.8 + vec3(19.0, 7.0, 29.0))
            + 0.38 * valueNoise(surfaceNormal * 15.5 + vec3(3.0, 23.0, 11.0));
        float climateNoise = valueNoise(surfaceNormal * 5.2 + vec3(31.0, 17.0, 9.0));
        temperature = clamp(
            1.0 - abs(surfaceNormal.y) * 0.84 + (climateNoise - 0.5) * 0.24,
            0.0,
            1.0
        );

        float mountainField = smoothMountainHeight(surfaceNormal);
        float inlandMask = smoothstep(0.54, 0.70, continentalMass);
        highland = inlandMask * smoothstep(0.56, 0.78, mountainField);

        if (!isPixelStyle && highland > 0.02 && daylight > 0.01) {
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
        landColor = mix(nightLand, dayLand, daylight) * (0.76 + fineSurfaceDetail * 0.42);
    }

    vec3 surfaceColor;
    if (land >= 0.995) {
        surfaceColor = landColor;
    } else {
        vec3 deepOcean = mix(vec3(0.003, 0.010, 0.028), vec3(0.012, 0.072, 0.23), daylight);
        vec3 coastalOcean = mix(vec3(0.006, 0.024, 0.048), vec3(0.028, 0.18, 0.34), daylight);
        vec3 shallowOcean = mix(vec3(0.010, 0.040, 0.055), vec3(0.07, 0.36, 0.40), daylight);
        float shelf = smoothstep(0.32, 0.46, continentNoise);
        float shallows = smoothstep(0.41, 0.485, continentNoise) * (1.0 - land);
        vec3 ocean = mix(deepOcean, coastalOcean, max(coast * 0.78, shelf * 0.58));
        ocean = mix(ocean, shallowOcean, shallows * 0.85);
        ocean *= 0.94 + fineSurfaceDetail * 0.12;
        surfaceColor = mix(ocean, landColor, land);
    }

    vec3 sunlightColor = solarTransmission(sunDot);
    if (land < 0.99 && daylight > 0.01) {
        vec3 waterHalfVector = normalize(sunDirection - viewDirection);
        float nh = max(dot(worldSurfaceNormal, waterHalfVector), 0.0);
        float broadGlint = pow(nh, 22.0) * 0.15;
        float sharpGlint = pow(nh, 120.0) * (isPixelStyle ? 0.25 : 0.55);
        float oceanGlint = (broadGlint + sharpGlint) * daylight * (1.0 - land);
        surfaceColor += mix(vec3(0.76, 0.88, 1.0), vec3(1.0, 0.78, 0.52), 1.0 - sunlightColor.b) * oceanGlint;
    }

    float moonNight = 1.0 - smoothstep(-0.08, 0.16, SkyParams0.x);
    float moonLight = SkyParams0.y * moonNight * max(dot(worldSurfaceNormal, moonDirection), 0.0);
    surfaceColor *= 0.025 + directLight * 0.975;
    surfaceColor *= mix(vec3(1.0), sunlightColor, directLight * 0.85);
    float terminatorTwilight = twilightScattering(sunDot);
    surfaceColor *= mix(vec3(1.0), vec3(1.14, 0.78, 0.56), clamp(terminatorTwilight * 0.48 + surfaceEclipseWarm * 0.42, 0.0, 0.65));
    surfaceColor += vec3(0.018, 0.028, 0.055) * moonLight * 0.85;

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

    // Night city lights only in Realistic mode on dark land pixels.
    if (!isPixelStyle && land > 0.02 && daylight < 0.98) {
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
    hazeColor = mix(hazeColor, vec3(0.96, 0.46, 0.26), clamp(terminatorTwilight * 0.60 + surfaceEclipseWarm * 0.55, 0.0, 0.80));
    surfaceColor = mix(surfaceColor, hazeColor, lowAtmosphereVeil * mix(0.12, 1.0, daylight));

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

            // Compute fast screen-space cloud bump before conditional branch so derivatives stay quad-coherent!
            vec3 cloudDetailNormal = isPixelStyle
                ? cloudNormal
                : cloudScreenSpaceNormal(cloudNormal, cloudSample.x, 0.0085);

            if (cloudDensity > 0.004) {
                float sampleSunDot = dot(cloudNormal, sunDirection);
                float sampleRawDaylight = smoothstep(-0.02, 0.08, sampleSunDot);
                vec2 cloudEclipse = orbitalEclipseShadow(cloudNormal, sunDirection, moonDirection, eclipseStrength);
                float cloudEclipseShadow = cloudEclipse.x * sampleRawDaylight;
                float cloudEclipseWarm = cloudEclipse.y * sampleRawDaylight;
                float sampleDaylight = sampleRawDaylight * (1.0 - cloudEclipseShadow * 0.90);
                float sampleMoonLight = SkyParams0.y * moonNight
                    * max(dot(cloudNormal, moonDirection), 0.0);

                vec3 cloudLitColor = vec3(1.00, 1.01, 1.03);
                vec3 cloudNightColor = vec3(0.025, 0.036, 0.062);
                vec3 sampleCloudColor = mix(cloudNightColor, cloudLitColor, sampleDaylight);
                sampleCloudColor *= mix(vec3(1.0), solarTransmission(sampleSunDot), sampleDaylight * 0.78);
                float cloudTwilight = twilightScattering(sampleSunDot);
                sampleCloudColor = mix(
                    sampleCloudColor,
                    vec3(1.08, 0.46, 0.24),
                    clamp(cloudTwilight * 0.68 + cloudEclipseWarm * 0.55, 0.0, 0.80)
                );

                float diffuseLight = 0.55 + max(sampleSunDot, 0.0) * 0.50;
                float cloudSelfShadow = 1.0;

                if (!isPixelStyle && sampleDaylight > 0.01) {
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
                        sunwardShadow = smoothstep(0.03, 0.22, sunwardDensity - cloudSample.x);
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

    float viewCosine = clamp(dot(worldSurfaceNormal, -viewDirection), 0.0, 1.0);
    float airDensity = exp(-cameraHeight / 10000.0);
    float atmosphericOpacity = exp(-viewCosine * 10.5) * (0.24 + airDensity * 0.22);
    float viewSunAlignment = dot(viewDirection, sunDirection);
    float forwardScatter = pow(max(viewSunAlignment, 0.0), 7.0);
    float atmosphericDaylight = smoothstep(-0.06, 0.12, sunDot) * (1.0 - surfaceEclipseShadow * 0.86);
    float atmosphericTwilight = clamp(twilightScattering(sunDot) + surfaceEclipseWarm * 0.72, 0.0, 1.0);

    vec3 atmosphereColor = mix(
        vec3(0.001, 0.004, 0.014),
        vec3(0.28, 0.58, 0.96),
        atmosphericDaylight
    );
    float warmAtmosphere = atmosphericTwilight
        * mix(0.40, 1.0, smoothstep(-0.15, 0.45, viewSunAlignment));
    atmosphereColor = mix(atmosphereColor, vec3(1.02, 0.44, 0.22), warmAtmosphere * 0.78);
    atmosphereColor += forwardScatter * atmosphericDaylight * vec3(0.12, 0.16, 0.24);

    float rimSunlight = smoothstep(-0.02, 0.16, sunDot) * (1.0 - surfaceEclipseShadow * 0.86);
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

    vec3 finalRgb = coverage >= 0.999
        ? softFilmicRollOff(surfaceColor)
        : mix(skyColor.rgb, softFilmicRollOff(surfaceColor), coverage);
    fragColor = vec4(clamp(finalRgb, 0.0, 1.0), 1.0);
}
