package com.example.koboconverter;

public enum DeviceProfile {

    KOBO_CLARA_BW("Kobo Clara BW", 1072, 1448, OutputFormat.CBZ),
    KOBO_CLARA_HD("Kobo Clara HD / Clara 2E", 1072, 1448, OutputFormat.CBZ),
    KOBO_LIBRA_2("Kobo Libra 2 / Libra Colour", 1264, 1680, OutputFormat.CBZ),
    KOBO_SAGE("Kobo Sage", 1440, 1920, OutputFormat.CBZ),
    KOBO_ELIPSA("Kobo Elipsa 2E", 1404, 1872, OutputFormat.CBZ),
    KINDLE_BASIC("Kindle Basic (10a-11a gen)", 1072, 1448, OutputFormat.EPUB),
    KINDLE_PAPERWHITE("Kindle Paperwhite", 1236, 1648, OutputFormat.EPUB),
    KINDLE_OASIS("Kindle Oasis", 1264, 1680, OutputFormat.EPUB),
    KINDLE_SCRIBE("Kindle Scribe", 1860, 2480, OutputFormat.EPUB);

    public enum OutputFormat { CBZ, EPUB }

    public final String displayName;
    public final int width;
    public final int height;
    public final OutputFormat defaultFormat;

    DeviceProfile(String displayName, int width, int height, OutputFormat defaultFormat) {
        this.displayName = displayName;
        this.width = width;
        this.height = height;
        this.defaultFormat = defaultFormat;
    }

    @Override
    public String toString() {
        return displayName;
    }
}