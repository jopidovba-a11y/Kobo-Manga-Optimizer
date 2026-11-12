package com.example.koboconverter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class PdfPackager {

    public static void buildPdf(OutputStream out, List<byte[]> pageImages, int pageWidth, int pageHeight) throws IOException {
        PdfDocument document = new PdfDocument();
        try {
            for (int i = 0; i < pageImages.size(); i++) {
                PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create();
                PdfDocument.Page page = document.startPage(pageInfo);

                byte[] jpegBytes = pageImages.get(i);
                Bitmap pageBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
                if (pageBitmap != null) {
                    page.getCanvas().drawBitmap(pageBitmap, 0, 0, null);
                    pageBitmap.recycle();
                }

                document.finishPage(page);
            }
            document.writeTo(out);
        } finally {
            document.close();
        }
    }
}