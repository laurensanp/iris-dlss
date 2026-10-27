#version 120
uniform sampler2D colortex0;
uniform sampler2D colortex1;
varying vec2 texcoord;
void main() {
	// Test: swap red and blue; the right third shows colortex1 (gbuffers normals).
	gl_FragData[0] = texcoord.x > 0.66 ? texture2D(colortex1, texcoord) : texture2D(colortex0, texcoord).bgra;
}
