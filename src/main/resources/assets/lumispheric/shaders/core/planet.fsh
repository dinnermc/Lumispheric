#version 150

uniform vec3 SunDirection;
uniform vec3 MoonDirection;
uniform float SunElevation;
uniform float MoonIllumination;
uniform float CloudWindAngle;
uniform float PlanetRadius;
uniform float CameraHeight;
uniform float HorizonDip;
uniform float PlanetBlend;
uniform vec2 PlanetRotation;
uniform vec4 ColorModulator;

in vec3 localPosition;
in vec4 vertexColor;

out vec4 fragColor;

const float PLANET_HORIZON_TOP_FADE = 0.018;

float hashCell(vec3 cell) {
    vec3 value = fract(cell * vec3(0.1031, 0.1030, 0.0973));
    value += dot(value, value.yzx + 33.33);
    return fract((value.x + value.y) * value.z);
}

float valueNoise(vec3 position) {
    vec3 cell = floor(position);
    vec3 blend = fract(position);
    blend = blend * blend * (3.0 - 2.0 * blend);

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

vec3 rotateAroundAxis(vec3 value, vec3 axis, float angle) {
    float sine = sin(angle);
    float cosine = cos(angle);
    return value * cosine
        + cross(axis, value) * sine
        + axis * dot(axis, value) * (1.0 - cosine);
}

vec3 toPlanetCoordinates(vec3 normal) {
    vec3 rotated = rotateAroundAxis(normal, vec3(0.0, 0.0, 1.0), PlanetRotation.x);
    return rotateAroundAxis(rotated, vec3(1.0, 0.0, 0.0), PlanetRotation.y);
}

vec3 fromPlanetCoordinates(vec3 normal) {
    vec3 rotated = rotateAroundAxis(normal, vec3(1.0, 0.0, 0.0), -PlanetRotation.y);
    return rotateAroundAxis(rotated, vec3(0.0, 0.0, 1.0), -PlanetRotation.x);
}

float cloudShapeAt(vec3 normal) {
    vec3 windAxis = normalize(vec3(0.28, 0.91, 0.30));
    float windAngle = CloudWindAngle;
    vec3 movingNormal = rotateAroundAxis(normal, windAxis, windAngle);
    vec3 coordinates = movingNormal * 44.0;
    float warp = valueNoise(coordinates * 0.12 + vec3(17.0, 31.0, 11.0)) - 0.5;
    vec3 warpedCoordinates = coordinates
        + vec3(warp, -warp * 0.7, warp * 0.5) * 2.4;
    float broadMass = valueNoise(warpedCoordinates * 0.56);
    float cloudForms = valueNoise(warpedCoordinates * 1.12 + vec3(7.0, 19.0, 37.0));
    float fineDetail = valueNoise(
        warpedCoordinates * 2.35 + vec3(23.0, 5.0, 13.0)
    );
    return 0.61 * broadMass + 0.24 * cloudForms + 0.15 * fineDetail;
}

float cloudDensityAt(vec3 normal) {
    float shape = cloudShapeAt(normal);
    float coverage = smoothstep(0.52, 0.64, shape);
    float interiorTexture = smoothstep(0.60, 0.95, shape);
    return coverage * mix(0.80, 1.0, interiorTexture);
}

float twilightScattering(float sunCosine) {
    return exp(-max(sunCosine, 0.0) / 0.22)
        * exp(-max(-sunCosine, 0.0) / 0.055);
}

vec3 solarTransmission(float sunCosine) {
    float airMass = min(12.0, 1.0 / max(sunCosine, 0.035));
    float opticalDepth = max(airMass - 1.0, 0.0);
    return exp(-vec3(0.12, 0.34, 0.78) * opticalDepth * 0.045);
}

float filteredTerrainNoise(vec3 normal, float frequency) {
    vec3 coordinates = normal * frequency;
    float pixelFootprint = max(
        max(fwidth(coordinates.x), fwidth(coordinates.y)),
        fwidth(coordinates.z)
    );
    float detailVisibility = 1.0 - smoothstep(0.45, 0.95, pixelFootprint);
    if (detailVisibility <= 0.0) {
        return 0.5;
    }
    return mix(0.5, valueNoise(coordinates), detailVisibility);
}

float filteredTerrainRidges(vec3 normal, float frequency) {
    float pixelFootprint = max(
        max(fwidth(normal.x * frequency), fwidth(normal.y * frequency)),
        fwidth(normal.z * frequency)
    );
    float detailVisibility = 1.0 - smoothstep(0.45, 0.95, pixelFootprint);
    if (detailVisibility <= 0.0) {
        return 0.5;
    }
    float noise = filteredTerrainNoise(normal, frequency);
    float ridges = 1.0 - abs(2.0 * noise - 1.0);
    return mix(0.5, ridges, detailVisibility);
}

vec3 terrainReliefNormal(vec3 normal, float height, float strength) {
    vec3 positionDx = dFdx(normal);
    vec3 positionDy = dFdy(normal);
    float heightDx = dFdx(height);
    float heightDy = dFdy(height);
    float xx = dot(positionDx, positionDx);
    float xy = dot(positionDx, positionDy);
    float yy = dot(positionDy, positionDy);
    float gramDeterminant = max(xx * yy - xy * xy, 1.0e-16);
    vec3 gradient = (
        positionDx * (yy * heightDx - xy * heightDy)
            + positionDy * (xx * heightDy - xy * heightDx)
    ) / gramDeterminant;
    return normalize(normal - gradient * strength);
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

void main() {
    vec4 skyColor = vertexColor * ColorModulator;
    if (PlanetBlend <= 0.001) {
        fragColor = skyColor;
        return;
    }

    vec3 viewDirection = normalize(localPosition);
    float elevation = asin(clamp(viewDirection.y, -1.0, 1.0));
    float horizon = -HorizonDip;
    float coverage = PlanetBlend * (1.0 - smoothstep(
        horizon - 0.012, horizon + PLANET_HORIZON_TOP_FADE, elevation
    ));
    if (coverage <= 0.001) {
        fragColor = skyColor;
        return;
    }

    float cameraScale = 1.0 + CameraHeight / PlanetRadius;
    vec3 worldSurfaceNormal = sphereNormalAt(viewDirection, cameraScale, 1.0);
    vec3 surfaceNormal = toPlanetCoordinates(worldSurfaceNormal);
    vec3 sunDirection = normalize(SunDirection);
    vec3 moonDirection = normalize(MoonDirection);
    float continentalWarp = valueNoise(
        surfaceNormal * 2.4 + vec3(17.0, 41.0, 9.0)
    ) - 0.5;
    float regionalWarp = valueNoise(
        surfaceNormal * 5.2 + vec3(7.0, 23.0, 37.0)
    ) - 0.5;
    vec3 continentalCoordinates = surfaceNormal * 4.2
        + vec3(
            continentalWarp,
            regionalWarp,
            continentalWarp * 0.45 - regionalWarp * 0.30
        ) * 1.25;
    float continentalMass = valueNoise(continentalCoordinates);
    float regionalNoise = valueNoise(
        continentalCoordinates * 1.65 + vec3(13.0, 29.0, 7.0)
    );
    float islandNoise = valueNoise(
        continentalCoordinates * 3.2 + vec3(31.0, 11.0, 23.0)
    );
    float continentalCore = 0.72 * continentalMass
        + 0.22 * regionalNoise
        + 0.06 * islandNoise;
    float shoreline = 1.0 - smoothstep(
        0.015, 0.12, abs(continentalCore - 0.50)
    );
    float coastlineDetail = valueNoise(
        continentalCoordinates * 5.6 + vec3(23.0, 7.0, 31.0)
    );
    float continentNoise = continentalCore
        + (coastlineDetail - 0.5) * 0.085 * shoreline;
    float land = smoothstep(0.48, 0.53, continentNoise);
    float coast = smoothstep(0.34, 0.43, continentNoise)
        * (1.0 - smoothstep(0.465, 0.53, continentNoise));
    float terrainCoarse = filteredTerrainNoise(surfaceNormal, 128.0);
    float terrainMedium = filteredTerrainNoise(
        surfaceNormal + vec3(0.05, 0.027, 0.074), 256.0
    );
    float terrainFine = filteredTerrainNoise(
        surfaceNormal + vec3(0.0605, 0.0215, 0.0098), 512.0
    );
    float terrainRidges = filteredTerrainRidges(
        surfaceNormal + vec3(0.0078, 0.0599, 0.0443), 192.0
    );
    float terrainDetail = 0.30 * terrainCoarse
        + 0.32 * terrainMedium
        + 0.20 * terrainFine
        + 0.18 * terrainRidges;
    float moisture = 0.66 * valueNoise(
        surfaceNormal * 6.5 + vec3(19.0, 7.0, 29.0)
    ) + 0.34 * valueNoise(surfaceNormal * 14.0 + vec3(3.0, 23.0, 11.0));
    float climateNoise = valueNoise(surfaceNormal * 5.0 + vec3(31.0, 17.0, 9.0));
    float temperature = clamp(
        1.0 - abs(surfaceNormal.y) * 0.82 + (climateNoise - 0.5) * 0.22,
        0.0,
        1.0
    );
    float elevationNoise = 0.58 * valueNoise(
        surfaceNormal * 24.0 + vec3(41.0, 13.0, 5.0)
    ) + 0.42 * valueNoise(surfaceNormal * 57.0 + vec3(7.0, 37.0, 17.0));
    float mountainRidges = 1.0 - abs(2.0 * regionalNoise - 1.0);
    float mountainBelts = smoothstep(0.48, 0.64, continentalMass)
        * smoothstep(0.62, 0.82, mountainRidges);
    float highland = max(
        smoothstep(0.72, 0.88, elevationNoise),
        mountainBelts * 0.85
    );
    float terrainHeight = 0.70 * elevationNoise
        + 0.22 * valueNoise(surfaceNormal * 34.0 + vec3(29.0, 5.0, 43.0))
        + 0.08 * valueNoise(surfaceNormal * 82.0 + vec3(11.0, 37.0, 19.0))
        + 0.12 * mountainBelts;
    vec3 terrainNormal = fromPlanetCoordinates(
        terrainReliefNormal(surfaceNormal, terrainHeight, 0.009)
    );
    float sunDot = dot(worldSurfaceNormal, sunDirection);
    float daylight = smoothstep(-0.06, 0.06, sunDot);
    float terrainDirectLight = max(dot(terrainNormal, sunDirection), 0.0);
    float directLight = mix(max(sunDot, 0.0), terrainDirectLight, land);
    float moonNight = 1.0 - smoothstep(-0.08, 0.16, SunElevation);
    float terrainMoonlight = max(dot(terrainNormal, moonDirection), 0.0);
    float moonLight = MoonIllumination * moonNight
        * mix(max(dot(worldSurfaceNormal, moonDirection), 0.0), terrainMoonlight, land);
    vec3 deepOcean = mix(vec3(0.004, 0.012, 0.030), vec3(0.018, 0.085, 0.20), daylight);
    vec3 coastalOcean = mix(vec3(0.008, 0.027, 0.047), vec3(0.035, 0.16, 0.25), daylight);
    vec3 shallowOcean = mix(vec3(0.012, 0.040, 0.050), vec3(0.07, 0.27, 0.25), daylight);
    float shelf = smoothstep(0.29, 0.45, continentNoise);
    float shallows = smoothstep(0.39, 0.47, continentNoise)
        * (1.0 - smoothstep(0.47, 0.50, continentNoise));
    vec3 ocean = mix(deepOcean, coastalOcean, max(coast * 0.75, shelf * 0.62));
    ocean = mix(ocean, shallowOcean, shallows * 0.82);
    float oceanTexture = filteredTerrainNoise(
        surfaceNormal + vec3(0.25, 0.45, 0.14), 92.0
    );
    ocean *= 0.94 + oceanTexture * 0.12;
    vec3 nightLand = vec3(0.012, 0.020, 0.018);
    vec3 grassland = vec3(0.22, 0.29, 0.15);
    vec3 forest = vec3(0.10, 0.22, 0.12);
    vec3 tropicalForest = vec3(0.09, 0.27, 0.14);
    vec3 savanna = vec3(0.34, 0.30, 0.15);
    vec3 desert = vec3(0.43, 0.34, 0.20);
    vec3 tundra = vec3(0.29, 0.34, 0.32);
    vec3 snow = vec3(0.52, 0.61, 0.64);
    vec3 warmBiome = mix(
        savanna,
        tropicalForest,
        smoothstep(0.38, 0.70, moisture)
    );
    vec3 temperateBiome = mix(
        grassland,
        forest,
        smoothstep(0.42, 0.72, moisture)
    );
    vec3 dayLand = mix(
        tundra,
        temperateBiome,
        smoothstep(0.12, 0.34, temperature)
    );
    dayLand = mix(
        dayLand,
        warmBiome,
        smoothstep(0.48, 0.76, temperature)
    );
    float desertCoverage = smoothstep(0.50, 0.76, temperature)
        * (1.0 - smoothstep(0.22, 0.48, moisture));
    dayLand = mix(dayLand, desert, desertCoverage);
    float snowCoverage = max(
        1.0 - smoothstep(0.06, 0.18, temperature),
        highland * (1.0 - smoothstep(0.28, 0.52, temperature))
    );
    dayLand = mix(dayLand, snow, snowCoverage);
    dayLand *= 0.82 + 0.36 * regionalNoise;
    vec3 landColor = mix(nightLand, dayLand, daylight);
    landColor *= 0.58 + terrainDetail * 0.86;
    vec3 surfaceColor = mix(ocean, landColor, land);
    vec3 waterHalfVector = normalize(sunDirection - viewDirection);
    float oceanGlint = pow(max(dot(worldSurfaceNormal, waterHalfVector), 0.0), 72.0)
        * daylight * (1.0 - land) * 0.20;
    surfaceColor += vec3(0.58, 0.76, 0.88) * oceanGlint;
    surfaceColor *= 0.025 + directLight * 0.975;
    vec3 sunlightColor = solarTransmission(sunDot);
    surfaceColor *= mix(vec3(1.0), sunlightColor, directLight * 0.8);
    float terminatorTwilight = twilightScattering(sunDot);
    surfaceColor *= mix(
        vec3(1.0),
        vec3(1.08, 0.82, 0.65),
        terminatorTwilight * 0.42
    );
    surfaceColor += vec3(0.018, 0.027, 0.052) * moonLight * 0.85;
    float altitudeHaze = smoothstep(1024.0, 9000.0, CameraHeight);
    float lowAtmosphereVeil = mix(0.12, 0.32, altitudeHaze);
    vec3 hazeColor = mix(
        vec3(0.012, 0.024, 0.046),
        vec3(0.34, 0.51, 0.66),
        daylight
    );
    hazeColor = mix(hazeColor, vec3(0.92, 0.48, 0.30), terminatorTwilight * 0.55);
    float hazeIllumination = mix(0.12, 1.0, daylight);
    surfaceColor = mix(
        surfaceColor,
        hazeColor,
        lowAtmosphereVeil * hazeIllumination
    );
    vec3 dayCloudColor = vec3(0.94, 0.95, 0.97);
    const float CLOUD_SURFACE_HEIGHT = 4000.0;
    float cloudSurfaceRadius = 1.0 + CLOUD_SURFACE_HEIGHT / PlanetRadius;
    float cloudRayProjection = cameraScale * viewDirection.y;
    float groundDiscriminant = cloudRayProjection * cloudRayProjection
        - (cameraScale * cameraScale - 1.0);
    float groundDistance = groundDiscriminant >= 0.0
        ? -cloudRayProjection - sqrt(groundDiscriminant)
        : -1.0;
    float cloudDiscriminant = cloudRayProjection * cloudRayProjection
        - (cameraScale * cameraScale - cloudSurfaceRadius * cloudSurfaceRadius);
    if (cloudDiscriminant >= 0.0) {
        float cloudRoot = sqrt(cloudDiscriminant);
        float cloudNear = -cloudRayProjection - cloudRoot;
        float cloudFar = -cloudRayProjection + cloudRoot;
        float cloudDistance = cloudNear > 0.0 ? cloudNear : cloudFar;
        if (cloudDistance > 0.0 && (groundDistance <= 0.0 || cloudDistance < groundDistance)) {
            vec3 cloudPosition = vec3(0.0, cameraScale, 0.0)
                + viewDirection * cloudDistance;
            vec3 cloudNormal = normalize(cloudPosition);
            vec3 cloudMapNormal = toPlanetCoordinates(cloudNormal);
            float cloudDensity = cloudDensityAt(cloudMapNormal);
            if (cloudDensity > 0.002) {
                float sampleSunDot = dot(cloudNormal, sunDirection);
                float sampleDaylight = smoothstep(-0.008, 0.032, sampleSunDot);
                float sampleMoonLight = MoonIllumination * moonNight
                    * max(dot(cloudNormal, moonDirection), 0.0);
                vec3 sampleCloudColor = mix(
                    vec3(0.035, 0.045, 0.07),
                    dayCloudColor,
                    sampleDaylight
                );
                sampleCloudColor *= mix(
                    vec3(1.0),
                    solarTransmission(sampleSunDot),
                    sampleDaylight * 0.8
                );
                float cloudTwilight = twilightScattering(sampleSunDot);
                sampleCloudColor = mix(
                    sampleCloudColor,
                    vec3(1.0, 0.48, 0.29),
                    cloudTwilight * 0.55
                );
                vec3 tangentX = normalize(cross(
                    cloudNormal,
                    abs(cloudNormal.y) < 0.95
                        ? vec3(0.0, 1.0, 0.0)
                        : vec3(1.0, 0.0, 0.0)
                ));
                vec3 tangentY = normalize(cross(cloudNormal, tangentX));
                float densityX = cloudDensityAt(
                    toPlanetCoordinates(normalize(cloudNormal + tangentX * 0.002))
                );
                float densityY = cloudDensityAt(
                    toPlanetCoordinates(normalize(cloudNormal + tangentY * 0.002))
                );
                vec3 cloudDetailNormal = fromPlanetCoordinates(normalize(
                    cloudMapNormal
                        - toPlanetCoordinates(tangentX) * (densityX - cloudDensity) * 5.5
                        - toPlanetCoordinates(tangentY) * (densityY - cloudDensity) * 5.5
                ));
                float normalSunDot = max(dot(cloudDetailNormal, sunDirection), 0.0);
                float diffuseLight = 0.28 + normalSunDot * 0.72;
                vec3 sunTangent = sunDirection - cloudNormal * sampleSunDot;
                float sunTangentLength = length(sunTangent);
                float sunwardShadow = 0.0;
                if (sunTangentLength > 0.05) {
                    vec3 sunwardNormal = normalize(
                        cloudNormal + sunTangent * (0.018 / sunTangentLength)
                    );
                    float sunwardDensity = cloudDensityAt(toPlanetCoordinates(sunwardNormal));
                    sunwardShadow = smoothstep(
                        0.02, 0.16, sunwardDensity - cloudDensity
                    );
                }
                float bodyShadow = smoothstep(0.78, 0.98, cloudDensity) * 0.10;
                float cloudSelfShadow = 1.0
                    - sampleDaylight * (bodyShadow + sunwardShadow * 0.24);
                float silverLining = pow(1.0 - normalSunDot, 2.4)
                    * sampleDaylight * 0.18;
                float forwardScatter = pow(
                    max(dot(viewDirection, sunDirection), 0.0), 5.0
                ) * sampleDaylight * 0.22;
                float cloudLighting = diffuseLight * cloudSelfShadow
                    + sampleMoonLight * 0.18 + silverLining + forwardScatter;
                sampleCloudColor += vec3(0.08, 0.11, 0.18) * sampleMoonLight;
                float cloudOpacity = 1.0 - exp(-cloudDensity * 1.25);
                surfaceColor = mix(
                    surfaceColor, sampleCloudColor * cloudLighting, cloudOpacity
                );
            }
        }
    }

    vec3 cityGrid = surfaceNormal * 280.0;
    vec3 cityCell = floor(cityGrid);
    vec3 cityLocal = fract(cityGrid) - 0.5;
    float citySeed = hashCell(cityCell);
    float cityRadius = mix(0.035, 0.09, hashCell(cityCell + vec3(13.0, 7.0, 3.0)));
    float citySpot = 1.0 - smoothstep(cityRadius * 0.65, cityRadius, length(cityLocal));
    float cityLights = land * (1.0 - daylight) * step(0.975, citySeed) * citySpot * 0.25;
    surfaceColor += cityLights * vec3(0.35, 0.16, 0.045);

    float viewCosine = clamp(dot(worldSurfaceNormal, -viewDirection), 0.0, 1.0);
    float airDensity = exp(-CameraHeight / 10000.0);
    float atmosphericOpacity = exp(-viewCosine * 12.0) * (0.18 + airDensity * 0.22);
    float viewSunAlignment = dot(viewDirection, sunDirection);
    float forwardScatter = pow(max(viewSunAlignment, 0.0), 7.0);
    float atmosphericDaylight = smoothstep(-0.06, 0.10, sunDot);
    float atmosphericTwilight = twilightScattering(sunDot);
    vec3 atmosphereColor = mix(
        vec3(0.001, 0.003, 0.009),
        vec3(0.34, 0.55, 0.73),
        atmosphericDaylight
    );
    float warmAtmosphere = atmosphericTwilight
        * mix(0.35, 1.0, smoothstep(-0.15, 0.45, viewSunAlignment));
    atmosphereColor = mix(atmosphereColor, vec3(0.96, 0.46, 0.27), warmAtmosphere * 0.72);
    atmosphereColor += forwardScatter * atmosphericDaylight * vec3(0.08, 0.11, 0.16);
    float rimSunlight = smoothstep(-0.015, 0.14, sunDot);
    float rimTwilight = atmosphericTwilight
        * mix(0.35, 1.0, smoothstep(-0.15, 0.45, viewSunAlignment));
    vec3 rimColor = mix(
        vec3(0.015, 0.035, 0.075),
        vec3(0.30, 0.60, 1.0),
        rimSunlight
    );
    rimColor = mix(
        rimColor,
        vec3(1.0, 0.42, 0.24),
        rimTwilight
    );
    atmosphereColor = mix(
        atmosphereColor,
        rimColor,
        0.42 * max(rimSunlight, rimTwilight)
    );
    surfaceColor = mix(surfaceColor, atmosphereColor, atmosphericOpacity);
    float rimProfile = exp(-viewCosine * 5.5);
    float rimIntensity = rimProfile
        * (0.001 + 0.68 * rimSunlight + 0.82 * rimTwilight);
    surfaceColor += rimColor * rimIntensity;

    fragColor = vec4(mix(skyColor.rgb, surfaceColor, coverage), skyColor.a);
}
