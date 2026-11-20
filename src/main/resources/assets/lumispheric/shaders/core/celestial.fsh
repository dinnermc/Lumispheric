#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float GlowScale;
uniform vec2 LunarShadowOffset;
uniform float MoonPass;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    if (GlowScale > 1.0) {
        float radius = length(texCoord0 * 2.0 - 1.0);
        float normalizedRadius = radius * GlowScale;
        float centerGlow = exp(-normalizedRadius * normalizedRadius * 10.0);
        float edgeFade = 1.0 - smoothstep(0.42, 0.74, normalizedRadius);
        float glow = centerGlow * edgeFade;
        if (MoonPass > 0.5) {
            vec4 moonTexture = texture(Sampler0, texCoord0);
            float litPhase = max(max(moonTexture.r, moonTexture.g), moonTexture.b) * moonTexture.a;
            glow *= litPhase;
        }
        fragColor = vec4(
            vertexColor.rgb * ColorModulator.rgb,
            glow * vertexColor.a * ColorModulator.a
        );
        return;
    }

    vec4 moonColor = texture(Sampler0, texCoord0);
    if (MoonPass > 0.5) {
        vec2 moonPosition = fract(texCoord0 * vec2(4.0, 2.0)) * 2.0 - 1.0;
        float shadowDistance = length(moonPosition - LunarShadowOffset);
        float umbra = 1.0 - smoothstep(2.56, 2.82, shadowDistance);
        float penumbra = 1.0 - smoothstep(4.45, 4.95, shadowDistance);
        moonColor.rgb *= 1.0 - max(penumbra - umbra, 0.0) * 0.22;
        moonColor.rgb = mix(
            moonColor.rgb,
            moonColor.rgb * vec3(0.55, 0.12, 0.04),
            umbra
        );
    }
    fragColor = moonColor * vertexColor * ColorModulator;
}
