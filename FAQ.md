# Scrubgnome-Face: FAQ

*Twig's answers to the usual questions. Photo edition (Android app + iPhone web app).*

## The basics

**What does it do?**
It strips the hidden data out of photos before you share them: GPS location, date, time and time zone, phone make, model and lens, serial numbers, owner names, hidden preview thumbnails, edit history, motion-photo video clips and captions. The picture itself isn't touched. Nothing is re-encoded.

**Does it upload my photos?**
No. The Android app has no internet permission, and the iPhone web page is locked so it can't send data anywhere. Everything happens on your phone.

**Which formats?**
JPEG, PNG, WebP, GIF, HEIC/HEIF (iPhone) and AVIF. RAW/DNG and TIFF need desktop Scrubgnome.

**Can I do a batch?**
Yes. Pick as many as you like and tap **Scrub all**.

**Why do my photos still look right side up and the same colour?**
On purpose. Twig keeps the orientation tag and the colour profile; everything else hidden goes.

**What's this about a hidden thumbnail?**
Many photos carry a small preview image inside them. If you cropped the photo, that preview can still show the **uncropped original**. Twig removes it.

## iPhone

**How do I install it?**
Open **kultrshok.github.io/scrubgnome-face** in Safari, then tap **Share → Add to Home Screen**. It works offline after the first visit.

**Photo Library or Browse?**
**Browse** (Files) hands over the untouched original. The Photo Library may give a converted copy, which is still fine to scrub.

**How do I save the clean photos?**
**Save / share → Save Image** puts them back in Photos.

**The icon didn't change after an update.**
iOS doesn't refresh home-screen icons. Delete the home-screen app and add it again from Safari.

## Android

**How do I install it?**
Download the APK from **Releases** (or **Actions → latest Build APK → Artifacts**), open it, and allow installs once. Needs Android 10 or newer. Clean copies go to `Pictures/Scrubgnome/`.

**Why does it ask for "access to photo locations"?**
Android hides GPS from apps unless you allow this. Allowing it lets Twig *show* you where the photo says it was taken. Say no and he still scrubs the GPS; he just can't show you the coordinates first.

## Limits

**Is my photo now anonymous?**
Only the hidden data is gone. Faces, street signs, house numbers, reflections and anything else *in the picture* are still there. Face blurring is a maybe-someday idea.

**Twig refused to save a photo.**
After scrubbing, Twig re-scans every clean copy and won't save one if anything survived. Try desktop Scrubgnome, and if it's reproducible, file a bug (see the Bug Fixes doc).

---
Author: kultrshok · MIT License · Generated with AI
