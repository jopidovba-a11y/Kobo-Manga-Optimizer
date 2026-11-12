package com.example.koboconverter;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

public class GrayscalePngEncoder {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    public static void encode(Bitmap bitmap, OutputStream out) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        byte[] gray = new byte[width * height];
        for (int i = 0; i < pixels.length; i++) {
            gray[i] = (byte) (pixels[i] & 0xFF);
        }

        byte[] filtered = filterScanlines(gray, width, height);
        byte[] compressed = deflate(filtered);

        out.write(SIGNATURE);
        writeChunk(out, "IHDR", buildIHDR(width, height));
        writeChunk(out, "IDAT", compressed);
        writeChunk(out, "IEND", new byte[0]);
    }

    private static byte[] buildIHDR(int width, int height) {
        byte[] data = new byte[13];
        writeInt(data, 0, width);
        writeInt(data, 4, height);
        data[8] = 8;  // bit depth
        data[9] = 0;  // color type: grayscale
        data[10] = 0; // compression method
        data[11] = 0; // filter method
        data[12] = 0; // interlace: none
        return data;
    }
 
    private static byte[] filterScanlines(byte[] gray, int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream((width + 1) * height);
        byte[] prevRow = new byte[width];
        byte[] currRow = new byte[width];
        byte[] paeth = new byte[width];

        for (int y = 0; y < height; y++) {
            System.arraycopy(gray, y * width, currRow, 0, width);

            for (int x = 0; x < width; x++) {
                int a = x > 0 ? (currRow[x - 1] & 0xFF) : 0;
                int b = prevRow[x] & 0xFF;
                int c = x > 0 ? (prevRow[x - 1] & 0xFF) : 0;
                int cur = currRow[x] & 0xFF;

                paeth[x] = (byte) (cur - paethPredictor(a, b, c));
            }

            out.write(4); // 4 = tipo de filtro Paeth, según la especificación de PNG
            out.write(paeth, 0, width);

            byte[] temp = prevRow;
            prevRow = currRow;
            currRow = temp;
        }

        return out.toByteArray();
    }

    private static int paethPredictor(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    private static byte[] deflate(byte[] data) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    try (DeflaterOutputStream dos = new DeflaterOutputStream(out, deflater)) {
        dos.write(data);
    } finally {
        deflater.end();
    }
    return out.toByteArray();
}

    private static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes("ASCII");
        byte[] lengthBytes = new byte[4];
        writeInt(lengthBytes, 0, data.length);
        out.write(lengthBytes);
        out.write(typeBytes);
        out.write(data);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        writeInt(crcBytes, 0, (int) crc.getValue());
        out.write(crcBytes);
    }

    private static void writeInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) ((value >> 24) & 0xFF);
        buffer[offset + 1] = (byte) ((value >> 16) & 0xFF);
        buffer[offset + 2] = (byte) ((value >> 8) & 0xFF);
        buffer[offset + 3] = (byte) (value & 0xFF);
    }
}