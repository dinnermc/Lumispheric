#version 150

uniform vec4 ColorModulator;
uniform float ObserverAltitude;

in vec4 vertexColor;
in vec3 localPosition;

out vec4 fragColor;

void main() {
    float normalizedUp = localPosition.y / max(length(localPosition), 0.0001);
    if (normalizedUp <= 0.0) {
        discard;
    }

    float altitudeDegrees = degrees(asin(clamp(normalizedUp, 0.0, 1.0)));
    float altitudeRadians = radians(altitudeDegrees);
    float airmass = 1.0 / (
        sin(altitudeRadians) + 0.50572 * pow(altitudeDegrees + 6.07995, -1.6364)
    );
    float airDensity = exp(-max(ObserverAltitude, 0.0) / 8500.0);
    float extinction = pow(10.0, -0.4 * 0.18 * airDensity * max(0.0, airmass - 1.0));
    float horizonFade = smoothstep(0.0, 8.0, altitudeDegrees);
    float visibility = extinction * horizonFade;

    vec4 color = vertexColor * ColorModulator;
    color.rgb *= visibility;
    color.a *= visibility;
    fragColor = color;
}
