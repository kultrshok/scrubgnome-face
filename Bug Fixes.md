# Scrubgnome-Face: Bug Fixes

*What got fixed, and anything still known to bite.*

## v1.0 (2026-11-14)

**New: app icon.** Twig in his shades. On iPhone, re-add the app to the home screen to see it.

**Fixed: release builds** now start for version tags written as `V1.0` as well as `v1.0` (phone keyboards capitalise the first letter).

**Fixed: offline cache** bumped so installed iPhone apps pick up the new files.

## Known issues

- **iPhone home-screen icon doesn't update** after an app update. That's iOS; re-add the app from Safari.
- **Photo Library picks on iPhone** may hand over a converted copy (for example HEIC to JPEG). Use **Browse** for the original.
- **No partial copy on failure:** if something survives the scrub, Face skips that photo rather than saving it. (The video app now keeps a recheck copy; Face may get the same.)
- **RAW/DNG and TIFF** aren't supported on the phone. Use desktop Scrubgnome.

## Reporting a bug

Open an issue on the GitHub repo with the phone model, iOS or Android version, the photo format and the exact message Twig showed. **Don't attach the photo**: it contains the data you're trying to hide.

---
Author: kultrshok · MIT License · Generated with AI
