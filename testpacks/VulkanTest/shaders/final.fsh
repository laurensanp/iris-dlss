#version 120
uniform sampler2D colortex0;
uniform float viewWidth;
uniform float viewHeight;
varying vec2 texcoord;
void main() {
	vec2 pixel = texcoord * vec2(viewWidth, viewHeight);
	bool border = pixel.x < 8.0 || pixel.y < 8.0 || pixel.x > viewWidth - 8.0 || pixel.y > viewHeight - 8.0;
	gl_FragColor = border ? vec4(1.0, 0.0, 0.0, 1.0) : texture2D(colortex0, texcoord);
}
