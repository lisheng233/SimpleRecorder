package com.lisheng.simplerecorder.avif;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * GPU RGB(A) to YUV444 conversion and downscale for the AVIF path.
 *
 * <p>A fullscreen quad samples the finished main framebuffer texture with a 2x2
 * box filter and writes limited-range BT.601 Y, U, V into an RGBA8 target. The
 * PBO then reads that packed YUV target, so the expensive per-pixel math does
 * not run on the CPU.</p>
 *
 * <p>1.12.2 runs on a legacy (compatibility) OpenGL context with LWJGL 2, so
 * this uses GLSL 120 with fixed-function vertex submission instead of the
 * core-profile {@code gl_VertexID}/{@code gl_FragColor} style used on newer
 * versions. That keeps the path working on the OpenGL 2.1 minimum the 1.12.2
 * renderer still supports.</p>
 */
public final class GpuYuvConverter {
    private static final Logger LOGGER = LogManager.getLogger();

    private final int program;
    private final int sourceUniform;
    private final int sourceSizeUniform;
    // LWJGL 2's BufferChecks require at least 16 elements for every buffer passed
    // to glGetInteger/glGetTexParameter, no matter how many values the pname
    // actually returns. A 4-element viewport buffer throws before the GL call is
    // ever issued, which is what broke the OpenGL YUV path on 1.12.2.
    private final IntBuffer scratch = BufferUtils.createIntBuffer(16);
    private final IntBuffer viewportScratch = BufferUtils.createIntBuffer(16);

    public GpuYuvConverter() {
        int vertex = compile(GL20.GL_VERTEX_SHADER,
                "#version 120\n"
                        + "void main() {\n"
                        + "    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);\n"
                        + "    gl_TexCoord[0] = gl_MultiTexCoord0;\n"
                        + "}\n");
        int fragment = compile(GL20.GL_FRAGMENT_SHADER,
                "#version 120\n"
                        + "uniform sampler2D uSource;\n"
                        + "uniform vec2 uSourceSize;\n"
                        + "void main() {\n"
                        + "    vec2 vUv = gl_TexCoord[0].xy;\n"
                        + "    vec2 texel = 1.0 / uSourceSize;\n"
                        + "    vec2 src = vUv * uSourceSize;\n"
                        + "    vec2 base = floor(src - 0.5) + 0.5;\n"
                        + "    vec4 c0 = texture2D(uSource, (base + vec2(-0.25, -0.25)) * texel);\n"
                        + "    vec4 c1 = texture2D(uSource, (base + vec2( 0.25, -0.25)) * texel);\n"
                        + "    vec4 c2 = texture2D(uSource, (base + vec2(-0.25,  0.25)) * texel);\n"
                        + "    vec4 c3 = texture2D(uSource, (base + vec2( 0.25,  0.25)) * texel);\n"
                        + "    vec4 c = (c0 + c1 + c2 + c3) * 0.25;\n"
                        + "    float y = 16.0 / 255.0 + (0.257 * c.r + 0.504 * c.g + 0.098 * c.b);\n"
                        + "    float u = 128.0 / 255.0 + (-0.148 * c.r - 0.291 * c.g + 0.439 * c.b);\n"
                        + "    float v = 128.0 / 255.0 + (0.439 * c.r - 0.368 * c.g - 0.071 * c.b);\n"
                        + "    gl_FragColor = vec4(y, u, v, 1.0);\n"
                        + "}\n");

        int linked = GL20.glCreateProgram();
        GL20.glAttachShader(linked, vertex);
        GL20.glAttachShader(linked, fragment);
        // Do not call glBindAttribLocation for "gl_Vertex": binding a name that
        // starts with "gl_" raises GL_INVALID_OPERATION. The compatibility profile
        // already maps the fixed-function attributes to the built-in names.
        GL20.glLinkProgram(linked);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(linked, 8192);
            GL20.glDeleteProgram(linked);
            throw new IllegalStateException("YUV shader link failed: " + log);
        }

        this.program = linked;
        this.sourceUniform = GL20.glGetUniformLocation(linked, "uSource");
        this.sourceSizeUniform = GL20.glGetUniformLocation(linked, "uSourceSize");
        LOGGER.info("OpenGL YUV converter ready (GLSL 120, YUV444)");
    }

    /**
     * Renders the source framebuffer texture into the destination FBO. Both the
     * source and destination must be valid on the current context.
     */
    public void render(int sourceTexture, int sourceWidth, int sourceHeight,
                       int destinationFbo, int destinationWidth, int destinationHeight) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        viewportScratch.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewportScratch);
        int previousViewportX = viewportScratch.get(0);
        int previousViewportY = viewportScratch.get(1);
        int previousViewportWidth = viewportScratch.get(2);
        int previousViewportHeight = viewportScratch.get(3);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousMinFilter = getTexParameter(GL11.GL_TEXTURE_MIN_FILTER);
        int previousMagFilter = getTexParameter(GL11.GL_TEXTURE_MAG_FILTER);
        int previousWrapS = getTexParameter(GL11.GL_TEXTURE_WRAP_S);
        int previousWrapT = getTexParameter(GL11.GL_TEXTURE_WRAP_T);
        int sourceMinFilter = GL11.GL_NEAREST;
        int sourceMagFilter = GL11.GL_NEAREST;
        int sourceWrapS = GL11.GL_CLAMP;
        int sourceWrapT = GL11.GL_CLAMP;

        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, destinationFbo);
            GL11.glViewport(0, 0, destinationWidth, destinationHeight);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);
            sourceMinFilter = getTexParameter(GL11.GL_TEXTURE_MIN_FILTER);
            sourceMagFilter = getTexParameter(GL11.GL_TEXTURE_MAG_FILTER);
            sourceWrapS = getTexParameter(GL11.GL_TEXTURE_WRAP_S);
            sourceWrapT = getTexParameter(GL11.GL_TEXTURE_WRAP_T);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            // The vertex shader writes clip space directly, so the fixed-function
            // matrices can be left exactly as the game had them.
            GL20.glUseProgram(program);
            GL20.glUniform1i(sourceUniform, 0);
            GL20.glUniform2f(sourceSizeUniform, (float) sourceWidth, (float) sourceHeight);
            // The texture is sampled vertically flipped so that glReadPixels returns
            // top-down rows straight into the encoder.
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0.0F, 1.0F);
            GL11.glVertex2f(-1.0F, -1.0F);
            GL11.glTexCoord2f(1.0F, 1.0F);
            GL11.glVertex2f(1.0F, -1.0F);
            GL11.glTexCoord2f(1.0F, 0.0F);
            GL11.glVertex2f(1.0F, 1.0F);
            GL11.glTexCoord2f(0.0F, 0.0F);
            GL11.glVertex2f(-1.0F, 1.0F);
            GL11.glEnd();
        } finally {
            GL20.glUseProgram(previousProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, sourceMinFilter);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, sourceMagFilter);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, sourceWrapS);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, sourceWrapT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, previousMinFilter);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, previousMagFilter);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, previousWrapS);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, previousWrapT);
            GL13.glActiveTexture(previousActiveTexture);
            GL11.glViewport(previousViewportX, previousViewportY,
                    previousViewportWidth, previousViewportHeight);
            setCapability(GL11.GL_BLEND, blend);
            setCapability(GL11.GL_DEPTH_TEST, depth);
            setCapability(GL11.GL_CULL_FACE, cull);
            setCapability(GL11.GL_SCISSOR_TEST, scissor);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        }
    }

    public void close() {
        GL20.glDeleteProgram(program);
    }

    private static void setCapability(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

    private int getTexParameter(int pname) {
        scratch.clear();
        GL11.glGetTexParameter(GL11.GL_TEXTURE_2D, pname, scratch);
        return scratch.get(0);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 8192);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("YUV shader compile failed: " + log);
        }
        return shader;
    }
}
