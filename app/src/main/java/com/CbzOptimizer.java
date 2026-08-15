package com.example.koboconverter;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import com.github.junrar.Junrar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class CbzOptimizer {

    public interface OptimizationCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(Exception e);
    }

    private enum ArchiveType { ZIP, RAR, UNKNOWN }

    public static void optimizeMultiple(Context context, List<Uri> uris, DeviceProfile profile,
                                         DeviceProfile.OutputFormat format, OptimizationCallback callback) {
        new Thread(() -> {
            try {
                File outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }

                int totalFiles = uris.size();
                int currentFileIndex = 1;

                for (Uri inputUri : uris) {
                    String originalName = getFileName(context, inputUri);
                    String baseName = originalName;
                    int dot = baseName.lastIndexOf('.');
                    if (dot != -1) {
                        baseName = baseName.substring(0, dot);
                    }

                    boolean isEpub = format == DeviceProfile.OutputFormat.EPUB;
                    String extension = isEpub ? ".epub" : ".cbz";
                    File outputFile = new File(outputDir, baseName + "_fixed" + extension);

                    ArchiveType type = detectArchiveType(context, inputUri);

                    if (type == ArchiveType.UNKNOWN) {
                        throw new IOException("Unsupported archive format for \"" + originalName +
                                "\" (only ZIP/CBZ and RAR/CBR are supported)");
                    }

                    ZipOutputStream zos = null;
                    List<byte[]> epubPages = null;
                    if (isEpub) {
                        epubPages = new ArrayList<>();
                    } else {
                        zos = new ZipOutputStream(new FileOutputStream(outputFile));
                    }

                    if (type == ArchiveType.ZIP) {
                        processZip(context, inputUri, profile, isEpub, zos, epubPages,
                                baseName, currentFileIndex, totalFiles, callback);
                    } else {
                        processRar(context, inputUri, profile, isEpub, zos, epubPages,
                                baseName, currentFileIndex, totalFiles, callback);
                    }

                    if (isEpub) {
                        callback.onProgress("Packaging EPUB...\n" + baseName);
                        EpubPackager.buildEpub(outputFile, baseName, epubPages, profile.width, profile.height);
                    } else {
                        zos.close();
                    }

                    currentFileIndex++;
                }

                callback.onSuccess("Done!\n" + totalFiles + " manga files saved to Downloads.");

            } catch (Exception e) {
                callback.onError(e);
            }
        }).start();
    }

    // --- Detección de formato por bytes (independiente de la extensión del archivo) ---

    private static ArchiveType detectArchiveType(Context context, Uri uri) throws IOException {
        byte[] header = new byte[8];
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return ArchiveType.UNKNOWN;
            int read = is.read(header);
            if (read < 4) return ArchiveType.UNKNOWN;
        }
        // ZIP / CBZ: firma "PK"
        if ((header[0] & 0xFF) == 0x50 && (header[1] & 0xFF) == 0x4B) {
            return ArchiveType.ZIP;
        }
        // RAR / CBR: firma "Rar!" (cubre RAR4 y RAR5)
        if ((header[0] & 0xFF) == 0x52 && (header[1] & 0xFF) == 0x61 &&
            (header[2] & 0xFF) == 0x72 && (header[3] & 0xFF) == 0x21) {
            return ArchiveType.RAR;
        }
        return ArchiveType.UNKNOWN;
    }

    // --- Camino ZIP / CBZ: streaming directo, igual que antes ---

    private static void processZip(Context context, Uri inputUri, DeviceProfile profile, boolean isEpub,
                                    ZipOutputStream zos, List<byte[]> epubPages, String baseName,
                                    int currentFileIndex, int totalFiles, OptimizationCallback callback) throws IOException {
        InputStream is = context.getContentResolver().openInputStream(inputUri);
        ZipInputStream zis = new ZipInputStream(is);

        ZipEntry entry;
        int count = 0;

        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            String lowerName = name.toLowerCase();

            if (isImage(lowerName)) {
                count++;
                callback.onProgress("File " + currentFileIndex + " of " + totalFiles + "\n" +
                        "Optimizing page " + count + "...\n" + baseName);

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[1024];
                int read;
                while ((read = zis.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, read);
                }
                buffer.flush();

                processImageBytes(buffer.toByteArray(), name, profile, isEpub, zos, epubPages);

            } else if (!entry.isDirectory() && zos != null) {
                ZipEntry newEntry = new ZipEntry(name);
                zos.putNextEntry(newEntry);
                byte[] data = new byte[1024];
                int read;
                while ((read = zis.read(data, 0, data.length)) != -1) {
                    zos.write(data, 0, read);
                }
                zos.closeEntry();
            }
        }

        zis.close();
        is.close();
    }

    // --- Camino RAR / CBR: Junrar necesita acceso a un File real, no a un stream del content:// ---

    private static void processRar(Context context, Uri inputUri, DeviceProfile profile, boolean isEpub,
                                    ZipOutputStream zos, List<byte[]> epubPages, String baseName,
                                    int currentFileIndex, int totalFiles, OptimizationCallback callback) throws Exception {
        File cacheDir = context.getCacheDir();
        File tempRar = new File(cacheDir, "temp_input_" + System.currentTimeMillis() + ".rar");
        File extractDir = new File(cacheDir, "temp_extract_" + System.currentTimeMillis());
        extractDir.mkdirs();

        try {
            // Copiar el content:// a un archivo real, Junrar no soporta streams de contenido de Android
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
            collectFilesSorted(extractDir, files);

            int count = 0;
            for (File f : files) {
                String lowerName = f.getName().toLowerCase();
                String relativeName = extractDir.toURI().relativize(f.toURI()).getPath();

                if (isImage(lowerName)) {
                    count++;
                    callback.onProgress("File " + currentFileIndex + " of " + totalFiles + "\n" +
                            "Optimizing page " + count + "...\n" + baseName);

                    byte[] imageBytes = readAllBytes(f);
                    processImageBytes(imageBytes, relativeName, profile, isEpub, zos, epubPages);

                } else if (zos != null) {
                    ZipEntry newEntry = new ZipEntry(relativeName);
                    zos.putNextEntry(newEntry);
                    zos.write(readAllBytes(f));
                    zos.closeEntry();
                }
            }
        } finally {
            deleteRecursive(tempRar);
            deleteRecursive(extractDir);
        }
    }

    // --- Lógica compartida: decodificar, recortar/centrar/escala de grises, y escribir en el destino que corresponda ---

    private static void processImageBytes(byte[] imageBytes, String entryName, DeviceProfile profile,
                                           boolean isEpub, ZipOutputStream zos, List<byte[]> epubPages) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);

        options.inSampleSize = calculateInSampleSize(options, profile.width, profile.height);
        options.inJustDecodeBounds = false;

        Bitmap decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
        if (decodedBitmap == null) return;

        Bitmap finalBitmap = autoCropCenterAndGrayscale(decodedBitmap, profile.width, profile.height);

        if (isEpub) {
            ByteArrayOutputStream pageOut = new ByteArrayOutputStream();
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, pageOut);
            epubPages.add(pageOut.toByteArray());
        } else {
            ZipEntry newEntry = new ZipEntry(entryName);
            zos.putNextEntry(newEntry);
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, zos);
            zos.closeEntry();
        }

        finalBitmap.recycle();
        if (decodedBitmap != finalBitmap) {
            decodedBitmap.recycle();
        }
    }

    private static boolean isImage(String lowerName) {
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png");
    }

    private static void collectFilesSorted(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            if (f.isDirectory()) {
                collectFilesSorted(f, out);
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

    /**
     * Crops margins while ignoring isolated scanner lines/shadows, scales proportionally,
     * centers on a maxWidth x maxHeight canvas and converts to grayscale.
     */
            
        private static Bitmap autoCropCenterAndGrayscale(Bitmap original, int maxWidth, int maxHeight) {
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
    // Cuántas columnas/filas CONSECUTIVAS con tinta se necesitan para confiar en que es
    // contenido real y no ruido aislado del escaneo (polvo, compresión JPEG, sombras).
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
                    if (consecutive >= requiredRun) {
                        isArtifact = false;
                        break;
                    }
                } else {
                    consecutive = 0;
                }
            }
            if (isArtifact) {
                left = left + gapSizeX;
            } else {
                break;
            }
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
                    if (consecutive >= requiredRun) {
                        isArtifact = false;
                        break;
                    }
                } else {
                    consecutive = 0;
                }
            }
            if (isArtifact) {
                right = right - gapSizeX;
            } else {
                break;
            }
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
                    if (consecutive >= requiredRun) {
                        isArtifact = false;
                        break;
                    }
                } else {
                    consecutive = 0;
                }
            }
            if (isArtifact) {
                top = top + gapSizeY;
            } else {
                break;
            }
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
                    if (consecutive >= requiredRun) {
                        isArtifact = false;
                        break;
                    }
                } else {
                    consecutive = 0;
                }
            }
            if (isArtifact) {
                bottom = bottom - gapSizeY;
            } else {
                break;
            }
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

    Bitmap finalBitmap = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(finalBitmap);
    canvas.drawColor(Color.WHITE);

    float leftOffset = (maxWidth - newWidth) / 2f;
    float topOffset = (maxHeight - newHeight) / 2f;

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
