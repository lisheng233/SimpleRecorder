package com.lisheng.simplerecorder.config;

/**
 * How RGB frames are converted to the YUV planes consumed by avifenc.
 */
public enum YuvMode {
    /** Default: a GLSL shader converts and scales on the GPU. */
    OPENGL,
    /** Fallback: multi-threaded CPU conversion, with a configurable thread count. */
    MULTITHREAD
}
