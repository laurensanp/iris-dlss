#version 120
attribute vec4 mc_Entity;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
varying float isLeaves;
void main() {
	gl_Position = ftransform();
	texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
	lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
	glcolor = gl_Color;
	normal = normalize(gl_NormalMatrix * gl_Normal);
	isLeaves = mc_Entity.x == 10001.0 ? 1.0 : 0.0;
}
