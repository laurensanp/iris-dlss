#version 330 compatibility
// Left half: shadowtex0 mip level 0, right half: mip level 4 (generated, so blurred).
const bool shadowtex0Mipmap = true;
const int shadowMapResolution = 1024;
uniform sampler2D shadowtex0;
in vec2 texcoord;
/* DRAWBUFFERS:0 */
void main() {
	float lod = texcoord.x < 0.5 ? 0.0 : 4.0;
	float depth = textureLod(shadowtex0, texcoord, lod).r;
	gl_FragData[0] = vec4(vec3(texcoord.x < 0.5 ? pow(depth, 8.0) : depth), 1.0);
}
