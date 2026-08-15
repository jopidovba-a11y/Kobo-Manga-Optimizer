# Kobo Manga Optimizer

An Android app that optimizes manga archives (CBZ, ZIP, CBR, RAR) for e-ink readers - centering the images and reducing the size of the files without losing perceptible quality. IMPORTANT: I have only tested the results in my kobo clara bw, other devices could show bad results.

   <table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/7ca6f884-78b9-4466-90e3-58a81f140c54" alt="Preview 1" width="100%"></td>
    <td><img src="https://github.com/user-attachments/assets/630848e8-1566-40e4-97ee-bef09e259894" alt="Preview 2" width="100%"></td>
    <td><img src="https://github.com/user-attachments/assets/9c6d63f9-7c4d-45b5-b8a3-6f1d850c0784" alt="Preview 3" width="100%"></td>
  </tr>
</table>

## Features

- **Smart auto-crop**: detects and removes scan margins/shadows while ignoring isolated noise lines from the scanner, without cutting into the actual artwork.
- **Device-aware scaling**: images are resized and centered to match your target device's exact screen resolution — no more distorted or letterboxed pages.
- **Grayscale conversion**: since e-ink displays are grayscale, converting ahead of time keeps file sizes down and rendering fast.
- **Multiple output formats**:
  - **CBZ** for Kobo devices (read natively by Nickel).
  - **Fixed-layout EPUB3** for Kindle devices (pages render edge-to-edge, no reflow margins).
- **Multiple input formats**: CBZ, ZIP, CBR, and RAR are all supported — format is detected from the file's actual contents, not its extension.
- **Batch processing**: select and process multiple manga files in one go.
- **Runs as a foreground service**: processing continues reliably in the background with a persistent notification showing progress.

## Supported devices

| Device | Resolution |
|---|---|
| Kobo Clara BW | 1072 x 1448 |
| Kobo Clara HD / Clara 2E | 1072 x 1448 |
| Kobo Libra 2 / Libra Colour | 1264 x 1680 |
| Kobo Sage | 1440 x 1920 |
| Kobo Elipsa 2E | 1404 x 1872 |
| Kindle Basic (10th/11th gen) | 1072 x 1448 |
| Kindle Paperwhite | 1236 x 1648 |
| Kindle Oasis | 1264 x 1680 |
| Kindle Scribe | 1860 x 2480 |

Resolutions were sourced from published device specs. If your device renders incorrectly, please open an issue with the model and its actual screen resolution — this is easy to fix.

## How it works

1. Pick one or more `.cbz` / `.zip` / `.cbr` / `.rar` files.
2. Choose your target device and output format (CBZ is recommended)
3. The app auto-crops, scales, centers, and grayscales every page in the background.
4. Optimized files are saved to your **Downloads** folder as `<original_name>_fixed.cbz` or `.epub`.

## A note on Kindle + EPUB

Kindle devices do not read `.epub` files directly over USB — Amazon requires converting through **Send to Kindle** (email or the desktop/mobile app), which handles the format conversion automatically.

## Known limitations

- Very large batches (many files selected at once) may hit Android's `Intent` extras size limit; a handful of manga volumes at a time works fine.
- Some newer RAR5 archives with solid compression or encrypted headers may fail to extract.
- Device resolutions are as accurate as publicly available specs — see the table above.

## Contributing

Issues and pull requests are welcome, especially for additional device profiles or resolution corrections.
