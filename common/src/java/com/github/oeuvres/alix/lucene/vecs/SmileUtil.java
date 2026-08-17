/*
 * Alix, A Lucene Indexer for XML documents.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package com.github.oeuvres.alix.lucene.vecs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Prepares native libraries used by Smile linear algebra.
 *
 * <p>Smile 6 uses the Foreign Function and Memory API for native ARPACK and
 * BLAS/LAPACK calls. When Alix ships native libraries beside the application,
 * they must be loaded before Smile initializes the corresponding binding.</p>
 *
 * <p>Local libraries are searched below {@code lib/native/<os>-<arch>} and
 * {@code lib/native}. Explicit overrides are available through
 * {@code alix.arpack.path} and {@code alix.openblas.path}; each property may
 * name either the library file itself or its containing directory. When no
 * application-side library is found, the system library path and finally
 * Smile's own operating-system lookup remain available.</p>
 */
public final class SmileUtil
{
    /** Native ARPACK library base name. */
    private static final String ARPACK_LIBRARY = "arpack";

    /** Optional native ARPACK file or directory override. */
    private static final String ARPACK_PATH_PROPERTY = "alix.arpack.path";

    /** Native OpenBLAS library base name. */
    private static final String OPENBLAS_LIBRARY = "openblas";

    /** Optional native OpenBLAS file or directory override. */
    private static final String OPENBLAS_PATH_PROPERTY = "alix.openblas.path";

    /** Whether application-side ARPACK lookup has already been attempted. */
    private static volatile boolean arpackLookupPrepared;

    /** Whether application-side OpenBLAS lookup has already been attempted. */
    private static volatile boolean openBlasLookupPrepared;

    /**
     * Prevents instantiation.
     */
    private SmileUtil()
    {
    }

    /**
     * Prepares OpenBLAS and ARPACK before Smile initializes its ARPACK binding.
     *
     * <p>OpenBLAS is prepared first because the Windows ARPACK library depends
     * on {@code libopenblas.dll}. A configured or project-local ARPACK library
     * is loaded explicitly; otherwise the system library path is tried and
     * Smile retains its own final operating-system lookup.</p>
     */
    public static void ensureArpackLoaded()
    {
        ensureOpenBlasLoaded();
        if (arpackLookupPrepared) {
            return;
        }

        synchronized (SmileUtil.class) {
            if (arpackLookupPrepared) {
                return;
            }

            final String library = System.mapLibraryName(ARPACK_LIBRARY);
            final String configured = System.getProperty(ARPACK_PATH_PROPERTY);
            if (configured != null && !configured.isBlank()) {
                loadConfigured(Path.of(configured), library, "ARPACK");
                arpackLookupPrepared = true;
                return;
            }

            if (loadLocal(library, "ARPACK")) {
                arpackLookupPrepared = true;
                return;
            }

            tryLoadLibrary(ARPACK_LIBRARY);
            arpackLookupPrepared = true;
        }
    }

    /**
     * Prepares OpenBLAS before Smile initializes its BLAS/LAPACK binding.
     *
     * <p>On Windows Smile's distribution uses {@code libopenblas.dll}. On
     * Unix-like platforms the ordinary platform-mapped OpenBLAS filename is
     * used. If no explicit or project-local library is found, the system
     * library path is tried and Smile retains its own final operating-system
     * lookup.</p>
     */
    public static void ensureOpenBlasLoaded()
    {
        if (openBlasLookupPrepared) {
            return;
        }

        synchronized (SmileUtil.class) {
            if (openBlasLookupPrepared) {
                return;
            }

            final String library = openBlasLibraryName();
            final String configured = System.getProperty(OPENBLAS_PATH_PROPERTY);
            if (configured != null && !configured.isBlank()) {
                loadConfigured(Path.of(configured), library, "OpenBLAS");
                openBlasLookupPrepared = true;
                return;
            }

            final Path besideArpack = openBlasBesideConfiguredArpack(library);
            if (besideArpack != null && Files.isRegularFile(besideArpack)) {
                loadNative(besideArpack, "OpenBLAS");
                openBlasLookupPrepared = true;
                return;
            }

            if (loadLocal(library, "OpenBLAS")) {
                openBlasLookupPrepared = true;
                return;
            }

            tryLoadLibrary(isWindows() ? "libopenblas" : OPENBLAS_LIBRARY);
            openBlasLookupPrepared = true;
        }
    }

    /**
     * Builds a descriptive failure for Smile ARPACK native initialization.
     *
     * @param cause native-binding initialization failure
     * @return exception describing supported native-library locations
     */
    static IllegalStateException arpackInitializationFailure(final Throwable cause)
    {
        final String library = System.mapLibraryName(ARPACK_LIBRARY);
        return new IllegalStateException(
            "Smile ARPACK native library '" + library + "' could not be loaded for "
                + nativePlatform() + ". Put it in lib/native/" + nativePlatform() + "/" + library
                + ", set -D" + ARPACK_PATH_PROPERTY + "=/path/to/" + library
                + " (or to its directory), or install ARPACK in the system library path. "
                + "Use --enable-native-access=ALL-UNNAMED with Java 25 for Smile's FFM access.",
            cause);
    }

    /**
     * Returns whether the current operating system is Windows.
     *
     * @return {@code true} on Windows
     */
    private static boolean isWindows()
    {
        return System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
    }

    /**
     * Loads a native library from an explicit file or directory.
     *
     * @param configured file path or containing directory
     * @param library platform-specific library filename
     * @param name human-readable library name used in diagnostics
     * @throws IllegalStateException if the configured library is absent or
     *         cannot be loaded
     */
    private static void loadConfigured(
        final Path configured,
        final String library,
        final String name
    ) {
        final Path path = Files.isDirectory(configured)
            ? configured.resolve(library)
            : configured;
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(
                name + " native library not found: " + path.toAbsolutePath().normalize());
        }
        loadNative(path, name);
    }

    /**
     * Loads a native library from the conventional project-local directories.
     *
     * @param library platform-specific library filename
     * @param name human-readable library name used in diagnostics
     * @return {@code true} if a local library was found and loaded
     */
    private static boolean loadLocal(final String library, final String name)
    {
        final Path[] candidates = {
            Path.of("lib", "native", nativePlatform(), library),
            Path.of("lib", "native", library)
        };
        for (final Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                loadNative(candidate, name);
                return true;
            }
        }
        return false;
    }

    /**
     * Loads one native library by absolute path.
     *
     * @param path native-library path
     * @param name human-readable library name used in diagnostics
     * @throws IllegalStateException if the library cannot be loaded
     */
    private static void loadNative(final Path path, final String name)
    {
        final Path absolute = path.toAbsolutePath().normalize();
        try {
            System.load(absolute.toString());
        }
        catch (final UnsatisfiedLinkError error) {
            throw new IllegalStateException(
                "Cannot load " + name + " native library " + absolute
                    + "; one of its native dependencies may be missing.",
                error);
        }
    }

    /**
     * Returns the normalized platform directory name used for local libraries.
     *
     * @return normalized {@code <os>-<arch>} identifier
     */
    private static String nativePlatform()
    {
        final String os = System.getProperty("os.name", "unknown")
            .toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "unknown")
            .toLowerCase(Locale.ROOT);

        final String osName;
        if (os.contains("win")) {
            osName = "windows";
        }
        else if (os.contains("linux")) {
            osName = "linux";
        }
        else if (os.contains("mac") || os.contains("darwin")) {
            osName = "macos";
        }
        else {
            osName = os.replaceAll("[^a-z0-9]+", "_");
        }

        final String archName;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archName = "x86_64";
        }
        else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archName = "aarch64";
        }
        else {
            archName = arch.replaceAll("[^a-z0-9]+", "_");
        }

        return osName + "-" + archName;
    }

    /**
     * Returns the OpenBLAS library beside an explicitly configured ARPACK path.
     *
     * <p>This preserves the previous Windows behaviour where
     * {@code libopenblas.dll} beside {@code arpack.dll} is loaded first.</p>
     *
     * @param library platform-specific OpenBLAS filename
     * @return sibling OpenBLAS path, or {@code null} if ARPACK is not configured
     */
    private static Path openBlasBesideConfiguredArpack(final String library)
    {
        final String configured = System.getProperty(ARPACK_PATH_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        final Path path = Path.of(configured).toAbsolutePath().normalize();
        final Path directory = Files.isDirectory(path) ? path : path.getParent();
        return directory == null ? null : directory.resolve(library);
    }

    /**
     * Builds a descriptive failure for Smile BLAS/LAPACK initialization.
     *
     * @param cause native-binding initialization failure
     * @return exception describing supported native-library locations
     */
    static IllegalStateException openBlasInitializationFailure(final Throwable cause)
    {
        final String library = openBlasLibraryName();
        return new IllegalStateException(
            "Smile BLAS/LAPACK native library '" + library + "' could not be loaded for "
                + nativePlatform() + ". Put it in lib/native/" + nativePlatform() + "/" + library
                + ", set -D" + OPENBLAS_PATH_PROPERTY + "=/path/to/" + library
                + " (or to its directory), or install OpenBLAS in the system library path. "
                + "Use --enable-native-access=ALL-UNNAMED with Java 25 for Smile's FFM access.",
            cause);
    }

    /**
     * Returns the platform-specific OpenBLAS filename used by Smile releases.
     *
     * @return OpenBLAS filename
     */
    private static String openBlasLibraryName()
    {
        return isWindows() ? "libopenblas.dll" : System.mapLibraryName(OPENBLAS_LIBRARY);
    }

    /**
     * Attempts to load a native library through the JVM system library path.
     *
     * <p>Failure is deliberately ignored because Smile performs its own native
     * lookup afterward.</p>
     *
     * @param library library base name for {@link System#loadLibrary(String)}
     */
    private static void tryLoadLibrary(final String library)
    {
        try {
            System.loadLibrary(library);
        }
        catch (final UnsatisfiedLinkError error) {
            // Smile's libraryLookup() still gets the final OS-level attempt.
        }
    }
}
