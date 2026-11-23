#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float GlowScale;
uniform vec2 LunarShadowOffset;
uniform float MoonPass;
uniform float PlanetPass;
uniform float PlanetType;
uniform vec3 BodyLightDir;
// x = pixelatedStyle (0/1), y = earthshine, z = spyglassZoom, w = solarEclipse
uniform vec4 StyleParams;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

float planetHash(vec2 position) {
    vec2 value = fract(position * vec2(0.1031, 0.1030));
    value += dot(value, value.yx + 33.33);
    return fract(value.x * value.y);
}

float planetNoise(vec2 position) {
    vec2 cell = floor(position);
    vec2 blend = fract(position);
    blend = blend * blend * (3.0 - 2.0 * blend);
    float a = planetHash(cell);
    float b = planetHash(cell + vec2(1.0, 0.0));
    float c = planetHash(cell + vec2(0.0, 1.0));
    float d = planetHash(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

vec3 mercurianSurface(vec2 spherePosition) {
    float craters = planetNoise(spherePosition * 42.0);
    craters *= planetNoise(spherePosition * 95.0);
    return mix(vec3(0.30, 0.28, 0.25), vec3(0.66, 0.62, 0.54), craters);
}

vec3 venusSurface(vec2 spherePosition) {
    float bands = sin(spherePosition.y * 20.0 + planetNoise(spherePosition * 5.0) * 3.0);
    float clouds = planetNoise(spherePosition * 16.0);
    return mix(vec3(0.76, 0.62, 0.36), vec3(0.98, 0.88, 0.62), smoothstep(-0.25, 0.8, bands) * 0.55 + clouds * 0.30);
}

vec3 marsSurface(vec2 spherePosition) {
    float terrain = planetNoise(spherePosition * 9.0);
    float fine = planetNoise(spherePosition * 31.0);
    vec3 dark = vec3(0.28, 0.10, 0.045);
    vec3 red = vec3(0.76, 0.28, 0.12);
    vec3 col = mix(dark, red, smoothstep(0.30, 0.75, terrain + fine * 0.22));
    float polarCap = smoothstep(0.74, 0.88, abs(spherePosition.y));
    return mix(col, vec3(0.92, 0.90, 0.88), polarCap * 0.80);
}

vec3 jovianSurface(vec2 spherePosition) {
    float turbulence = planetNoise(vec2(spherePosition.x * 9.0, spherePosition.y * 18.0));
    float bands = sin(spherePosition.y * 28.0 + turbulence * 2.8);
    vec3 cream = vec3(0.84, 0.74, 0.56);
    vec3 brown = vec3(0.48, 0.24, 0.14);
    vec3 orange = vec3(0.78, 0.44, 0.20);
    vec3 color = mix(cream, brown, smoothstep(-0.25, 0.55, bands));
    color = mix(color, orange, smoothstep(0.50, 0.82, turbulence) * 0.46);
    float spot = exp(-pow(length((spherePosition - vec2(0.22, 0.26)) * vec2(1.0, 1.6)) * 6.5, 2.0));
    return mix(color, vec3(0.70, 0.20, 0.10), spot * 0.85);
}

vec3 saturnSurface(vec2 spherePosition) {
    float bands = sin(spherePosition.y * 30.0 + planetNoise(spherePosition * 8.0) * 2.0);
    return mix(vec3(0.68, 0.56, 0.34), vec3(0.92, 0.82, 0.56), smoothstep(-0.4, 0.75, bands));
}

vec3 planetarySurface(vec2 spherePosition) {
    if (PlanetType < 1.5) return mercurianSurface(spherePosition);
    if (PlanetType < 2.5) return venusSurface(spherePosition);
    if (PlanetType < 3.5) return marsSurface(spherePosition);
    if (PlanetType < 4.5) return jovianSurface(spherePosition);
    if (PlanetType < 5.5) return saturnSurface(spherePosition);
    if (PlanetType < 6.5) {
        float noise = planetNoise(spherePosition * 14.0);
        return mix(vec3(0.42, 0.74, 0.84), vec3(0.62, 0.88, 0.92), noise);
    }
    float noise = planetNoise(spherePosition * 16.0);
    return mix(vec3(0.16, 0.28, 0.62), vec3(0.32, 0.56, 0.92), noise);
}

bool sampleSaturnRing(vec2 quadPosition, out vec3 outRingColor, out float outRingAlpha, out bool isFront) {
    const float cosTilt = 0.9135;
    const float sinTilt = 0.4067;
    vec2 rotPos = vec2(
        quadPosition.x * cosTilt - quadPosition.y * sinTilt,
        quadPosition.x * sinTilt + quadPosition.y * cosTilt
    );
    isFront = rotPos.y > 0.0;
    vec2 ringPos = rotPos / 0.54;
    ringPos.y /= 0.36;
    float ringRadius = length(ringPos);
    if (ringRadius <= 1.18 || ringRadius >= 1.84) {
        outRingColor = vec3(0.0);
        outRingAlpha = 0.0;
        return false;
    }
    float ringBand = sin(ringRadius * 72.0);
    float ringFine = planetNoise(quadPosition * 80.0 + vec2(7.0, 3.0));
    float cassiniGap = smoothstep(1.50, 1.54, ringRadius) * (1.0 - smoothstep(1.58, 1.62, ringRadius));
    vec3 ringColor = mix(
        vec3(0.50, 0.43, 0.32),
        vec3(0.90, 0.82, 0.62),
        smoothstep(-0.45, 0.75, ringBand) * 0.70 + ringFine * 0.30
    );
    ringColor *= 1.0 - cassiniGap * 0.72;
    float shadowSide = dot(normalize(rotPos + vec2(1.0e-5, 0.0)), normalize(BodyLightDir.xy + vec2(1.0e-5, 0.0)));
    float planetShadow = isFront ? 1.0 : mix(0.45, 1.0, smoothstep(-0.85, -0.35, shadowSide));
    outRingColor = ringColor * planetShadow;
    outRingAlpha = (1.0 - smoothstep(1.76, 1.84, ringRadius))
        * smoothstep(1.18, 1.25, ringRadius)
        * (1.0 - cassiniGap * 0.65);
    return outRingAlpha > 0.01;
}

void main() {
    float pixelatedStyle = StyleParams.x;

    // Mode 3: Naked-eye planet rendered as a crisp Minecraft-style square star (non-twinkling).
    if (PlanetPass > 2.5) {
        vec2 p = abs(texCoord0 * 2.0 - 1.0);
        float maxCoord = max(p.x, p.y);
        if (maxCoord > 1.0) {
            discard;
        }
        float core = 1.0 - smoothstep(0.65, 1.0, maxCoord);
        vec3 col = mix(ColorModulator.rgb, vec3(1.0), 0.32 * core);
        fragColor = vec4(col * vertexColor.rgb, vertexColor.a * ColorModulator.a * (0.85 + 0.15 * core));
        return;
    }

    // Mode 2: Soft atmospheric halo for very bright planets (Venus, Jupiter, etc.).
    if (PlanetPass > 1.5) {
        vec2 glowPosition = texCoord0 * 2.0 - 1.0;
        float glowRadius = length(glowPosition);
        float glow = exp(-glowRadius * glowRadius * 2.4);
        glow *= 1.0 - smoothstep(0.80, 1.0, glowRadius);
        if (glow <= 0.001) {
            discard;
        }
        fragColor = vec4(ColorModulator.rgb * glow, glow * vertexColor.a * ColorModulator.a);
        return;
    }

    // Mode 1: Telescopic planet view (Spyglass / Zoom) with real Sun phase lighting and optional pixel-art grid.
    if (PlanetPass > 0.5) {
        vec2 sampleUV = texCoord0;
        if (pixelatedStyle > 0.5) {
            float gridRes = (PlanetType > 4.5 && PlanetType < 5.5) ? 20.0 : 14.0;
            sampleUV = (floor(sampleUV * gridRes) + 0.5) / gridRes;
        }
        vec2 quadPosition = sampleUV * 2.0 - 1.0;
        bool isSaturn = PlanetType > 4.5 && PlanetType < 5.5;
        float diskScale = isSaturn ? 0.54 : 0.80;
        vec2 spherePosition = quadPosition / diskScale;
        float radius = length(spherePosition);

        vec3 ringColor = vec3(0.0);
        float ringAlpha = 0.0;
        bool ringFront = false;
        bool hasRing = isSaturn && sampleSaturnRing(quadPosition, ringColor, ringAlpha, ringFront);

        if (hasRing && ringFront && ringAlpha > 0.05) {
            fragColor = vec4(ringColor * vertexColor.rgb * ColorModulator.rgb,
                ringAlpha * vertexColor.a * ColorModulator.a);
            return;
        }

        if (radius > 1.0) {
            if (hasRing && !ringFront && ringAlpha > 0.05) {
                fragColor = vec4(ringColor * vertexColor.rgb * ColorModulator.rgb,
                    ringAlpha * vertexColor.a * ColorModulator.a);
                return;
            }
            discard;
        }

        float sphereZ = sqrt(max(0.0, 1.0 - radius * radius));
        vec3 normal = normalize(vec3(spherePosition, sphereZ));
        vec3 surface = planetarySurface(spherePosition);
        vec3 lightDirection = normalize(BodyLightDir);
        float rawDot = dot(normal, lightDirection);
        float diffuse = smoothstep(-0.08, 0.85, rawDot);
        float limb = smoothstep(0.0, 0.26, sphereZ);
        float ambientFloor = PlanetType < 2.5 ? 0.03 : 0.14;
        float terminator = ambientFloor + diffuse * (1.0 - ambientFloor);
        surface *= mix(0.40, 1.0, limb) * terminator;

        float rim = pow(1.0 - sphereZ, 3.0) * smoothstep(-0.15, 0.35, rawDot);
        if (PlanetType > 1.5 && PlanetType < 3.5) {
            vec3 atmosphere = PlanetType < 2.5
                ? vec3(1.00, 0.82, 0.52)
                : vec3(0.88, 0.42, 0.22);
            surface = mix(surface, atmosphere, rim * 0.55);
        } else if (PlanetType > 3.5 && PlanetType < 5.5) {
            surface = mix(surface, vec3(0.95, 0.84, 0.64), rim * 0.35);
        } else if (PlanetType > 5.5) {
            vec3 atmosphere = PlanetType < 6.5
                ? vec3(0.46, 0.86, 0.96)
                : vec3(0.24, 0.44, 0.94);
            surface = mix(surface, atmosphere, rim * 0.45);
        }

        fragColor = vec4(surface * vertexColor.rgb * ColorModulator.rgb,
            vertexColor.a * ColorModulator.a);
        return;
    }

    // Soft radial glow pass around the Sun or Moon (purely radial, never clipped by sprite edges).
    if (GlowScale > 1.0) {
        vec2 glowUV = MoonPass > 0.5 ? clamp(texCoord0 * vec2(4.0, 2.0), 0.0, 1.0) : texCoord0;
        float radius = length(glowUV * 2.0 - 1.0);
        float centerGlow = exp(-radius * radius * 14.0);
        float edgeFade = 1.0 - smoothstep(0.45, 0.92, radius);
        float glow = centerGlow * edgeFade;
        if (glow <= 0.001) {
            discard;
        }
        fragColor = vec4(
            vertexColor.rgb * ColorModulator.rgb,
            glow * vertexColor.a * ColorModulator.a
        );
        return;
    }

    // Main Sun or Moon body pass.
    vec4 bodyColor = texture(Sampler0, texCoord0);

    if (MoonPass > 0.5) {
        // Sampled from Full Moon tile (0,0) of moon_phases.png (32x32 tile; crisp 8x8 Moon sits at pixels 12..19).
        vec2 tileUV = clamp(texCoord0 * vec2(4.0, 2.0), 0.0, 1.0);
        vec2 moonDiskPos = (tileUV - 0.5) / 0.125;
        float boxDist = max(abs(moonDiskPos.x), abs(moonDiskPos.y));

        vec3 sunDir = normalize(BodyLightDir);
        float overallIllum = clamp(sunDir.z * 0.5 + 0.5, 0.0, 1.0);
        float earthshine = StyleParams.y;

        // Directional phase lighting ONLY inside the 8x8 Moon body; outside it, modulate vanilla's baked circular
        // halo uniformly by overall lunar illumination so no rectangular cross/cut ever appears.
        vec2 clampedPos = clamp(moonDiskPos, -1.0, 1.0);
        vec2 shadePos = pixelatedStyle > 0.5
            ? (floor((clampedPos * 0.5 + 0.5) * 8.0) + 0.5) / 8.0 * 2.0 - 1.0
            : clampedPos;
        float nz = sqrt(max(0.0, 1.0 - clamp(dot(shadePos, shadePos) * 0.62, 0.0, 0.99)));
        vec3 moonNormal = normalize(vec3(shadePos, nz));
        float ndotl = dot(moonNormal, sunDir);
        float terminatorSoftness = pixelatedStyle > 0.5 ? 0.05 : 0.12;
        float lit = smoothstep(-terminatorSoftness, terminatorSoftness * 1.4, ndotl);

        vec3 diskPhaseLighting = mix(vec3(0.58, 0.74, 1.0) * earthshine, vec3(1.0), lit);
        // Smooth radial modulation for the faint circular halo pixels baked into vanilla moon_phases.png.
        vec3 haloLighting = vec3(overallIllum * overallIllum * 0.75 + earthshine * 0.25);
        float isHalo = smoothstep(0.98, 1.15, boxDist);
        bodyColor.rgb *= mix(diskPhaseLighting, haloLighting, isHalo);

        // Real-proportion lunar eclipse umbra & penumbra shadow.
        float shadowDistance = length(moonDiskPos - LunarShadowOffset);
        float umbra = 1.0 - smoothstep(2.56, 2.82, shadowDistance);
        float penumbra = 1.0 - smoothstep(4.45, 4.95, shadowDistance);
        bodyColor.rgb *= 1.0 - max(penumbra - umbra, 0.0) * 0.25;
        bodyColor.rgb = mix(
            bodyColor.rgb,
            bodyColor.rgb * vec3(0.58, 0.13, 0.045),
            umbra
        );
    } else {
        float solarEclipse = StyleParams.w;
        if (solarEclipse > 0.01) {
            vec2 sunDisk = (texCoord0 - 0.5) / 0.125;
            float dist = pixelatedStyle > 0.5
                ? max(abs(sunDisk.x), abs(sunDisk.y))
                : length(sunDisk);
            float occultation = (1.0 - smoothstep(0.85, 1.02, dist)) * solarEclipse;
            bodyColor.rgb *= 1.0 - occultation * 0.96;
        }
    }

    fragColor = bodyColor * vertexColor * ColorModulator;
}
