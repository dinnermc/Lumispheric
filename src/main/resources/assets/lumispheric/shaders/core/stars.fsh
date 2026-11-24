#version 150

uniform float HorizonLevel;
uniform float HorizonFadeWidth;
uniform vec4 ColorModulator;
// x = gameTimeSeconds, y = spaceBlend (0 = ground, 1 = space)
uniform vec4 StarAtmosphere;

in vec3 localPosition;
in vec3 starSeedDir;
in vec4 vertexColor;

out vec4 fragColor;

float hashStar(vec3 p) {
    vec3 v = fract(p * vec3(0.1031, 0.1030, 0.0973));
    v += dot(v, v.yzx + 33.33);
    return fract((v.x + v.y) * v.z);
}

void main() {
    float localUp = normalize(localPosition).y;
    if (localUp <= HorizonLevel) {
        discard;
    }

    float horizonFade = smoothstep(HorizonLevel, HorizonLevel + HorizonFadeWidth, localUp);
    float spaceBlend = clamp(StarAtmosphere.y, 0.0, 1.0);
    float atmosphere = 1.0 - spaceBlend;

    vec4 col = vertexColor * ColorModulator;
    if (atmosphere > 0.001) {
        // Atmospheric extinction: stars dim and subtly redden close to the horizon; vanishes in space.
        float elevationAboveHorizon = max(localUp - HorizonLevel, 0.001);
        float airMass = min(10.0, 1.0 / (elevationAboveHorizon + 0.065));
        float opticalDepth = max(airMass - 1.0, 0.0) * atmosphere;
        vec3 extinction = exp(-vec3(0.08, 0.14, 0.24) * opticalDepth);

        // Atmospheric scintillation (twinkling): livelier near the horizon, calm near zenith, zero in space.
        vec3 starBucket = floor(normalize(starSeedDir) * 140.0);
        float phase = hashStar(starBucket) * 6.2831853;
        float freq = 2.4 + hashStar(starBucket + vec3(17.0, 31.0, 11.0)) * 3.2;
        float t = StarAtmosphere.x;
        float wave = 0.65 * sin(t * freq + phase) + 0.35 * sin(t * freq * 1.73 + phase * 2.1);
        float lowHorizonBoost = 1.0 - smoothstep(0.0, 0.55, elevationAboveHorizon);
        float twinkleAmp = atmosphere * (0.09 + 0.28 * lowHorizonBoost);
        float twinkle = 1.0 + wave * twinkleAmp;

        // Subtle chromatic shimmer near the horizon (like Sirius flashing blue/amber low in the sky).
        float chroma = wave * atmosphere * lowHorizonBoost * 0.10;
        vec3 chromaShift = vec3(1.0 + chroma, 1.0, 1.0 - chroma);
        col.rgb *= extinction * twinkle * chromaShift;
    }
    col.a *= horizonFade;
    fragColor = col;
}
