#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 CelestialToLocal;

out vec3 localPosition;
out vec4 vertexColor;

void main() {
    vec4 local = CelestialToLocal * vec4(Position, 1.0);
    localPosition = local.xyz;
    vertexColor = Color;
    gl_Position = ProjMat * ModelViewMat * local;
}
