#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float CloudRain;
uniform float CloudThunder;
uniform vec3 CloudSunDirection;
uniform vec3 CloudMoonDirection;
uniform float CloudDeckOpacity;
// rgb = warm twilight color, a = twilight strength
uniform vec4 CloudTwilightColor;
// x = moonIllumination, y = nightStrength, z = sunElevationSin, w = biomeDust
uniform vec4 CloudMoonParams;

in vec2 texCoord0;
in float vertexDistance;
in vec4 vertexColor;
in vec3 cloudNormal;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    color.a *= clamp(CloudDeckOpacity, 0.0, 1.0);
    if (color.a <= 0.01) {
        discard;
    }

    vec3 normal = normalize(cloudNormal);
    vec3 sunDir = normalize(CloudSunDirection);
    vec3 moonDir = normalize(CloudMoonDirection);
    float directLight = max(dot(normal, sunDir), 0.0);
    float volumeLight = 0.70 + max(normal.y, 0.0) * 0.08 + directLight * 0.22;
    color.rgb *= volumeLight;

    float rain = clamp(CloudRain, 0.0, 1.0);
    float thunder = clamp(CloudThunder, 0.0, 1.0);
    float overcast = clamp(max(rain * 0.75, thunder), 0.0, 1.0);

    // Sunrise and sunset illumination: low sun lights cloud undersides and Sun-facing edges in vivid
    // golden-amber -> coral-orange -> rose-magenta afterglow, while shadowed faces take cool violet-indigo.
    float twilightStrength = CloudTwilightColor.a * (1.0 - overcast * 0.68);
    if (twilightStrength > 0.001) {
        float sunElev = CloudMoonParams.z;
        float afterglow = smoothstep(0.06, -0.06, sunElev);
        float underside = clamp(-normal.y, 0.0, 1.0);
        float sunFacing = clamp(dot(normal, sunDir) * 0.5 + 0.5, 0.0, 1.0);
        float warmFactor = clamp(underside * 0.85 + sunFacing * 0.60, 0.0, 1.0);
        vec3 goldenUnder = vec3(1.12, 0.62, 0.28);
        vec3 roseAfterglow = vec3(1.06, 0.44, 0.52);
        vec3 warmTint = mix(goldenUnder, roseAfterglow, afterglow * 0.75);
        warmTint = mix(warmTint, CloudTwilightColor.rgb * 1.25, 0.40);
        vec3 coolShadowTint = mix(vec3(0.50, 0.48, 0.74), vec3(0.32, 0.30, 0.54), afterglow);
        vec3 twilightTint = mix(coolShadowTint, warmTint, warmFactor);
        color.rgb = mix(color.rgb, color.rgb * twilightTint * 1.18, twilightStrength * 0.90);
    }

    // Subtle cool silver-blue moonlight on clouds during clear moonlit nights.
    float moonIllum = CloudMoonParams.x;
    float nightStrength = CloudMoonParams.y;
    float moonAbove = smoothstep(-0.05, 0.18, moonDir.y);
    float moonLight = moonIllum * nightStrength * moonAbove * (1.0 - overcast * 0.85);
    if (moonLight > 0.005) {
        float moonFacing = 0.35 + 0.65 * max(dot(normal, moonDir), 0.0);
        color.rgb += vec3(0.055, 0.075, 0.125) * moonLight * moonFacing;
    }

    // Subtle warm desert dust tint during daytime in arid biomes.
    float biomeDust = CloudMoonParams.w * (1.0 - nightStrength) * (1.0 - overcast);
    color.rgb = mix(color.rgb, color.rgb * vec3(1.04, 0.99, 0.92), biomeDust * 0.35);

    vec3 stormTint = mix(vec3(0.88, 0.91, 0.96), vec3(0.82, 0.86, 0.93), thunder);
    color.rgb *= mix(vec3(1.0), stormTint, overcast);
    color.a = min(color.a * (1.0 + rain * 0.12 + thunder * 0.02), 1.0);

    float fade = 1.0 - smoothstep(FogStart, FogEnd, vertexDistance);
    color.a *= fade;
    if (color.a <= 0.01) {
        discard;
    }

    fragColor = color;
}
