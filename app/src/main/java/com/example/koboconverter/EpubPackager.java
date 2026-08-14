package com.example.koboconverter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class EpubPackager {

    public static void buildEpub(File outputFile, String title, List<byte[]> pageImages,
                                  int pageWidth, int pageHeight) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile))) {

            // 1. mimetype: must be first and uncompressed
            byte[] mimeBytes = "application/epub+zip".getBytes("UTF-8");
            ZipEntry mimeEntry = new ZipEntry("mimetype");
            mimeEntry.setMethod(ZipEntry.STORED);
            CRC32 crc = new CRC32();
            crc.update(mimeBytes);
            mimeEntry.setSize(mimeBytes.length);
            mimeEntry.setCompressedSize(mimeBytes.length);
            mimeEntry.setCrc(crc.getValue());
            zos.putNextEntry(mimeEntry);
            zos.write(mimeBytes);
            zos.closeEntry();

            // 2. META-INF/container.xml
            writeEntry(zos, "META-INF/container.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles>\n" +
                    "</container>");

            StringBuilder manifest = new StringBuilder();
            StringBuilder spine = new StringBuilder();
            StringBuilder navItems = new StringBuilder();
            StringBuilder ncxNavPoints = new StringBuilder();

            for (int i = 0; i < pageImages.size(); i++) {
                int pageNum = i + 1;
                String imgName = String.format("images/page_%04d.jpg", pageNum);
                String pageName = String.format("page_%04d.xhtml", pageNum);

                writeEntryBytes(zos, "OEBPS/" + imgName, pageImages.get(i));

                // Fixed-layout page: no margins, image at exact screen size
                String pageXhtml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<!DOCTYPE html>\n" +
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
                        "<head><title>Page " + pageNum + "</title>" +
                        "<meta name=\"viewport\" content=\"width=" + pageWidth + ", height=" + pageHeight + "\"/>" +
                        "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#ffffff;}" +
                        "img{width:100%;height:100%;display:block;}</style></head>\n" +
                        "<body><img src=\"" + imgName + "\" alt=\"Page " + pageNum + "\"/></body>\n" +
                        "</html>";
                writeEntry(zos, "OEBPS/" + pageName, pageXhtml);

                manifest.append("<item id=\"img").append(pageNum).append("\" href=\"").append(imgName)
                        .append("\" media-type=\"image/jpeg\"/>\n");
                manifest.append("<item id=\"page").append(pageNum).append("\" href=\"").append(pageName)
                        .append("\" media-type=\"application/xhtml+xml\" properties=\"rendition:layout-pre-paginated\"/>\n");
                spine.append("<itemref idref=\"page").append(pageNum).append("\"/>\n");
                navItems.append("<li><a href=\"").append(pageName).append("\">Page ").append(pageNum).append("</a></li>\n");
                ncxNavPoints.append("<navPoint id=\"navpoint-").append(pageNum).append("\" playOrder=\"").append(pageNum).append("\">")
                        .append("<navLabel><text>Page ").append(pageNum).append("</text></navLabel>")
                        .append("<content src=\"").append(pageName).append("\"/></navPoint>\n");
            }

            // 3. nav.xhtml (required by EPUB3)
            String navXhtml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<!DOCTYPE html>\n" +
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n" +
                    "<head><title>Contents</title></head>\n" +
                    "<body><nav epub:type=\"toc\" id=\"toc\"><ol>\n" + navItems + "</ol></nav></body>\n" +
                    "</html>";
            writeEntry(zos, "OEBPS/nav.xhtml", navXhtml);

            // 4. content.opf with fixed-layout metadata (rendition:layout)
            String opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"BookId\">\n" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
                    "<dc:title>" + escapeXml(title) + "</dc:title>\n" +
                    "<dc:language>en</dc:language>\n" +
                    "<dc:identifier id=\"BookId\">urn:uuid:" + UUID.randomUUID() + "</dc:identifier>\n" +
                    "<meta property=\"dcterms:modified\">2026-01-01T00:00:00Z</meta>\n" +
                    "<meta property=\"rendition:layout\">pre-paginated</meta>\n" +
                    "<meta property=\"rendition:orientation\">portrait</meta>\n" +
                    "<meta property=\"rendition:spread\">none</meta>\n" +
                    "</metadata>\n" +
                    "<manifest>\n" +
                    "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n" +
                    "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n" +
                    manifest +
                    "</manifest>\n" +
                    "<spine toc=\"ncx\">\n" + spine + "</spine>\n" +
                    "</package>";
            writeEntry(zos, "OEBPS/content.opf", opf);

            // 5. toc.ncx (EPUB2 reader compatibility)
            String ncx = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n" +
                    "<head><meta name=\"dtb:uid\" content=\"urn:uuid:none\"/></head>\n" +
                    "<docTitle><text>" + escapeXml(title) + "</text></docTitle>\n" +
                    "<navMap>\n" + ncxNavPoints + "</navMap>\n" +
                    "</ncx>";
            writeEntry(zos, "OEBPS/toc.ncx", ncx);
        }
    }

    private static void writeEntry(ZipOutputStream zos, String name, String content) throws IOException {
        writeEntryBytes(zos, name, content.getBytes("UTF-8"));
    }

    private static void writeEntryBytes(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}