package com.lisheng.simplerecorder.config;

/**
 * Recording container format.
 *
 * <p>AVIF is the default because it produces dramatically smaller files than
 * GIF. GIF remains as a compatibility fallback.</p>
 */
public enum OutputFormat {
    AVIF("avif"),
    GIF("gif");

    private final String extension;

    OutputFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public OutputFormat next() {
        return this == AVIF ? GIF : AVIF;
    }
}
