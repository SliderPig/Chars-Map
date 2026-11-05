#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out float vertexDistance;
out vec4 vertexColor;

void main() {
    vec4 place = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * place;
    vertexDistance = length(place.xyz);
    vertexColor = Color;
}
