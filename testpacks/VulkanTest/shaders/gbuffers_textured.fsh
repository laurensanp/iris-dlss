#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
/* DRAWBUFFERS:01 */
void main() {
	vec4 color = texture2D(texture, texcoord) * glcolor;
	color.rgb *= texture2D(lightmap, lmcoord).rgb;
	if (color.a < 0.1) discard;
	// Test: gbuffers run on the pack's shaders; tint them green.
	gl_FragData[0] = vec4(color.r * 0.5, min(color.g * 1.5, 1.0), color.b * 0.5, color.a);
	gl_FragData[1] = vec4(normal * 0.5 + 0.5, 1.0);
}
