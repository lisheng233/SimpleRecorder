package com.lisheng.simplerecorder.avif;

import com.lisheng.simplerecorder.config.RecorderConfig;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * Locates or downloads a usable {@code avifenc} executable.
 *
 * <p>Resolution order: an explicit {@code avif.encoderPath}, a cached binary in
 * {@code config/simplerecorder/avif/<platform>/}, then {@code avifenc} on PATH.
 * If none exists, an async download from the official libavif release starts at
 * mod load and is given one minute. The official archive is used directly; the
 * mod does not redistribute the binary.</p>
 */
public final class AvifSupport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Object LOCK = new Object();
    private static final String RELEASE = "v1.4.2";
    private static final String RELEASE_BASE =
            "https://github.com/AOMediaCodec/libavif/releases/download/" + RELEASE + "/";
    private static final String MIRROR_PREFIX = "https://gh-proxy.com/";
    private static final long DOWNLOAD_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(1L);

    private static volatile boolean resolved;
    private static volatile boolean preparing;
    private static volatile String command;
    private static volatile String error = "not checked yet";

    private AvifSupport() {
    }

    /**
     * Starts the one-minute download during client startup. Never blocks the
     * render thread.
     */
    public static void prepareAsync() {
        if (resolved) {
            return;
        }
        synchronized (LOCK) {
            if (preparing || resolved) {
                return;
            }
            preparing = true;
            error = "AVIF encoder is still downloading (up to 1 minute)";
        }
        Thread thread = new Thread(AvifSupport::resolveAndDownload, "SimpleRecorder-AVIF-Download");
        thread.setDaemon(true);
        thread.start();
    }

    public static boolean isAvailable() {
        if (command != null) {
            return true;
        }
        if (preparing) {
            error = "AVIF encoder is still downloading (up to 1 minute)";
            return false;
        }
        if (resolved) {
            return false;
        }
        if (tryLocal()) {
            return true;
        }
        prepareAsync();
        return false;
    }

    /**
     * Called after client config has loaded. A fast failure before the config
     * was available (for example on an architecture with no official build) is
     * retried so that a custom download URL or encoder path can take effect.
     */
    public static void retryAfterConfig() {
        synchronized (LOCK) {
            if (!preparing && command == null) {
                resolved = false;
                error = "not checked yet";
            }
        }
        prepareAsync();
    }

    public static String command() {
        if (command == null) {
            tryLocal();
        }
        return command;
    }

    public static String error() {
        return error;
    }

    public static synchronized void reset() {
        resolved = false;
        command = null;
        error = "not checked yet";
    }

    private static void resolveAndDownload() {
        try {
            if (tryLocal()) {
                return;
            }

            Platform platform = Platform.current();
            if (platform == null) {
                error = "unsupported platform for automatic avifenc download";
                return;
            }
            String custom = configuredDownloadUrl();
            if (!platform.officialBuild && custom.isEmpty()) {
                error = "no official or known community avifenc build for " + platform.id
                        + "; find a compatible avifenc and set avif.encoderPath or avif.downloadUrl";
                return;
            }

            String assetUrl = custom.isEmpty() ? RELEASE_BASE + platform.asset : custom;
            Path cache = cacheExecutable(platform);
            Files.createDirectories(cache.getParent());

            if (downloadArchive(assetUrl, platform.entryName, cache)) {
                if (isUsable(cache.toString())) {
                    command = cache.toAbsolutePath().toString();
                    error = "";
                    LOGGER.info("Downloaded AVIF encoder for {}: {}", platform.id, command);
                } else {
                    error = "downloaded avifenc for " + platform.id + " is not runnable";
                }
            }
        } catch (Throwable throwable) {
            error = "avifenc download failed: " + throwable.getMessage();
            LOGGER.warn("Could not download avifenc", throwable);
        } finally {
            resolved = true;
            preparing = false;
        }
    }

    private static boolean tryLocal() {
        synchronized (LOCK) {
            if (resolved) {
                return command != null;
            }
            String configured = configuredPath();
            if (!configured.isEmpty() && isUsable(configured)) {
                command = configured;
                error = "";
                resolved = true;
                return true;
            }

            Platform platform = Platform.current();
            if (platform != null) {
                Path cached = cacheExecutable(platform);
                if (Files.isRegularFile(cached) && isUsable(cached.toString())) {
                    command = cached.toAbsolutePath().toString();
                    error = "";
                    resolved = true;
                    return true;
                }
            }

            if (isUsable("avifenc")) {
                command = "avifenc";
                error = "";
                resolved = true;
                return true;
            }
            return false;
        }
    }

    private static boolean isUsable(String candidate) {
        try {
            if (candidate.contains("/") || candidate.contains("\\")) {
                Path path;
                try {
                    path = Path.of(candidate);
                } catch (InvalidPathException exception) {
                    error = "invalid avifenc path: " + candidate;
                    return false;
                }
                if (!Files.isRegularFile(path)) {
                    error = "avifenc file not found: " + path;
                    return false;
                }
            }

            Process process = new ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start();
            try (InputStream input = process.getInputStream()) {
                input.transferTo(ByteArrayOutputStream.nullOutputStream());
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                error = "avifenc --version timed out: " + candidate;
                return false;
            }
            if (process.exitValue() != 0) {
                error = "avifenc --version exited with " + process.exitValue() + ": " + candidate;
                return false;
            }
            return true;
        } catch (IOException exception) {
            error = "could not run avifenc (" + candidate + "): " + exception.getMessage();
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error = "interrupted while checking avifenc";
            return false;
        }
    }

    private static boolean downloadArchive(String url, String entryName, Path destination) throws IOException {
        long deadline = System.nanoTime() + DOWNLOAD_TIMEOUT_NANOS;
        String[] urls = url.startsWith(MIRROR_PREFIX) || url.startsWith("https://github.com/")
                ? new String[]{url, MIRROR_PREFIX + url}
                : new String[]{url};

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        for (String candidate : urls) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                break;
            }
            Path archive = destination.getParent().resolve("avifenc-download.zip");
            try {
                Duration requestTimeout = candidate.startsWith("https://github.com/")
                        ? Duration.ofSeconds(Math.max(1L,
                                Math.min(10L, TimeUnit.NANOSECONDS.toSeconds(remaining))))
                        : Duration.ofNanos(remaining);
                HttpRequest request = HttpRequest.newBuilder(URI.create(candidate))
                        .timeout(requestTimeout)
                        .GET()
                        .build();
                LOGGER.info("Downloading AVIF encoder from {}", candidate);
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    error = "download returned HTTP " + response.statusCode();
                    continue;
                }
                try (InputStream input = response.body()) {
                    Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING);
                }
                if (extract(archive, entryName, destination) && isUsable(destination.toString())) {
                    Files.deleteIfExists(archive);
                    return true;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                error = "avifenc download interrupted";
                return false;
            } catch (IOException | RuntimeException exception) {
                error = "avifenc download failed: " + exception.getMessage();
            } finally {
                try {
                    Files.deleteIfExists(archive);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            }
        }
        return false;
    }

    private static boolean extract(Path archive, String entryName, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.equals(entryName) || name.endsWith("/" + entryName)) {
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                    destination.toFile().setExecutable(true);
                    return Files.size(destination) > 0L;
                }
            }
        }
        error = "downloaded archive did not contain " + entryName;
        return false;
    }

    private static Path cacheExecutable(Platform platform) {
        try {
            return FMLPaths.CONFIGDIR.get()
                    .resolve("simplerecorder")
                    .resolve("avif")
                    .resolve(platform.id)
                    .resolve(platform.entryName);
        } catch (Throwable ignored) {
            return Path.of(System.getProperty("java.io.tmpdir"), "simplerecorder-avif", platform.id)
                    .resolve(platform.entryName);
        }
    }

    private static String configuredPath() {
        try {
            String value = RecorderConfig.AVIF_ENCODER_PATH.get();
            return value == null ? "" : value.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String configuredDownloadUrl() {
        try {
            String value = RecorderConfig.AVIF_DOWNLOAD_URL.get();
            return value == null ? "" : value.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private enum Platform {
        WINDOWS_X64("windows-x64", "avifenc.exe", "windows-artifacts.zip", true),
        LINUX_X64("linux-x64", "avifenc", "linux-artifacts.zip", true),
        MACOS_ARM64("macos-arm64", "avifenc", "macOS-artifacts.zip", true),
        MACOS_X64("macos-x64", "avifenc", "macOS-artifacts.zip", false),
        LINUX_ARM64("linux-arm64", "avifenc", "linux-artifacts.zip", false);

        private final String id;
        private final String entryName;
        private final String asset;
        private final boolean officialBuild;

        Platform(String id, String entryName, String asset, boolean officialBuild) {
            this.id = id;
            this.entryName = entryName;
            this.asset = asset;
            this.officialBuild = officialBuild;
        }

        static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            boolean arm = arch.contains("aarch64") || arch.contains("arm64");
            boolean x64 = arch.contains("amd64") || arch.contains("x86_64");
            if (os.contains("win")) {
                return x64 ? WINDOWS_X64 : null;
            }
            if (os.contains("linux")) {
                if (arm) {
                    return LINUX_ARM64;
                }
                return x64 ? LINUX_X64 : null;
            }
            if (os.contains("mac")) {
                if (arm) {
                    return MACOS_ARM64;
                }
                return x64 ? MACOS_X64 : null;
            }
            return null;
        }
    }
}
