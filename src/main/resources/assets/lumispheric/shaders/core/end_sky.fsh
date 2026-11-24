#version 150

uniform vec3 BlackHoleDir;
// x = gameTimeSeconds, y = pixelatedStyle (0/1)
uniform vec4 EndParams;
uniform vec4 ColorModulator;

in vec3 localPosition;

out vec4 fragColor;

const float PI = 3.14159265359;
const float TWO_PI = 6.28318530718;

float hashCell(vec3 cell) {
    vec3 v = fract(cell * vec3(0.1031, 0.1030, 0.0973));
    v += dot(v, v.yzx + 33.33);
    return fract((v.x + v.y) * v.z);
}

float valueNoise(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    // Quintic C2 interpolation for silky volumetric clouds.
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float z0 = mix(
        mix(hashCell(cell), hashCell(cell + vec3(1.0, 0.0, 0.0)), f.x),
        mix(hashCell(cell + vec3(0.0, 1.0, 0.0)), hashCell(cell + vec3(1.0, 1.0, 0.0)), f.x),
        f.y
    );
    float z1 = mix(
        mix(hashCell(cell + vec3(0.0, 0.0, 1.0)), hashCell(cell + vec3(1.0, 0.0, 1.0)), f.x),
        mix(hashCell(cell + vec3(0.0, 1.0, 1.0)), hashCell(cell + vec3(1.0, 1.0, 1.0)), f.x),
        f.y
    );
    return mix(z0, z1, f.z);
}

float fbm4(vec3 p) {
    float sum = 0.0;
    float amp = 0.50;
    for (int i = 0; i < 4; i++) {
        sum += amp * valueNoise(p);
        p = p * 2.08 + vec3(17.3, 9.1, 29.7);
        amp *= 0.50;
    }
    return sum;
}

float fbm3(vec3 p) {
    float sum = 0.0;
    float amp = 0.54;
    for (int i = 0; i < 3; i++) {
        sum += amp * valueNoise(p);
        p = p * 2.12 + vec3(11.7, 23.4, 5.9);
        amp *= 0.48;
    }
    return sum;
}

vec3 quantizeCubeSphere(vec3 dir, float cellsPerFace) {
    vec3 ad = abs(dir);
    float m = max(ad.x, max(ad.y, ad.z));
    vec3 proj = dir / max(m, 1.0e-5);
    vec3 snapped = (floor(proj * cellsPerFace) + 0.5) / cellsPerFace;
    return normalize(snapped);
}

// Projects any unit direction onto a flat 2D cube-face grid so procedural stars
// are always clean circular pinpoints (or crisp pixels), never clipped 3D-voxel triangles.
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

vec3 cubeFaceStarLayer(
    vec3 dir,
    float cellsPerFace,
    float threshold,
    float seedOffset,
    float brightnessScale,
    bool isPixelStyle
) {
    float faceId;
    vec2 faceUV;
    cubeFaceUV(dir, faceId, faceUV);

    vec2 scaled = (faceUV * 0.5 + 0.5) * cellsPerFace;
    vec2 cell = floor(scaled);
    vec2 local = fract(scaled);
    vec3 seed = vec3(cell, faceId * 31.0 + seedOffset);

    float h = hashCell(seed);
    if (h <= threshold) {
        return vec3(0.0);
    }

    float bright = smoothstep(threshold, 0.9997, h);
    float shape;
    if (isPixelStyle) {
        vec2 d = abs(local - 0.5);
        float halfSize = mix(0.24, 0.36, bright);
        shape = step(max(d.x, d.y), halfSize);
    } else {
        // Jitter inside [0.26, 0.74]^2 with radius <= 0.21 => mathematically guaranteed zero clipping!
        vec2 center = vec2(
            0.26 + 0.48 * hashCell(seed + vec3(11.0, 5.0, 1.0)),
            0.26 + 0.48 * hashCell(seed + vec3(23.0, 13.0, 2.0))
        );
        float dist = length(local - center);
        float core = 1.0 - smoothstep(0.02, mix(0.11, 0.17, bright), dist);
        float halo = exp(-(dist * dist) / 0.009) * bright * 0.35;
        shape = core + halo;
    }

    float temp = hashCell(seed + vec3(13.0, 29.0, 7.0));
    vec3 col = temp < 0.22
        ? vec3(1.00, 0.68, 0.54)
        : mix(vec3(0.84, 0.74, 1.00), vec3(0.98, 0.96, 1.00), temp);
    return col * shape * (0.24 + 0.86 * bright) * brightnessScale;
}

// Rare, silent, seamless 4-stage distant supernova in the outer void (zero grid cells => never clipped).
vec3 distantSupernova(vec3 dir, vec3 eyeDir, float timeSec) {
    // Long 72-second cycle; only ~62% of cycles trigger an explosion (~1 supernova every ~115s on average).
    const float CYCLE_SECONDS = 72.0;
    const float BURST_FRACTION = 0.23; // ~16.5 seconds full evolution from swell to ghostly remnant.
    float cycleIndex = floor(timeSec / CYCLE_SECONDS);
    float cyclePhase = fract(timeSec / CYCLE_SECONDS);
    if (cyclePhase >= BURST_FRACTION) {
        return vec3(0.0);
    }

    float triggerRoll = hashCell(vec3(cycleIndex, 17.0, 53.0));
    if (triggerRoll < 0.38) {
        return vec3(0.0);
    }

    // Place the supernova deterministically in the starry outer void (well away from the Eye and above horizon).
    float az = hashCell(vec3(cycleIndex, 3.0, 11.0)) * TWO_PI;
    float elev = 0.12 + 0.56 * hashCell(vec3(cycleIndex, 29.0, 41.0));
    vec3 novaDir = normalize(vec3(cos(az) * cos(elev), sin(elev), sin(az) * cos(elev)));
    // Push away from the Eye if random placement landed inside the vortex cone.
    if (dot(novaDir, eyeDir) > 0.26) {
        novaDir = normalize(vec3(-novaDir.x, max(0.14, novaDir.y * 0.65), -novaDir.z));
    }

    // Cheap angular bounding check before computing full tangent-plane shockwave.
    float cosSep = dot(dir, novaDir);
    if (cosSep < 0.9965) { // ~4.8 degrees max radius
        return vec3(0.0);
    }

    vec3 nRef = abs(novaDir.y) > 0.95 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 nRight = normalize(cross(nRef, novaDir));
    vec3 nUp = normalize(cross(novaDir, nRight));
    vec2 local = vec2(dot(dir, nRight), dot(dir, nUp));
    float r = length(local);
    float ang = atan(local.y, local.x);

    float p = cyclePhase / BURST_FRACTION; // 0..1 over ~16.5 seconds

    // Stage 1: Quiet warm red-giant swell right before core collapse (p = 0.00..0.18).
    float swellEnv = smoothstep(0.0, 0.14, p) * (1.0 - smoothstep(0.15, 0.26, p));
    float swellR = mix(0.0012, 0.0032, smoothstep(0.0, 0.18, p));
    float redGiant = exp(-(r * r) / (swellR * swellR)) * swellEnv;

    // Stage 2: Blinding core-collapse flash + delicate 4-point optical diffraction spikes (p = 0.14..0.44).
    float flashEnv = smoothstep(0.13, 0.20, p) * (1.0 - smoothstep(0.22, 0.48, p));
    float flashCore = exp(-(r * r) / (0.000014 + flashEnv * 0.000018)) * flashEnv;
    float spikeRot = hashCell(vec3(cycleIndex, 61.0, 7.0)) * PI;
    float cs = cos(spikeRot);
    float sn = sin(spikeRot);
    vec2 sp = vec2(local.x * cs - local.y * sn, local.x * sn + local.y * cs);
    float spikeLen = 0.009 + 0.018 * flashEnv;
    float spikeH = exp(-abs(sp.y) / 0.00065) * exp(-abs(sp.x) / spikeLen);
    float spikeV = exp(-abs(sp.x) / 0.00065) * exp(-abs(sp.y) / spikeLen);
    float diffraction = (spikeH + spikeV) * flashEnv * flashEnv * 0.72;

    // Stage 3 & 4: Filamentary expanding shockwave + volumetric plasma nebula + cooling remnant (p = 0.18..1.0).
    vec3 shockContribution = vec3(0.0);
    if (p > 0.16) {
        float bp = clamp((p - 0.16) / 0.84, 0.0, 1.0);
        // Decelerating Sedov-Taylor expansion curve (fast initial blast, gentle drift at the end).
        float expansion = pow(bp, 0.58);
        float baseRadius = 0.002 + expansion * 0.026;

        // Organic angular filament turbulence so the shockwave looks like real cosmic gas, not a plain circle!
        vec3 gasCoord = vec3(cos(ang) * 2.2, sin(ang) * 2.2, bp * 1.4 + cycleIndex * 7.1);
        float gasNoise = fbm3(gasCoord + vec3(r * 45.0, 3.7, 9.2));
        float knotWave = 0.5 + 0.5 * sin(ang * 5.0 + gasNoise * 5.2 + cycleIndex * 2.3);
        float radiusWarp = (gasNoise - 0.5) * 0.0055 * expansion;
        float rWarped = r + radiusWarp;

        float fadeOut = smoothstep(0.0, 0.10, bp) * (1.0 - smoothstep(0.45, 1.0, bp));
        float shellThickness = mix(0.0014, 0.0038, expansion);
        float shell = exp(-pow((rWarped - baseRadius) / shellThickness, 2.0))
            * (0.42 + 0.95 * gasNoise * (0.45 + 0.55 * knotWave));

        // Soft wispy interior nebula cloud filling the blast cavity.
        float cavity = (1.0 - smoothstep(baseRadius * 0.25, baseRadius * 1.15, rWarped))
            * (0.28 + 0.72 * gasNoise) * (1.0 - smoothstep(0.28, 0.88, bp));

        // Faint cooling white-dwarf core left shimmering silently at the center.
        float remnantCore = exp(-(r * r) / 0.0000032) * (1.0 - smoothstep(0.55, 1.0, bp)) * 0.55;

        // Color cools realistically: blazing white-gold -> rose-violet plasma -> ethereal pale-cyan/lavender shock.
        vec3 outerShockCol = mix(vec3(0.92, 0.88, 1.00), vec3(0.62, 0.78, 1.00), smoothstep(0.08, 0.65, bp));
        vec3 innerPlasmaCol = mix(vec3(1.00, 0.68, 0.58), vec3(0.66, 0.36, 0.92), smoothstep(0.05, 0.60, bp));

        shockContribution = (outerShockCol * shell * 0.95 + innerPlasmaCol * cavity * 0.42) * fadeOut
            + vec3(0.92, 0.95, 1.00) * remnantCore;
    }

    vec3 total = vec3(1.00, 0.56, 0.34) * redGiant * 0.95
        + vec3(1.00, 0.96, 0.90) * (flashCore * 1.55 + diffraction)
        + shockContribution;
    return total;
}

// Sparse, ancient dying-universe starfield + rare silent distant supernovae in the purple End void.
vec3 outerWildsVoidStars(vec3 rawDir, vec3 eyeDir, float timeSec, float vortexOcclusion, bool isPixelStyle) {
    if (vortexOcclusion > 0.96) {
        return vec3(0.0);
    }
    vec3 result = cubeFaceStarLayer(rawDir, isPixelStyle ? 112.0 : 148.0, 0.986, 5.0, 1.0, isPixelStyle);
    // Rotate secondary faint star layer slightly so the two 2D face grids never align artificially.
    vec3 rotDir = normalize(vec3(
        rawDir.x * 0.866 - rawDir.z * 0.500,
        rawDir.y,
        rawDir.x * 0.500 + rawDir.z * 0.866
    ));
    result += cubeFaceStarLayer(rotDir, isPixelStyle ? 170.0 : 235.0, 0.982, 47.0, 0.46, isPixelStyle);
    result += distantSupernova(isPixelStyle ? quantizeCubeSphere(rawDir, 220.0) : rawDir, eyeDir, timeSec);
    return result * (1.0 - vortexOcclusion);
}

vec3 softFilmic(vec3 c) {
    return c / (1.0 + max(c - vec3(0.80), vec3(0.0)) * 0.50);
}

void main() {
    vec3 rawDir = normalize(localPosition);
    float timeSec = EndParams.x;
    bool isPixelStyle = EndParams.y > 0.5;
    vec3 dir = isPixelStyle ? quantizeCubeSphere(rawDir, 155.0) : rawDir;

    // Towering Eye of the Universe axis.
    vec3 eyeDir = normalize(BlackHoleDir);
    vec3 refUp = abs(eyeDir.y) > 0.95 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 eyeRight = normalize(cross(refUp, eyeDir));
    vec3 eyeUp = normalize(cross(eyeDir, eyeRight));

    float cosAngle = clamp(dot(dir, eyeDir), -1.0, 1.0);
    float angle = acos(cosAngle); // 0 at center of the Eye, PI opposite.

    // Velvety obsidian-plum End cosmos fading into misty violet-purple at the horizon (matches END_FOG_COLOR).
    float horizonMist = exp(-abs(dir.y) * 2.8);
    float lowerVoid = smoothstep(-0.04, -0.52, dir.y);
    vec3 zenithVoid = vec3(0.020, 0.009, 0.042);
    vec3 horizonPurple = vec3(0.088, 0.038, 0.152);
    vec3 abyssPlum = vec3(0.032, 0.012, 0.060);
    vec3 voidColor = mix(zenithVoid, horizonPurple, horizonMist);
    voidColor = mix(voidColor, abyssPlum, lowerVoid * 0.68);

    // Colossal purple swirling Eye of the Universe storm vortex overhead (angle < 1.42 rad).
    float vortexOcclusion = 0.0;
    vec3 vortexLight = vec3(0.0);
    if (angle < 1.42) {
        vec2 rel = vec2(dot(dir, eyeRight), dot(dir, eyeUp));
        float theta = atan(rel.y, rel.x);
        float logR = log(angle + 0.080);

        // Domain-warped logarithmic spiral coordinates projected into 3D (zero seam at theta = +-PI).
        float swirlSlow = theta + 2.35 * logR - timeSec * 0.052;
        float swirlFast = theta + 3.20 * logR - timeSec * 0.108;

        vec3 outerCoord = vec3(
            cos(swirlSlow) * (1.25 + angle * 2.15),
            sin(swirlSlow) * (1.25 + angle * 2.15),
            logR * 1.45 - timeSec * 0.032
        );
        vec3 innerCoord = vec3(
            cos(swirlFast) * (1.65 + angle * 3.30),
            sin(swirlFast) * (1.65 + angle * 3.30),
            logR * 2.15 - timeSec * 0.068
        );

        // Stronger multi-axis curl warping breaks spiral bands into billowy volumetric storm clouds.
        float warpA = valueNoise(outerCoord * 1.25 + vec3(7.1, 19.3, 3.7)) - 0.5;
        float warpB = valueNoise(outerCoord * 1.25 + vec3(23.4, 5.2, 13.8)) - 0.5;
        float fineTurbulence = valueNoise(innerCoord * 2.40 + vec3(13.2, -8.5, 19.1)) - 0.5;

        float outerClouds = fbm4(outerCoord * 1.42 + vec3(warpA, warpB, warpA * 0.6) * 1.55);
        float innerFilaments = fbm3(innerCoord * 1.85 + vec3(warpB, -warpA, fineTurbulence) * 1.25);

        // Radial envelope around the calm central Eye (eyeRadius = 0.125 rad).
        const float EYE_RADIUS = 0.125;
        float outsideEye = smoothstep(EYE_RADIUS * 0.92, EYE_RADIUS * 1.45, angle);
        float outerFade = 1.0 - smoothstep(0.48, 1.38, angle);
        float funnelMask = outsideEye * outerFade;

        // Softly modulated 3-arm spiral structure with rich billowy breakup (no flat black stripes).
        float armWave = 0.5 + 0.5 * sin(3.0 * theta + 5.8 * logR - timeSec * 0.14 + warpA * 4.4 + fineTurbulence * 1.4);
        float rawCloud = outerClouds * 0.58 + innerFilaments * 0.34 + (armWave - 0.5) * 0.17 + fineTurbulence * 0.08;
        float cloudDensity = smoothstep(0.28, 0.78, rawCloud) * funnelMask;
        // Billowy lit rim where dense storm clouds thin out into glowing amethyst rifts.
        float cloudEdge = smoothstep(0.18, 0.52, cloudDensity) * (1.0 - smoothstep(0.52, 0.88, cloudDensity));

        // Occlude background stars cleanly behind the Eye and thick swirling storm body.
        float darkEyeShadow = 1.0 - smoothstep(EYE_RADIUS * 0.88, EYE_RADIUS * 1.15, angle);
        float innerStormHaze = exp(-max(angle - EYE_RADIUS, 0.0) / 0.42) * funnelMask;
        vortexOcclusion = clamp(max(darkEyeShadow, max(cloudDensity * 0.95, innerStormHaze * 0.85)), 0.0, 1.0);

        // End-purple cosmic palette: deep royal obsidian-plum shadows, ultraviolet rifts, orchid-lavender glow.
        float innerGlowProfile = exp(-max(angle - EYE_RADIUS, 0.0) / 0.28) * outsideEye;
        float gapTransmission = (1.0 - smoothstep(0.26, 0.78, cloudDensity)) * (0.38 + 0.62 * innerFilaments);

        vec3 deepEndShadow = vec3(0.036, 0.014, 0.072);
        vec3 royalAmethyst = vec3(0.17, 0.068, 0.33);
        vec3 ultravioletRift = vec3(0.42, 0.16, 0.72);
        vec3 orchidGlow = vec3(0.72, 0.40, 0.98);
        vec3 magentaWisp = vec3(0.78, 0.28, 0.82);

        // Volumetric storm cloud shading: dark plum cores with bright violet-magenta inner faces.
        vec3 stormCloudBase = mix(
            deepEndShadow,
            mix(royalAmethyst, magentaWisp * 0.55, innerFilaments * 0.45),
            clamp(innerFilaments * 0.65 + innerGlowProfile * 0.55, 0.0, 1.0)
        );
        vec3 riftColor = mix(ultravioletRift, mix(orchidGlow, magentaWisp, 0.28), innerGlowProfile * 0.88);
        vec3 riftGlow = riftColor * (0.42 + 0.92 * gapTransmission) * funnelMask;
        vec3 backlitRim = mix(magentaWisp, orchidGlow, innerGlowProfile) * cloudEdge * (0.28 + 0.62 * innerGlowProfile);

        // Delicate pale lavender-white quantum light shafts fanning out of the Eye rim into the purple storm.
        float rayPattern = 0.5 + 0.5 * sin(theta * 14.0 + warpA * 2.4 - timeSec * 0.08);
        float rayFine = 0.5 + 0.5 * sin(theta * 27.0 - warpB * 2.8 + timeSec * 0.12);
        float rayOuterFade = 1.0 - smoothstep(0.45, 1.32, angle);
        float rays = pow(rayPattern * 0.65 + rayFine * 0.35, 2.3)
            * exp(-max(angle - EYE_RADIUS, 0.0) / 0.38) * outsideEye * rayOuterFade * (1.0 - cloudDensity * 0.50);

        // Subtle, haunting deep-cloud electric-violet lightning flashes inside the storm funnel (seamless global slots).
        float lightning = 0.0;
        if (angle > EYE_RADIUS * 1.15 && angle < 0.92) {
            float lightningFade = smoothstep(EYE_RADIUS * 1.15, EYE_RADIUS * 1.55, angle)
                * (1.0 - smoothstep(0.72, 0.92, angle));
            for (int slot = 0; slot < 2; slot++) {
                float slotId = float(slot);
                float slotTime = timeSec * (0.42 + slotId * 0.19) + slotId * 11.3;
                float timeBucket = floor(slotTime);
                float strikeHash = hashCell(vec3(timeBucket, slotId * 13.0, 19.0));
                if (strikeHash > 0.42) {
                    float localPhase = fract(slotTime);
                    float pulse = exp(-pow((localPhase - 0.08) / 0.022, 2.0))
                        + 0.65 * exp(-pow((localPhase - 0.15) / 0.030, 2.0));
                    if (pulse > 0.01) {
                        float flashAngle = hashCell(vec3(timeBucket, slotId * 7.0, 31.0)) * TWO_PI - PI;
                        float flashR = 0.22 + 0.40 * hashCell(vec3(timeBucket, slotId * 17.0, 47.0));
                        vec2 flashPos = vec2(cos(flashAngle), sin(flashAngle)) * flashR;
                        float distToFlash = length(rel - flashPos);
                        lightning += exp(-(distToFlash * distToFlash) / 0.022) * pulse * (0.45 + 0.80 * cloudDensity) * 0.95;
                    }
                }
            }
            lightning *= lightningFade;
        }

        // Luminous silver-lavender quantum rim hugging the calm dark pupil of the Eye.
        float eyeRim = exp(-pow((angle - EYE_RADIUS * 1.04) / 0.022, 2.0));
        float innerHalo = exp(-pow((angle - EYE_RADIUS * 1.18) / 0.090, 2.0)) * 0.44;
        float rimShimmer = 0.82 + 0.18 * sin(theta * 6.0 - timeSec * 0.45 + warpA * 4.0);
        vec3 rimColor = mix(vec3(0.78, 0.56, 1.00), vec3(0.97, 0.91, 1.00), eyeRim);

        // Faint ghostly violet quantum ripple rings inside the dark pupil.
        float pupilRipple = 0.0;
        if (angle < EYE_RADIUS * 0.95) {
            float normR = angle / EYE_RADIUS;
            float ring1 = exp(-pow((normR - 0.48) / 0.045, 2.0));
            float ring2 = exp(-pow((normR - 0.76) / 0.035, 2.0));
            float pulse = 0.55 + 0.45 * sin(timeSec * 0.65 - normR * 5.0);
            pupilRipple = (ring1 * 0.10 + ring2 * 0.065) * pulse;
        }

        vortexLight = mix(riftGlow, stormCloudBase + riftGlow * 0.36, cloudDensity * 0.72);
        vortexLight += backlitRim;
        vortexLight += vec3(0.84, 0.62, 1.00) * rays * 0.44;
        vortexLight += vec3(0.95, 0.76, 1.08) * lightning;
        vortexLight *= 1.0 - darkEyeShadow;
        vortexLight += rimColor * (eyeRim * 0.98 + innerHalo) * rimShimmer;
        vortexLight += vec3(0.72, 0.46, 0.98) * pupilRipple;
    }

    // Skip outer nebula FBM noise when completely occluded behind the Eye vortex.
    if (vortexOcclusion < 0.96) {
        vec3 dustNormal = normalize(vec3(0.45, 0.76, 0.46));
        float dustBand = exp(-pow(dot(dir, dustNormal) / 0.46, 2.0));
        if (dustBand > 0.02) {
            float dustNoise = fbm3(dir * 3.2 + vec3(timeSec * 0.002, 4.1, -2.7));
            float wisps = smoothstep(0.32, 0.78, dustNoise) * dustBand;
            vec3 nebulaCol = mix(vec3(0.085, 0.028, 0.155), vec3(0.175, 0.055, 0.245), dustNoise);
            voidColor += nebulaCol * wisps * 0.72;
        }
    }

    vec3 stars = outerWildsVoidStars(rawDir, eyeDir, timeSec, vortexOcclusion, isPixelStyle);
    vec3 finalSky = voidColor * (1.0 - vortexOcclusion * 0.84) + stars + vortexLight;

    fragColor = vec4(clamp(softFilmic(finalSky), 0.0, 1.0), 1.0) * ColorModulator;
}
