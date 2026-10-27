#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
varying float isLeaves;
/* DRAWBUFFERS:01 */
void main() {
	vec4 color = texture2D(texture, texcoord) * glcolor;
	color.rgb *= texture2D(lightmap, lmcoord).rgb;
	if (color.a < 0.1) discard;
	// Test: terrain through the pack; leaves (block.properties id 10001) turn yellow.
	gl_FragData[0] = mix(color, vec4(1.0, 1.0, 0.0, 1.0), isLeaves * 0.6);
	gl_FragData[1] = vec4(normal * 0.5 + 0.5, 1.0);
}
