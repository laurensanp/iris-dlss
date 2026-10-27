#version 120
varying vec4 glcolor;
varying vec3 normal;
void main() {
	gl_Position = ftransform();
	glcolor = gl_Color;
	normal = normalize(gl_NormalMatrix * gl_Normal);
}
