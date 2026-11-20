#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float CloudRain;
uniform float CloudThunder;
uniform vec3 CloudSunDirection;
uniform float CloudDeckOpacity;

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
    float directLight = max(dot(normal, normalize(CloudSunDirection)), 0.0);
    float volumeLight = 0.70 + max(normal.y, 0.0) * 0.08 + directLight * 0.22;
    color.rgb *= volumeLight;

    float rain = clamp(CloudRain, 0.0, 1.0);
    float thunder = clamp(CloudThunder, 0.0, 1.0);
    float overcast = clamp(max(rain * 0.75, thunder), 0.0, 1.0);
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
