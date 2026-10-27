#version 120
// separateAo test: left third vertex colour rgb, middle vertex alpha (AO with separateAo), right the texture.
uniform sampler2D texture;
uniform float viewWidth;
varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 normal;
varying float isLeaves;
void main() {
	vec4 tex = texture2D(texture, texcoord);
	if (tex.a < 0.1) discard;
	float x = gl_FragCoord.x / viewWidth;
	vec3 c = x < 0.333 ? glcolor.rgb : x < 0.667 ? vec3(glcolor.a) : tex.rgb;
	gl_FragData[0] = vec4(c, 1.0);
}
