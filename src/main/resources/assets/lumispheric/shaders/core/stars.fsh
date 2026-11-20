#version 150

uniform float HorizonLevel;
uniform float HorizonFadeWidth;
uniform vec4 ColorModulator;

in vec3 localPosition;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float localUp = normalize(localPosition).y;
    if (localUp <= HorizonLevel) {
        discard;
    }

    float horizonFade = smoothstep(HorizonLevel, HorizonLevel + HorizonFadeWidth, localUp);
    fragColor = vertexColor * ColorModulator;
    fragColor.a *= horizonFade;
}
