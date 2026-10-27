#version 120
varying vec4 glcolor;
varying vec3 normal;
/* DRAWBUFFERS:01 */
void main() {
	gl_FragData[0] = glcolor;
	gl_FragData[1] = vec4(normal * 0.5 + 0.5, 1.0);
}
