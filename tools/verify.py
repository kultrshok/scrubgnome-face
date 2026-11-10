#!/usr/bin/env python3
"""Independent check of scrubbed photos: pixels identical, and exiftool finds
nothing private. Usage: python3 tools/verify.py ORIG_DIR CLEAN_DIR"""
import os, subprocess, sys
from PIL import Image, ImageChops
import pillow_heif
pillow_heif.register_heif_opener()
orig, clean = sys.argv[1], sys.argv[2]
BAD = ("gps", "make", "model", "software", "serial", "artist", "owner", "creator", "author",
       "datetime", "createdate", "date/time", "offsettime", "makernote", "comment", "copyright",
       "city", "country", "by-line", "motionphoto", "thumbnail", "xmp:")
ok = True
for n in sorted(os.listdir(clean)):
    a, b = Image.open(os.path.join(orig, n)), Image.open(os.path.join(clean, n))
    frames = getattr(a, "n_frames", 1)
    same = a.size == b.size and frames == getattr(b, "n_frames", 1)
    for k in range(frames if same else 0):
        a.seek(k); b.seek(k)
        if ImageChops.difference(a.convert("RGBA"), b.convert("RGBA")).getbbox() is not None:
            same = False
    tags = subprocess.run(["exiftool", "-a", "-G1", "-s", os.path.join(clean, n)], capture_output=True, text=True).stdout
    left = [l for l in tags.splitlines() if not l.startswith(("[ExifTool]", "[System]", "[File]"))
            and any(w in l.lower() for w in BAD) and "Orientation" not in l]
    o = Image.open(os.path.join(orig, n)).getexif().get(274); c = b.getexif().get(274)
    print(f"{n:40} pixels identical: {same}   orientation {o} -> {c}   leftover tags: {left or 'none'}")
    ok &= same and not left and (o or 1) == (c or 1)
print("VERIFY PASSED" if ok else "VERIFY FAILED"); sys.exit(0 if ok else 1)
