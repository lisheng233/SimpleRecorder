package com.lisheng.simplerecorder.avif;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

/**
 * GPU RGB(A) to YUV444 conversion and downscale for the AVIF path.
 *
 * <p>A fullscreen triangle samples the finished main framebuffer texture with a
 * 2x2 box filter and writes limited-range BT.601 Y, U, V into an RGBA8 target.
 * The PBO then reads that packed YUV target, so the expensive per-pixel math
 * does not run on the CPU.</p>
 */
public final class GpuYuvConverter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final int program;
    private final int vao;
    private final int sourceUniform;
    private final int sourceSizeUniform;

    public GpuYuvConverter() {
        int vertex = compile(GL20.GL_VERTEX_SHADER, """
                #version 150
                out vec2 vUv;
                void main() {
                    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                    vUv = vec2(p.x, 1.0 - p.y);
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, """
                #version 150
                in vec2 vUv;
                out vec4 fragColor;
                uniform sampler2D uSource;
                uniform vec2 uSourceSize;
                void main() {
                    vec2 texel = 1.0 / uSourceSize;
                    vec2 src = vUv * uSourceSize;
                    vec2 base = floor(src - 0.5) + 0.5;
                    vec4 c0 = texture(uSource, (base + vec2(-0.25, -0.25)) * texel);
                    vec4 c1 = texture(uSource, (base + vec2( 0.25, -0.25)) * texel);
                    vec4 c2 = texture(uSource, (base + vec2(-0.25,  0.25)) * texel);
                    vec4 c3 = texture(uSource, (base + vec2( 0.25,  0.25)) * texel);
                    vec4 c = (c0 + c1 + c2 + c3) * 0.25;
                    float y = 16.0 / 255.0 + (0.257 * c.r + 0.504 * c.g + 0.098 * c.b);
                    float u = 128.0 / 255.0 + (-0.148 * c.r - 0.291 * c.g + 0.439 * c.b);
                    float v = 128.0 / 255.0 + (0.439 * c.r - 0.368 * c.g - 0.071 * c.b);
                    fragColor = vec4(y, u, v, 1.0);
                }
                """);

        int linked = GL20.glCreateProgram();
        GL20.glAttachShader(linked, vertex);
        GL20.glAttachShader(linked, fragment);
        GL30.glBindFragDataLocation(linked, 0, "fragColor");
        GL20.glLinkProgram(linked);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(linked);
            GL20.glDeleteProgram(linked);
            throw new IllegalStateException("YUV shader link failed: " + log);
        }

        this.program = linked;
        this.sourceUniform = GL20.glGetUniformLocation(linked, "uSource");
        this.sourceSizeUniform = GL20.glGetUniformLocation(linked, "uSourceSize");
        this.vao = GL30.glGenVertexArrays();
        LOGGER.info("OpenGL YUV converter ready (GLSL 150, YUV444)");
    }

    /**
     * Renders the source framebuffer texture into the destination FBO. Both the
     * source and destination must be valid on the current context.
     */
    public void render(int sourceTexture, int sourceWidth, int sourceHeight,
                       int destinationFbo, int destinationWidth, int destinationHeight) {
        int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] previousViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousMinFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
        int previousMagFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
        int previousWrapS = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S);
        int previousWrapT = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T);
        int sourceMinFilter = GL11.GL_NEAREST;
        int sourceMagFilter = GL11.GL_NEAREST;
        int sourceWrapS = GL11.GL_CLAMP;
        int sourceWrapT = GL11.GL_CLAMP;

        try {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, destinationFbo);
            GlStateManager._viewport(0, 0, destinationWidth, destinationHeight);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sourceTexture);
            sourceMinFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
            sourceMagFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
            sourceWrapS = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S);
            sourceWrapT = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);

            GL20.glUseProgram(program);
            GL20.glUniform1i(sourceUniform, 0);
            GL20.glUniform2f(sourceSizeUniform, (float) sourceWidth, (float) sourceHeight);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL30.glBindVertexArray(0);
        } finally {
            GL20.glUseProgram(previousProgram);
            GL30.glBindVertexArray(previousVao);
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
            GlStateManager._viewport(previousViewport[0], previousViewport[1],
                    previousViewport[2], previousViewport[3]);
            if (blend) {
                GL11.glEnable(GL11.GL_BLEND);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }
            if (depth) {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
            } else {
                GL11.glDisable(GL11.GL_DEPTH_TEST);
            }
            if (cull) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            if (scissor) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
        }
    }

    public void close() {
        GL20.glDeleteProgram(program);
        GL30.glDeleteVertexArrays(vao);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("YUV shader compile failed: " + log);
        }
        return shader;
    }
}
