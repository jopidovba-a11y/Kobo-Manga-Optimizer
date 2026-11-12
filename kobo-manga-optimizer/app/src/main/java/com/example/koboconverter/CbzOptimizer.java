package com.example.koboconverter;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import com.github.junrar.Junrar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.CRC32;

public class CbzOptimizer {

    public static final int QUALITY_LOSSLESS = -1;
    private static final int BORDER_DARK_THRESHOLD = 60;
    private static final int BORDER_FLATNESS_RANGE = 30;

    public interface OptimizationCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(Exception e);
    }

    public enum RotationMode { ALL, AUTO_HORIZONTAL, NONE }
    public enum BorderColor { WHITE, BLACK, AUTO }

    private enum ArchiveType { ZIP, RAR, UNKNOWN }

    private static class ArchiveEntryData {
        final String name;
        final byte[] data;
        ArchiveEntryData(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    private static class OutputTarget {
        final Uri uri;
        final OutputStream outputStream;
        final File legacyFile;
        OutputTarget(Uri uri, OutputStream outputStream, File legacyFile) {
            this.uri = uri;
            this.outputStream = outputStream;
            this.legacyFile = legacyFile;
        }
    }

    private static final Comparator<String> NATURAL_ORDER = (a, b) -> {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startI = i, startJ = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String numA = a.substring(startI, i).replaceFirst("^0+(?=\\d)", "");
                String numB = b.substring(startJ, j).replaceFirst("^0+(?=\\d)", "");
                if (numA.length() != numB.length()) {
                    return numA.length() - numB.length();
                }
                int cmp = numA.compareTo(numB);
                if (cmp != 0) return cmp;
            } else {
                if (ca != cb) {
                    return Character.toLowerCase(ca) - Character.toLowerCase(cb);
                }
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    };

    // -----------------------------------------------------------------------
    // Device tier detection
    // -----------------------------------------------------------------------

    /** Returns total physical RAM in megabytes, or 0 if unavailable. */
    private static long getTotalRamMB(Context context) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem / (1024L * 1024L);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Picks a thread count for the Rust batch processor based on the device's
     * core count and RAM. Leaves at least one core free for the system UI and
     * caps by RAM tier so low-end devices don't get overloaded.
     *
     * Flagship (8+ cores, 8+ GB):     6 threads
     * Upper-mid (8 cores, 6 GB):      5 threads
     * Mid-range (6+ cores, 4 GB):     4 threads
     * Budget (4 cores, 3 GB):         3 threads
     * Low (2-4 cores, < 3 GB):        2 threads
     */
    private static int computeOptimalThreads(Context context) {
        int cores = Runtime.getRuntime().availableProcessors();
        long ramMB = getTotalRamMB(context);

        // Core-based base: leave at least one core for Android + UI.
        int base = Math.max(2, cores - 1);

        // RAM-based cap: ensures modest devices don't oversubscribe.
        long ramCap;
        if (ramMB >= 7000)      ramCap = 6;
        else if (ramMB >= 5000) ramCap = 5;
        else if (ramMB >= 3000) ramCap = 4;
        else                    ramCap = 2;

        return (int) Math.max(1, Math.min(base, ramCap));
    }

    /**
     * Batch size for the Rust processor. Sized at 2× threads so each round
     * keeps every worker busy (with batch == threads, the final round only
     * uses 1-2 threads). Floored at 8 for small devices so per-batch JNI
     * overhead stays negligible.
     */
    private static int computeOptimalBatchSize(int threads) {
        return Math.max(8, threads * 2);
    }

    public static void optimizeMultiple(Context context, List<Uri> uris, int targetWidth, int targetHeight,
                                         DeviceProfile.OutputFormat format, int quality,
                                         RotationMode rotationMode, BorderColor borderColor,
                                         boolean colorMangaMode, OptimizationCallback callback) {
        new Thread(() -> {
            int totalFiles = uris.size();
            int currentFileIndex = 1;
            int successCount = 0;
            List<String> failedFiles = new ArrayList<>();

            // Decide parallelism once per conversion (device properties don't change mid-run).
            final int rustThreads = computeOptimalThreads(context);
            final int batchSize = computeOptimalBatchSize(rustThreads);
            android.util.Log.i("RUST", "device " + Build.MODEL
                    + " cores=" + Runtime.getRuntime().availableProcessors()
                    + " ram=" + getTotalRamMB(context) + "MB"
                    + " → threads=" + rustThreads + " batch=" + batchSize);

            for (Uri inputUri : uris) {
                String originalName = getFileName(context, inputUri);
                String baseName = originalName;
                int dot = baseName.lastIndexOf('.');
                if (dot != -1) {
                    baseName = baseName.substring(0, dot);
                }

                boolean collectPages = format != DeviceProfile.OutputFormat.CBZ;
                boolean lossless = quality == QUALITY_LOSSLESS;
                String pageExtension = lossless ? ".png" : ".jpg";
                String extension = format == DeviceProfile.OutputFormat.EPUB ? ".epub"
                        : format == DeviceProfile.OutputFormat.PDF ? ".pdf" : ".cbz";
                String displayName = baseName + "_fixed" + extension;
                String mimeType = format == DeviceProfile.OutputFormat.EPUB ? "application/epub+zip"
                        : format == DeviceProfile.OutputFormat.PDF ? "application/pdf" : "application/x-cbz";

                OutputTarget target = null;

                try {
                    ArchiveType type = detectArchiveType(context, inputUri);
                    if (type == ArchiveType.UNKNOWN) {
                        throw new IOException("unsupported archive format (not ZIP/CBZ/EPUB or RAR/CBR)");
                    }

                    List<ArchiveEntryData> entries = (type == ArchiveType.ZIP)
                            ? readZipEntries(context, inputUri)
                            : readRarEntries(context, inputUri);

                    entries.sort((e1, e2) -> NATURAL_ORDER.compare(e1.name, e2.name));

                    target = createOutputTarget(context, displayName, mimeType);

                    ZipOutputStream zos = null;
                    List<byte[]> pageBytesList = null;
                    if (collectPages) {
                        pageBytesList = new ArrayList<>();
                    } else {
                        zos = new ZipOutputStream(target.outputStream);
                    }

                    int skippedPages = 0;

                    // Non-image entries (ComicInfo.xml, etc.) are copied as-is into CBZ output.
                    List<ArchiveEntryData> imageEntries = new ArrayList<>();
                    for (ArchiveEntryData e : entries) {
                        if (isImage(e.name.toLowerCase())) {
                            imageEntries.add(e);
                        } else if (zos != null) {
                            ZipEntry newEntry = new ZipEntry(e.name);
                            zos.putNextEntry(newEntry);
                            zos.write(e.data);
                            zos.closeEntry();
                        }
                    }

                    // Pages are converted in batches (Rust, in parallel).
                    // Any page Rust cannot convert falls back to the Java renderer.
                    int totalPages = imageEntries.size();
                    for (int start = 0; start < totalPages; start += batchSize) {
                        int end = Math.min(start + batchSize, totalPages);
                        callback.onProgress("File " + currentFileIndex + " of " + totalFiles + "\n" +
                                "Optimizing pages " + (start + 1) + "-" + end + " of " + totalPages + "...\n" + baseName);

                        byte[][] batch = convertBatch(imageEntries, start, end, targetWidth, targetHeight,
                                quality, rotationMode, borderColor, colorMangaMode, rustThreads);

                        for (int i = 0; i < batch.length; i++) {
                            byte[] pageBytes = batch[i];
                            if (pageBytes == null) {
                                skippedPages++;
                                continue;
                            }
                            if (collectPages) {
                                pageBytesList.add(pageBytes);
                            } else {
                                String outputPageBaseName = String.format("page_%04d", start + i + 1);
                                writeStoredZipEntry(zos, outputPageBaseName + pageExtension, pageBytes);
                            }
                        }
                    }

                    if (format == DeviceProfile.OutputFormat.EPUB) {
                        callback.onProgress("Packaging EPUB...\n" + baseName);
                        EpubPackager.buildEpub(target.outputStream, baseName, pageBytesList, targetWidth, targetHeight, lossless);
                    } else if (format == DeviceProfile.OutputFormat.PDF) {
                        callback.onProgress("Packaging PDF...\n" + baseName);
                        PdfPackager.buildPdf(target.outputStream, pageBytesList, targetWidth, targetHeight);
                    } else {
                        zos.close();
                    }

                    target.outputStream.close();
                    successCount++;
                    if (skippedPages > 0) {
                        failedFiles.add(baseName + " (" + skippedPages + " unreadable page(s) skipped)");
                    }

                } catch (Exception fileError) {
                    failedFiles.add(originalName + " (" + fileError.getMessage() + ")");
                    deleteOutputTarget(context, target);
                }

                currentFileIndex++;
            }

            if (successCount == 0 && totalFiles > 0) {
                callback.onError(new Exception("All files failed: " + String.join("; ", failedFiles)));
                return;
            }

            StringBuilder summary = new StringBuilder();
            summary.append("Done!\n").append(successCount).append(" of ").append(totalFiles)
                    .append(" manga files saved to Downloads.");
            if (!failedFiles.isEmpty()) {
                summary.append("\nIssues: ").append(String.join("; ", failedFiles));
            }
            callback.onSuccess(summary.toString());

        }).start();
    }

    /**
     * Converts pages [start, end) of imageEntries. Returns one entry per page;
     * null means the page could not be converted by any path.
     *
     * `threads` is computed once by optimizeMultiple based on the device tier
     * (see computeOptimalThreads). Do not pass a hardcoded value.
     */
    private static byte[][] convertBatch(List<ArchiveEntryData> imageEntries, int start, int end,
                                         int targetWidth, int targetHeight, int quality,
                                         RotationMode rotationMode, BorderColor borderColor,
                                         boolean colorMangaMode, int threads) {
        int size = end - start;
        byte[][] results = new byte[size][];

        // Rust path (color manga mode still uses the Java renderer).
        if (!colorMangaMode && RustPageProcessor.isAvailable()) {
            try {
                byte[][] input = new byte[size][];
                for (int i = 0; i < size; i++) {
                    input[i] = imageEntries.get(start + i).data;
                }
                long startNs = System.nanoTime();
                byte[][] rustResults = RustPageProcessor.processBatch(input, targetWidth, targetHeight,
                        quality, rotationMode.ordinal(), borderColor.ordinal(), threads);
                if (rustResults != null && rustResults.length == size) {
                    results = rustResults;
                }
                android.util.Log.d("RUST", "pages " + (start + 1) + "-" + end + " took "
                        + (System.nanoTime() - startNs) / 1000000 + " ms (threads=" + threads + ")");
                String rustErrors = RustPageProcessor.drainErrors();
                if (rustErrors != null && !rustErrors.isEmpty()) {
                    android.util.Log.w("RUST", "batch at page " + (start + 1) + ":\n" + rustErrors);
                }
            } catch (Throwable t) {
                android.util.Log.e("RUST", "batch failed, falling back to Java", t);
            }
        }

        // Java fallback for every page that is still missing.
        for (int i = 0; i < size; i++) {
            if (results[i] != null) continue;
            ArchiveEntryData e = imageEntries.get(start + i);
            try {
                results[i] = renderPage(e.data, targetWidth, targetHeight,
                        quality, rotationMode, borderColor, colorMangaMode);
            } catch (Throwable t) {
                android.util.Log.e("PAGEFAIL", "page " + (start + i + 1) + " (" + e.name + ") failed", t);
            }
        }
        return results;
    }

    private static byte[] renderPage(byte[] imageBytes, int targetWidth, int targetHeight, int quality,
                                      RotationMode rotationMode, BorderColor borderColor,
                                      boolean colorMangaMode) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("could not decode image");
        }

        options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;

        Bitmap decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);

        if (decodedBitmap == null) {
            throw new IOException("could not decode image");
        }

        Bitmap orientedBitmap = applyRotation(decodedBitmap, rotationMode);
        Bitmap strippedBitmap = stripUniformDarkBorder(orientedBitmap);
        Bitmap finalBitmap = autoCropCenterAndGrayscale(strippedBitmap, targetWidth, targetHeight, borderColor);

        if (colorMangaMode) {
            applyColorEnhancements(finalBitmap);
        }

        boolean lossless = quality == QUALITY_LOSSLESS;
        ByteArrayOutputStream pageOut = new ByteArrayOutputStream();
        if (lossless) {
            GrayscalePngEncoder.encode(finalBitmap, pageOut);
        } else {
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, pageOut);
        }
        byte[] result = pageOut.toByteArray();

        finalBitmap.recycle();
        if (strippedBitmap != finalBitmap) {
            strippedBitmap.recycle();
        }

        return result;
    }

    private static OutputTarget createOutputTarget(Context context, String displayName, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri itemUri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (itemUri == null) {
                throw new IOException("could not create output file in Downloads");
            }
            OutputStream os = context.getContentResolver().openOutputStream(itemUri);
            if (os == null) {
                throw new IOException("could not open output stream for Downloads file");
            }
            return new OutputTarget(itemUri, os, null);
        } else {
            File outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            File file = new File(outputDir, displayName);
            OutputStream os = new FileOutputStream(file);
            return new OutputTarget(Uri.fromFile(file), os, file);
        }
    }

    private static void deleteOutputTarget(Context context, OutputTarget target) {
        if (target == null) return;
        try {
            target.outputStream.close();
        } catch (Exception ignored) {
        }
        if (target.legacyFile != null) {
            if (target.legacyFile.exists()) {
                target.legacyFile.delete();
            }
        } else {
            context.getContentResolver().delete(target.uri, null, null);
        }
    }

    private static ArchiveType detectArchiveType(Context context, Uri uri) throws IOException {
        byte[] header = new byte[8];
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return ArchiveType.UNKNOWN;
            int read = is.read(header);
            if (read < 4) return ArchiveType.UNKNOWN;
        }
        if ((header[0] & 0xFF) == 0x50 && (header[1] & 0xFF) == 0x4B) {
            return ArchiveType.ZIP;
        }
        if ((header[0] & 0xFF) == 0x52 && (header[1] & 0xFF) == 0x61 &&
            (header[2] & 0xFF) == 0x72 && (header[3] & 0xFF) == 0x21) {
            return ArchiveType.RAR;
        }
        return ArchiveType.UNKNOWN;
    }
private static List<ArchiveEntryData> readZipEntries(Context context, Uri inputUri) throws IOException {
        List<ArchiveEntryData> entries = new ArrayList<>();
        try (InputStream is = context.getContentResolver().openInputStream(inputUri);
             ZipInputStream zis = new ZipInputStream(is)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[1024];
                int read;
                while ((read = zis.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, read);
                }
                entries.add(new ArchiveEntryData(entry.getName(), buffer.toByteArray()));
            }
        }
        return stripEpubStructuralFiles(entries);
    }

    private static List<ArchiveEntryData> stripEpubStructuralFiles(List<ArchiveEntryData> entries) {
        boolean isEpub = false;
        for (ArchiveEntryData e : entries) {
            if (e.name.equals("mimetype")) {
                String content = new String(e.data, StandardCharsets.UTF_8).trim();
                isEpub = content.equals("application/epub+zip");
                break;
            }
        }
        if (!isEpub) return entries;

        List<ArchiveEntryData> imagesOnly = new ArrayList<>();
        for (ArchiveEntryData e : entries) {
            if (isImage(e.name.toLowerCase())) {
                imagesOnly.add(e);
            }
        }
        return imagesOnly;
    }

    private static List<ArchiveEntryData> readRarEntries(Context context, Uri inputUri) throws Exception {
        File cacheDir = context.getCacheDir();
        File tempRar = new File(cacheDir, "temp_input_" + System.currentTimeMillis() + ".rar");
        File extractDir = new File(cacheDir, "temp_extract_" + System.currentTimeMillis());
        extractDir.mkdirs();

        List<ArchiveEntryData> entries = new ArrayList<>();
        try {
            try (InputStream is = context.getContentResolver().openInputStream(inputUri);
                 FileOutputStream fos = new FileOutputStream(tempRar)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) != -1) {
                    fos.write(buf, 0, read);
                }
            }

            Junrar.extract(tempRar, extractDir);

            List<File> files = new ArrayList<>();
            collectFiles(extractDir, files);

            for (File f : files) {
                String relativeName = extractDir.toURI().relativize(f.toURI()).getPath();
                entries.add(new ArchiveEntryData(relativeName, readAllBytes(f)));
            }
        } finally {
            deleteRecursive(tempRar);
            deleteRecursive(extractDir);
        }
        return entries;
    }

    private static Bitmap applyRotation(Bitmap bitmap, RotationMode mode) {
        if (mode == RotationMode.NONE) {
            return bitmap;
        }
        boolean isLandscape = bitmap.getWidth() > bitmap.getHeight();
        if (mode == RotationMode.AUTO_HORIZONTAL && !isLandscape) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(-90);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    private static void applyColorEnhancements(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int size = width * height;
        int[] pixels = new int[size];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int[] gray = new int[size];
        for (int i = 0; i < size; i++) {
            gray[i] = pixels[i] & 0xFF;
        }

        autoContrastStretch(gray);
        unsharpMask(gray, width, height);
        floydSteinbergDither(gray, width, height, 16);

        for (int i = 0; i < size; i++) {
            int v = gray[i];
            pixels[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    private static void autoContrastStretch(int[] gray) {
        int[] histogram = new int[256];
        for (int v : gray) histogram[v]++;

        long total = gray.length;
        long clipCount = (long) (total * 0.005);

        int low = 0;
        long acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += histogram[i];
            if (acc > clipCount) { low = i; break; }
        }

        int high = 255;
        acc = 0;
        for (int i = 255; i >= 0; i--) {
            acc += histogram[i];
            if (acc > clipCount) { high = i; break; }
        }

        if (high <= low) return;

        float scale = 255f / (high - low);
        for (int i = 0; i < gray.length; i++) {
            int v = Math.round((gray[i] - low) * scale);
            gray[i] = Math.max(0, Math.min(255, v));
        }
    }

    private static void unsharpMask(int[] gray, int width, int height) {
        int[] blurred = boxBlur(gray, width, height, 2);
        float amount = 0.6f;
        for (int i = 0; i < gray.length; i++) {
            int v = Math.round(gray[i] + amount * (gray[i] - blurred[i]));
            gray[i] = Math.max(0, Math.min(255, v));
        }
    }

    private static int[] boxBlur(int[] src, int width, int height, int radius) {
        int[] temp = new int[src.length];
        int[] out = new int[src.length];

        for (int y = 0; y < height; y++) {
            int rowBase = y * width;
            int sum = 0;
            for (int x = -radius; x <= radius; x++) {
                int xi = Math.max(0, Math.min(width - 1, x));
                sum += src[rowBase + xi];
            }
            for (int x = 0; x < width; x++) {
                temp[rowBase + x] = sum / (radius * 2 + 1);
                int addX = Math.min(width - 1, x + radius + 1);
                int subX = Math.max(0, x - radius);
                sum += src[rowBase + addX] - src[rowBase + subX];
            }
        }

        for (int x = 0; x < width; x++) {
            int sum = 0;
            for (int y = -radius; y <= radius; y++) {
                int yi = Math.max(0, Math.min(height - 1, y));
                sum += temp[yi * width + x];
            }
            for (int y = 0; y < height; y++) {
                out[y * width + x] = sum / (radius * 2 + 1);
                int addY = Math.min(height - 1, y + radius + 1);
                int subY = Math.max(0, y - radius);
                sum += temp[addY * width + x] - temp[subY * width + x];
            }
        }
        return out;
    }

    private static void floydSteinbergDither(int[] gray, int width, int height, int levels) {
        float step = 255f / (levels - 1);
        float[] errorBuf = new float[gray.length];
        for (int i = 0; i < gray.length; i++) errorBuf[i] = gray[i];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                float oldVal = errorBuf[idx];
                int quantized = Math.round(Math.round(oldVal / step) * step);
                quantized = Math.max(0, Math.min(255, quantized));
                gray[idx] = quantized;
                float error = oldVal - quantized;

                if (x + 1 < width) errorBuf[idx + 1] += error * 7f / 16f;
                if (y + 1 < height) {
                    if (x > 0) errorBuf[idx + width - 1] += error * 3f / 16f;
                    errorBuf[idx + width] += error * 5f / 16f;
                    if (x + 1 < width) errorBuf[idx + width + 1] += error * 1f / 16f;
                }
            }
        }
    }

    private static void writeStoredZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static boolean isImage(String lowerName) {
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".webp");
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectFiles(f, out);
            } else {
                out.add(f);
            }
        }
    }

    private static byte[] readAllBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[1024];
            int read;
            while ((read = fis.read(data)) != -1) {
                buffer.write(data, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private static int resolveAutoColor(int avgLuminance) {
        return avgLuminance < 90 ? Color.BLACK : Color.WHITE;
    }

    private static int averageLuminanceOfColumnStrip(Bitmap bitmap, boolean leftSide, int stripWidth) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int w = Math.min(stripWidth, width);
        int startX = leftSide ? 0 : width - w;

        int[] pixels = new int[w * height];
        bitmap.getPixels(pixels, 0, w, startX, 0, w, height);

        long sum = 0;
        for (int p : pixels) {
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            sum += (long) (0.299 * r + 0.587 * g + 0.114 * b);
        }
        return (int) (sum / pixels.length);
    }

    private static int averageLuminanceOfRowStrip(Bitmap bitmap, boolean topSide, int stripHeight) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int h = Math.min(stripHeight, height);
        int startY = topSide ? 0 : height - h;

        int[] pixels = new int[width * h];
        bitmap.getPixels(pixels, 0, width, 0, startY, width, h);

        long sum = 0;
        for (int p : pixels) {
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            sum += (long) (0.299 * r + 0.587 * g + 0.114 * b);
        }
        return (int) (sum / pixels.length);
    }

    private static int luminanceOf(int pixel) {
        int r = (pixel >> 16) & 0xff;
        int g = (pixel >> 8) & 0xff;
        int b = pixel & 0xff;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    private static boolean isRowUniformDark(int[] pixels, int width, int y) {
        long sum = 0;
        int min = 255, max = 0;
        int rowStart = y * width;
        for (int x = 0; x < width; x++) {
            int lum = luminanceOf(pixels[rowStart + x]);
            sum += lum;
            if (lum < min) min = lum;
            if (lum > max) max = lum;
        }
        int avg = (int) (sum / width);
        return avg < BORDER_DARK_THRESHOLD && (max - min) < BORDER_FLATNESS_RANGE;
    }

    private static boolean isColumnUniformDark(int[] pixels, int width, int height, int x) {
        long sum = 0;
        int min = 255, max = 0;
        for (int y = 0; y < height; y++) {
            int lum = luminanceOf(pixels[y * width + x]);
            sum += lum;
            if (lum < min) min = lum;
            if (lum > max) max = lum;
        }
        int avg = (int) (sum / height);
        return avg < BORDER_DARK_THRESHOLD && (max - min) < BORDER_FLATNESS_RANGE;
    }

    private static Bitmap stripUniformDarkBorder(Bitmap original) {
        int width = original.getWidth();
        int height = original.getHeight();

        int[] pixels = new int[width * height];
        original.getPixels(pixels, 0, width, 0, 0, width, height);

        int maxStripTop = height / 4;
        int maxStripBottom = height * 3 / 4;
        int maxStripLeft = width / 4;
        int maxStripRight = width * 3 / 4;

        int top = 0;
        while (top < maxStripTop && isRowUniformDark(pixels, width, top)) top++;

        int bottom = height - 1;
        while (bottom > maxStripBottom && isRowUniformDark(pixels, width, bottom)) bottom--;

        int left = 0;
        while (left < maxStripLeft && isColumnUniformDark(pixels, width, height, left)) left++;

        int right = width - 1;
        while (right > maxStripRight && isColumnUniformDark(pixels, width, height, right)) right--;

        if (top == 0 && bottom == height - 1 && left == 0 && right == width - 1) {
            return original;
        }

        int cropWidth = right - left + 1;
        int cropHeight = bottom - top + 1;
        if (cropWidth <= 0 || cropHeight <= 0) {
            return original;
        }

        Bitmap cropped = Bitmap.createBitmap(original, left, top, cropWidth, cropHeight);
        original.recycle();
        return cropped;
    }

    private static Bitmap autoCropCenterAndGrayscale(Bitmap original, int maxWidth, int maxHeight, BorderColor borderColor) {
        int width = original.getWidth();
        int height = original.getHeight();

        int[] pixels = new int[width * height];
        original.getPixels(pixels, 0, width, 0, 0, width, height);

        int[] colInk = new int[width];
        int[] rowInk = new int[height];
        int threshold = 210;

        for (int y = 0; y < height; y++) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x++) {
                int pixel = pixels[rowOffset + x];
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                int luminance = (int) (0.299 * r + 0.587 * g + 0.114 * b);

                if (luminance < threshold) {
                    colInk[x]++;
                    rowInk[y]++;
                }
            }
        }

        int minInkPerCol = Math.max(2, height / 200);
        int minInkPerRow = Math.max(2, width / 200);
        int gapSizeX = Math.max(15, width / 70);
        int gapSizeY = Math.max(15, height / 70);
        int requiredRun = 3;

        int left = 0;
        while (left < width / 2) {
            if (colInk[left] >= minInkPerCol) {
                boolean isArtifact = true;
                int checkEnd = Math.min(left + gapSizeX, width / 2);
                int consecutive = 0;
                for (int k = left + 1; k < checkEnd; k++) {
                    if (colInk[k] >= minInkPerCol) {
                        consecutive++;
                        if (consecutive >= requiredRun) { isArtifact = false; break; }
                    } else {
                        consecutive = 0;
                    }
                }
                if (isArtifact) { left = left + gapSizeX; } else { break; }
            } else {
                left++;
            }
        }

        int right = width - 1;
        while (right > width / 2) {
            if (colInk[right] >= minInkPerCol) {
                boolean isArtifact = true;
                int checkEnd = Math.max(right - gapSizeX, width / 2);
                int consecutive = 0;
                for (int k = right - 1; k > checkEnd; k--) {
                    if (colInk[k] >= minInkPerCol) {
                        consecutive++;
                        if (consecutive >= requiredRun) { isArtifact = false; break; }
                    } else {
                        consecutive = 0;
                    }
                }
                if (isArtifact) { right = right - gapSizeX; } else { break; }
            } else {
                right--;
            }
        }

        int top = 0;
        while (top < height / 2) {
            if (rowInk[top] >= minInkPerRow) {
                boolean isArtifact = true;
                int checkEnd = Math.min(top + gapSizeY, height / 2);
                int consecutive = 0;
                for (int k = top + 1; k < checkEnd; k++) {
                    if (rowInk[k] >= minInkPerRow) {
                        consecutive++;
                        if (consecutive >= requiredRun) { isArtifact = false; break; }
                    } else {
                        consecutive = 0;
                    }
                }
                if (isArtifact) { top = top + gapSizeY; } else { break; }
            } else {
                top++;
            }
        }

        int bottom = height - 1;
        while (bottom > height / 2) {
            if (rowInk[bottom] >= minInkPerRow) {
                boolean isArtifact = true;
                int checkEnd = Math.max(bottom - gapSizeY, height / 2);
                int consecutive = 0;
                for (int k = bottom - 1; k > checkEnd; k--) {
                    if (rowInk[k] >= minInkPerRow) {
                        consecutive++;
                        if (consecutive >= requiredRun) { isArtifact = false; break; }
                    } else {
                        consecutive = 0;
                    }
                }
                if (isArtifact) { bottom = bottom - gapSizeY; } else { break; }
            } else {
                bottom--;
            }
        }

        Bitmap cropped;
        if (left < right && top < bottom && (right - left) > (width / 4) && (bottom - top) > (height / 4)) {
            int cropWidth = right - left + 1;
            int cropHeight = bottom - top + 1;
            cropped = Bitmap.createBitmap(original, left, top, cropWidth, cropHeight);
        } else {
            cropped = original;
        }

        int cropW = cropped.getWidth();
        int cropH = cropped.getHeight();

        float ratio = Math.min((float) maxWidth / cropW, (float) maxHeight / cropH);
        int newWidth = Math.round(ratio * cropW);
        int newHeight = Math.round(ratio * cropH);

        if (newWidth > maxWidth) newWidth = maxWidth;
        if (newHeight > maxHeight) newHeight = maxHeight;

        Bitmap scaled = Bitmap.createScaledBitmap(cropped, newWidth, newHeight, true);

        if (cropped != original && cropped != scaled) {
            cropped.recycle();
        }

        float leftOffset = (maxWidth - newWidth) / 2f;
        float topOffset = (maxHeight - newHeight) / 2f;

        Bitmap finalBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(finalBitmap);

        if (borderColor == BorderColor.AUTO) {
            int stripSize = 12;
            canvas.drawColor(Color.WHITE);

            if (leftOffset > 0.5f) {
                int leftLum = averageLuminanceOfColumnStrip(scaled, true, stripSize);
                int rightLum = averageLuminanceOfColumnStrip(scaled, false, stripSize);

                Paint leftPaint = new Paint();
                leftPaint.setColor(resolveAutoColor(leftLum));
                canvas.drawRect(0, 0, leftOffset, maxHeight, leftPaint);

                Paint rightPaint = new Paint();
                rightPaint.setColor(resolveAutoColor(rightLum));
                canvas.drawRect(leftOffset + newWidth, 0, maxWidth, maxHeight, rightPaint);

            } else if (topOffset > 0.5f) {
                int topLum = averageLuminanceOfRowStrip(scaled, true, stripSize);
                int bottomLum = averageLuminanceOfRowStrip(scaled, false, stripSize);

                Paint topPaint = new Paint();
                topPaint.setColor(resolveAutoColor(topLum));
                canvas.drawRect(0, 0, maxWidth, topOffset, topPaint);

                Paint bottomPaint = new Paint();
                bottomPaint.setColor(resolveAutoColor(bottomLum));
                canvas.drawRect(0, topOffset + newHeight, maxWidth, maxHeight, bottomPaint);
            }
        } else {
            canvas.drawColor(borderColor == BorderColor.BLACK ? Color.BLACK : Color.WHITE);
        }

        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));

        canvas.drawBitmap(scaled, leftOffset, topOffset, paint);

        if (scaled != original && scaled != finalBitmap) {
            scaled.recycle();
        }

        return finalBitmap;
    }

    private static String getFileName(Context context, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result != null ? result : "manga";
    }
}