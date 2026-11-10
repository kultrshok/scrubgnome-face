# Scrubgnome-Face

*Twig's photo edition. Strip the snitchy metadata out of your photos before you post them.*

> "Great gears! That photo is blabbing where you stood, when, and with what. Hand it over." — Twig

Scrubgnome-Face is the photo member of the Scrubgnome family, alongside desktop
[Scrubgnome](https://github.com/kultrshok/scrubgnome) and the video app
[Scrubgnome Away from Home](https://github.com/kultrshok/scrubgnome-afh). It does one job well:

1. **Pick** photos (or *Share* them to "Scrub with Twig" from your gallery). One or a whole batch.
2. **See** what each one is leaking, in plain words: where, when, what phone, who, and hidden extras.
3. **Scrub & save** clean copies to `Pictures/Scrubgnome/` with random filenames.
4. **Share / upload** the clean copies.

**Two flavours, same brain:**
- **Android app**: install the APK.
- **Web version for iPhone / any browser** (`web/`): open the page in Safari, tap *Share → Add to Home Screen*,
  and it works like an app, even offline. It reads your photos locally and never uploads them; the page's
  security policy forbids it from sending data anywhere.

**Privacy promise:** the Android app has **no internet permission at all**. Everything happens on your phone.

---

## What gets removed

| Leak | Where it hides | What Twig does |
|---|---|---|
| GPS location | EXIF GPS block, XMP, IPTC city/country | removed |
| Date & time (and your time zone) | EXIF DateTime*, OffsetTime*, GPS date, XMP, PNG tIME | removed |
| Phone make, model, lens, software | EXIF, XMP, MakerNote vendor blob | removed |
| Who you are | Artist, owner name, camera/lens **serial numbers**, copyright, IPTC by-line | removed |
| Hidden preview thumbnail | EXIF IFD1 / JFXX (can show the **uncropped** original!) | removed |
| Edit history | XMP history (every edit, with app + timestamp) | removed |
| Motion-photo video | MP4 appended after the JPEG by Pixel/Samsung "motion photos" | cut off |
| HDR gain maps, extra images, C2PA records | MPF / APP11 / trailing data | removed |
| Comments & captions | JPEG/GIF comments, PNG text, IPTC captions | removed |
| The filename | `PXL_20261107_183522.jpg` *is* a timestamp | clean copy gets `photo_<random>.jpg` |

**Kept:** the orientation tag, so photos don't turn sideways, plus the colour profile, so colours don't shift.

**Lossless, every format:** JPEG, PNG, WebP and GIF are rebuilt around their untouched compressed
picture data. HEIC/HEIF (iPhone) and AVIF are cleaned **in place**: the metadata items are overwritten
with an empty stand-in of the same size, so every pointer in the file stays valid. Not one pixel is
re-encoded. After scrubbing, Twig re-scans the result and refuses to save it if anything survived.

Supported: JPEG, PNG, WebP, GIF, HEIC/HEIF, AVIF. Not supported: RAW/DNG, TIFF (use desktop Scrubgnome).

**What it can't do:** it removes *hidden* information only. A street sign, a face, a reflection or your
house number in the picture itself is still there. (Face blurring is a maybe-someday idea.)

## Install (no app store needed)

1. Open the repo's **Actions** tab → newest green **Build APK** run → download **ScrubgnomeFace-apk** (a zip holding the APK).
   Tagged versions also show up under **Releases**.
2. Copy the APK to your phone and tap it. Android will ask you to allow installs from that app (Files/Chrome). Allow it once.
3. Requires **Android 10 or newer**.
4. On first use Twig asks for **access to photo locations**. That lets him *show* you the real GPS
   (Android hides it from apps otherwise). Saying no is fine: he scrubs the GPS either way, he just can't
   show you the coordinates.

## iPhone / web version

1. Open the web app's address in Safari (published from this repo via GitHub Pages once the repo is public;
   see `.github/workflows/pages.yml`).
2. Tap **Share → Add to Home Screen**. Twig gets his own icon and works offline after the first visit.
3. **Pick photos** → choose **Browse** to pick from Files (that hands over the untouched original). The
   Photo Library option may give Twig a converted copy, which is still fine to scrub.
4. **Scrub all** → **Save / share** → **Save Image** puts the clean copies in Photos.

The JavaScript scrubber produces byte-for-byte the same output as the Android (Java) one, and both match
desktop Scrubgnome's Photos tab for JPEG, PNG, WebP and GIF.

## Build it yourself

**Easiest:** push to GitHub and let Actions build it (see `.github/workflows/build-apk.yml`).
Release signing uses the same four repo secrets as Away from Home (`SG_KEYSTORE_B64`, `SG_KEYSTORE_PASS`,
`SG_KEY_ALIAS`, `SG_KEY_PASS`). Without them you get a debug APK.

**Android Studio:** *File → Open* this folder, wait for Gradle sync, press ▶ with your phone plugged in
(USB debugging on).

**Desktop self-tests** (Java 17+, Node 22+, Python 3 with `pillow` + `pillow-heif`, and `exiftool`):
```
bash tools/selftest.sh        # Java core: scrub generated photos, verify with exiftool + pixel compare
bash tools/web-selftest.sh    # also: JS must match Java byte-for-byte
```

## Project layout

```
app/src/main/java/com/kultrshok/scrubgnome/face/
├── core/ImageScrubber.java   ← the scrubber (pure Java, no Android; reusable module)
└── MainActivity.java         ← the one-screen UI (Twig's voice lives here)
web/
├── index.html                ← the web/iPhone app (one screen)
├── js/face.js                ← JS twin of ImageScrubber.java
├── js/app.js                 ← UI glue, Twig's voice
└── sw.js, manifest.webmanifest  ← offline + Add to Home Screen
tools/
├── make_samples.py           ← generates leaky test photos
├── SelfTest.java, web-selftest.mjs, verify.py, *.sh
```
No third-party libraries in the app. `core/ImageScrubber.java` and `web/js/face.js` drop straight into
other projects.

## License

MIT © kultrshok. See [LICENSE](LICENSE).

<sub>Generated with AI</sub>
