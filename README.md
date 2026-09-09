# Kobo Manga Optimizer

An Android app that optimizes manga archives (CBZ, ZIP, CBR, RAR, EPUP) for e-ink readers - centering the images and reducing the size of the files without losing perceptible quality. 

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/b81fdf9e-55d5-4927-86ae-1b707e1a9734" alt="Preview 0" width="100%"></td>
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
  - **PDF or Epub** for Kindle devices
- **Multiple input formats**: CBZ, ZIP, CBR, RAR and Epub are all supported — format is detected from the file's actual contents, not its extension.
- **Border color selection**: select white or black margins for your manga
- **Landscape mode**: Rotate your horizontal images automatically.
- **Improve Colored Mangas**: Automatically applies different settings to improve the quality of colored images in your device.
- **Runs as a foreground service**: processing continues reliably in the background with a persistent notification showing progress.
- **Batch processing**: select and process multiple manga files in one go. 

## Supported devices

| Device | Resolution |
|---|---|
| Kobo Clara BW | 1072 x 1448 |
| Kobo Libra 2 / Libra Colour | 1264 x 1680 |
| Kobo Sage | 1440 x 1920 |
| Kobo Elipsa 2E | 1404 x 1872 |
| Kindle Basic (10th/11th gen) | 1072 x 1448 |
| Kindle Paperwhite | 1236 x 1648 |
| Kindle Oasis | 1264 x 1680 |
| Kindle Scribe | 1860 x 2480 |

CUSTOM RESOLUTION now supported. 

## How it works

1. Pick one or more `.cbz` / `.zip` / `.cbr` / `.rar` / `.epub` files.
2. Choose your target device and output format (CBZ is recommended)
3. The app auto-crops, scales, centers, and grayscales every page in the background.
4. Optimized files are saved to your **Downloads** folder as `<original_name>_fixed.cbz` or `.epub`.




