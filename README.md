# 🌐 PhotoSphere Downloader

Download Google Maps and Google Earth photosphere (360° panorama) images directly to your Android phone. The app automatically injects the correct **GPano XMP** and **GPS EXIF** metadata so images are recognised as photospheres by Google Photos, the Android Gallery, and any other panorama viewer.

---

## ✨ Features

| Feature | Details |
|---|---|
| **Share from Google Maps** | Tap Share in Maps → choose this app |
| **Share from Google Earth** | Same one-tap sharing flow |
| **Paste URL** | Copy any Maps/Earth link and paste it |
| **3 Quality Levels** | 2048×1024 / 4096×2048 / 8192×4096 px |
| **GPano XMP injection** | Image opens as 360° pano in Google Photos |
| **GPS EXIF injection** | Location embedded from the URL coordinates |
| **Saved to Gallery** | `Pictures/PhotoSpheres/` folder |

---

## 📲 Getting the APK

### Option A — Download from GitHub Actions (no build needed)

1. Go to your repository on GitHub
2. Click **Actions** tab
3. Click the latest **Build Debug APK** workflow run
4. Scroll down to **Artifacts** and download `PhotoSphereDownloader-debug`
5. Unzip and install the `.apk` on your device

> **Enable "Install from unknown sources"** in Android Settings → Security first.

### Option B — Build locally

```bash
git clone https://github.com/YOUR_USERNAME/PhotoSphereDownloader
cd PhotoSphereDownloader
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## 🚀 Uploading to GitHub

1. Create a new repository on GitHub (e.g. `PhotoSphereDownloader`)
2. Run:
   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git remote add origin https://github.com/YOUR_USERNAME/PhotoSphereDownloader.git
   git push -u origin main
   ```
3. Go to the **Actions** tab — the build starts automatically
4. After ~3 minutes, download the APK from the Artifacts section

---

## 📖 How to Use

### From Google Maps
1. Open **Google Maps** on your phone
2. Navigate to a Street View / photo sphere location (blue dot or blue line)
3. Tap the image to open it full screen
4. Tap the **three-dot menu** → **Share**
5. Choose **PhotoSphere Downloader** from the share sheet
6. The download starts automatically!

### From Google Earth
1. Open **Google Earth** and find a photosphere (camera icon)
2. Tap it to open
3. Tap **Share** → **PhotoSphere Downloader**

### Manual URL
1. Copy any Google Maps Street View URL
2. Open **PhotoSphere Downloader**
3. Tap **Paste** or paste the URL
4. Tap **Download**

---

## 🏗️ Architecture

```
PhotoSphereDownloader/
├── .github/
│   └── workflows/
│       └── build.yml           ← GitHub Actions CI
├── app/src/main/java/.../
│   ├── MainActivity.kt         ← UI + Intent handling
│   ├── PanoDownloader.kt       ← Download pipeline orchestrator
│   ├── PanoIdExtractor.kt      ← URL parser (pano ID + GPS coords)
│   ├── TileStitcher.kt         ← Downloads + stitches tile grid
│   └── XmpInjector.kt          ← GPano XMP + GPS EXIF injection
└── ...
```

### Technology Choices

| Component | Choice | Why |
|---|---|---|
| Language | **Kotlin** | Official Android language, concise, coroutines |
| HTTP | **OkHttp 4** | Reliable, handles redirects, connection pooling |
| Concurrency | **Coroutines** | Structured, cancellable, Dispatchers.IO |
| Image | **Android Bitmap** | No extra deps needed for JPEG encode |
| EXIF | **AndroidX ExifInterface** | Official GPS EXIF writing |
| XMP | **Manual JPEG APP1** | No external dep needed, full control |
| Build | **Gradle KTS** | Type-safe, IDE-friendly |

---

## 🔬 Technical Details

### Tile Download
Google Street View tiles are fetched from:
```
https://streetviewpixels-pa.googleapis.com/v1/tile
    ?cb_client=maps_sv.tactile
    &panoid=<ID>
    &x=<col>&y=<row>&zoom=<level>
```

| Zoom | Grid | Output Resolution |
|------|------|-------------------|
| 2 | 4×2 tiles | 2048 × 1024 px |
| 3 | 8×4 tiles | 4096 × 2048 px ⭐ |
| 4 | 16×8 tiles | 8192 × 4096 px |

### XMP Photosphere Metadata
The app inserts a JPEG APP1 segment with these key GPano fields:
```xml
<GPano:ProjectionType>equirectangular</GPano:ProjectionType>
<GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer>
<GPano:FullPanoWidthPixels>4096</GPano:FullPanoWidthPixels>
<GPano:FullPanoHeightPixels>2048</GPano:FullPanoHeightPixels>
```

This is what causes Google Photos (and other apps) to open the image in 360° viewer mode.

---

## ⚠️ Notes

- Only **photospheres** (360° panoramas) work. Regular photos won't stitch correctly.
- Google may rate-limit tile downloads for very large panoramas.
- Zoom level 4 (8K) requires ~200 MB of RAM during stitching — some older devices may run out of memory.
- This app does **not** require any API key — it uses the same public tile endpoint that Google Maps uses in the browser.

---

## 📜 License
MIT License. See [LICENSE](LICENSE) file.
