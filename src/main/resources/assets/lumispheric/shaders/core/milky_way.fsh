#version 150

uniform vec3 GalacticNorth;
uniform vec3 GalacticCenter;
uniform float MilkyWayStrength;
// x = geometricHorizonDip, y = spaceBlend, z = pixelatedStyle (0/1), w = planetBlend
uniform vec4 MilkyWayParams;

in vec3 localPosition;

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

float fbm(vec3 p) {
    float total = 0.0;
    float amp = 0.52;
    for (int i = 0; i < 4; i++) {
        total += amp * valueNoise(p);
        p = p * 2.12 + vec3(19.3, 7.7, 31.1);
        amp *= 0.48;
    }
    return total;
}

vec3 quantizeCubeSphere(vec3 dir, float cellsPerFace) {
    vec3 ad = abs(dir);
    float m = max(ad.x, max(ad.y, ad.z));
    vec3 proj = dir / max(m, 1.0e-5);
    vec3 snapped = (floor(proj * cellsPerFace) + 0.5) / cellsPerFace;
    return normalize(snapped);
}

// Projects any unit direction onto a flat 2D cube-face grid so procedural micro-stars
// are always clean circular points (or crisp pixels), never clipped 3D-voxel triangles.
void cubeFaceUV(vec3 d, out float faceId, out vec2 uv) {
    vec3 ad = abs(d);
    if (ad.x >= ad.y && ad.x >= ad.z) {
        faceId = d.x > 0.0 ? 0.0 : 1.0;
        uv = vec2(d.z, d.y) / max(ad.x, 1.0e-5);
    } else if (ad.y >= ad.x && ad.y >= ad.z) {
        faceId = d.y > 0.0 ? 2.0 : 3.0;
        uv = vec2(d.x, d.z) / max(ad.y, 1.0e-5);
    } else {
        faceId = d.z > 0.0 ? 4.0 : 5.0;
        uv = vec2(d.x, d.y) / max(ad.z, 1.0e-5);
    }
}

float milkyWayMicroStars(vec3 dir, bool isPixelStyle) {
    float faceId;
    vec2 faceUV;
    cubeFaceUV(dir, faceId, faceUV);
    float cellsPerFace = isPixelStyle ? 150.0 : 210.0;
    vec2 scaled = (faceUV * 0.5 + 0.5) * cellsPerFace;
    vec2 cell = floor(scaled);
    vec2 local = fract(scaled);
    vec3 seed = vec3(cell, faceId * 17.0 + 3.0);
    float h = hashCell(seed);
    if (h <= 0.962) {
        return 0.0;
    }
    float bright = smoothstep(0.962, 0.998, h);
    if (isPixelStyle) {
        vec2 d = abs(local - 0.5);
        float shape = step(max(d.x, d.y), 0.36);
        return bright * shape;
    }
    // Jitter inside [0.25, 0.75]^2 with radius <= 0.21 so stars never touch cell borders.
    vec2 center = vec2(
        0.25 + 0.50 * hashCell(seed + vec3(11.0, 5.0, 1.0)),
        0.25 + 0.50 * hashCell(seed + vec3(23.0, 13.0, 2.0))
    );
    float dist = length(local - center);
    float shape = 1.0 - smoothstep(0.05, 0.21, dist);
    return bright * shape;
}

void main() {
    vec3 viewDirection = normalize(localPosition);

    // Early-out far from the galactic plane before running any 3D noise (~60% sky pixels skipped!).
    vec3 gNorth = normalize(GalacticNorth);
    float planeSin = dot(viewDirection, gNorth);
    if (abs(planeSin) > 0.72) {
        discard;
    }

    float elevation = asin(clamp(viewDirection.y, -1.0, 1.0));
    float horizon = -MilkyWayParams.x;
    float spaceBlend = clamp(MilkyWayParams.y, 0.0, 1.0);
    bool isPixelStyle = MilkyWayParams.z > 0.5;
    float planetBlend = clamp(MilkyWayParams.w, 0.0, 1.0);

    float planetClip = mix(
        1.0,
        smoothstep(horizon - 0.008, horizon + PLANET_HORIZON_TOP_FADE + 0.014, elevation),
        planetBlend
    );
    float groundExtinction = mix(
        smoothstep(horizon - 0.02, horizon + 0.22, elevation),
        smoothstep(horizon - 0.005, horizon + 0.025, elevation),
        spaceBlend
    );
    float horizonVisibility = planetClip * groundExtinction;
    if (horizonVisibility <= 0.001) {
        discard;
    }

    vec3 gCenter = normalize(GalacticCenter - gNorth * dot(GalacticCenter, gNorth));
    vec3 gEast = normalize(cross(gNorth, gCenter));

    vec3 rawGalDir = vec3(
        dot(viewDirection, gCenter),
        planeSin,
        dot(viewDirection, gEast)
    );
    vec3 galDir = isPixelStyle ? quantizeCubeSphere(rawGalDir, 150.0) : rawGalDir;

    float galacticLatitude = asin(clamp(galDir.y, -1.0, 1.0));
    float centerAlignment = max(0.0, galDir.x);

    float broadBand = exp(-pow(galacticLatitude / 0.34, 2.0));
    float innerBand = exp(-pow(galacticLatitude / 0.145, 2.0));
    float bulge = exp(-pow(galacticLatitude / 0.24, 2.0)) * pow(centerAlignment, 2.6);

    vec3 warp = vec3(
        valueNoise(galDir * 6.5 + vec3(3.1, 11.4, 7.2)) - 0.5,
        valueNoise(galDir * 6.5 + vec3(17.8, 5.2, 23.6)) - 0.5,
        valueNoise(galDir * 6.5 + vec3(29.3, 19.1, 4.5)) - 0.5
    );
    vec3 cloudCoords = galDir * 9.5 + warp * 1.45;
    float starClouds = fbm(cloudCoords);
    float fineFilaments = fbm(cloudCoords * 2.4 + vec3(13.0, 27.0, 5.0));
    float brightKnots = smoothstep(0.42, 0.78, starClouds * 0.62 + fineFilaments * 0.38);

    // Skip Great Rift dark dust noise outside the central band (saves 8 valueNoise calls per outer-halo pixel).
    float dustTransmission = 1.0;
    if (abs(galacticLatitude) < 0.24) {
        float riftWave = (fbm(galDir * 5.2 + vec3(41.0, 9.0, 17.0)) - 0.5) * 0.11;
        float riftCenter = galacticLatitude - riftWave;
        float riftBand = exp(-pow(riftCenter / 0.055, 2.0));
        float riftWisps = smoothstep(0.32, 0.68, fbm(galDir * 18.0 + warp * 2.2 + vec3(7.0, 31.0, 13.0)));
        float darkRift = riftBand * riftWisps * (0.55 + 0.45 * smoothstep(-0.3, 0.7, galDir.x));
        dustTransmission = 1.0 - clamp(darkRift * 0.82, 0.0, 0.85);
    }

    float microStar = milkyWayMicroStars(rawGalDir, isPixelStyle) * innerBand * dustTransmission;

    float coreBoost = 0.65 + 0.55 * centerAlignment;
    float baseGlow = (broadBand * 0.19 + innerBand * 0.42 * mix(0.45, 1.30, brightKnots) + bulge * 0.40)
        * coreBoost * dustTransmission;
    float totalEmission = (baseGlow + microStar * (0.38 + spaceBlend * 0.42))
        * (1.0 + spaceBlend * 0.38);

    float alpha = clamp(totalEmission * MilkyWayStrength * horizonVisibility, 0.0, 0.76);
    if (alpha <= 0.002) {
        discard;
    }

    vec3 outerArmColor = vec3(0.50, 0.63, 0.94);
    vec3 starCloudColor = vec3(0.84, 0.87, 0.97);
    vec3 coreGoldColor = vec3(1.00, 0.83, 0.58);
    vec3 nebulaPink = vec3(0.92, 0.52, 0.70);

    vec3 color = mix(outerArmColor, starCloudColor, brightKnots * 0.78);
    color = mix(color, coreGoldColor, smoothstep(0.15, 0.85, centerAlignment) * (0.55 + 0.45 * bulge));
    float nebulaKnot = smoothstep(0.72, 0.90, fineFilaments) * innerBand * dustTransmission;
    color = mix(color, nebulaPink, nebulaKnot * 0.25);
    color += vec3(0.95, 0.97, 1.0) * microStar * 0.50;

    fragColor = vec4(color, alpha);
}
