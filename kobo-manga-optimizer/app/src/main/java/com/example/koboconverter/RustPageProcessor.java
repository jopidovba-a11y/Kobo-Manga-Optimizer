package com.example.koboconverter;

/**
 * Bridge to libkobo_native.so (Rust). If the library can't be loaded,
 * isAvailable() returns false and the app keeps using the Java renderer.
 */
public final class RustPageProcessor {

    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("kobo_native");
            loaded = true;
        } catch (Throwable t) {
            android.util.Log.e("RUST", "libkobo_native.so not available", t);
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private RustPageProcessor() {}

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * Converts a batch of pages in parallel.
     * Enum arguments are passed as ordinals: rotation (ALL=0, AUTO_HORIZONTAL=1, NONE=2),
     * borderColor (WHITE=0, BLACK=1, AUTO=2). quality -1 means lossless PNG.
     * Returns one byte[] per page (null = that page failed), or null if the whole batch failed.
     */
    public static native byte[][] processBatch(byte[][] pages, int targetWidth, int targetHeight,
                                               int quality, int rotation, int borderColor,
                                               int threads);

    /** Returns (and clears) error messages recorded by the Rust side. */
    public static native String drainErrors();
}