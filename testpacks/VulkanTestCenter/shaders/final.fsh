#version 120
// Left half: centerDepthSmooth as grey (top) and depthtex0 at the screen centre (bottom); right half: the world.
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform float centerDepthSmooth;
varying vec2 texcoord;
void main() {
	if (texcoord.x < 0.25) {
		float v = texcoord.y > 0.5 ? centerDepthSmooth : texture2D(depthtex0, vec2(0.5)).r;
		v = (v - 0.99) * 100.0;
		gl_FragColor = vec4(isnan(v) ? 1.0 : v, isnan(v) ? 0.0 : v, v < 0.0 ? 1.0 : v, 1.0);
	} else {
		gl_FragColor = texture2D(colortex0, texcoord);
	}
}
