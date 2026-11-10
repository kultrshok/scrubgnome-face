#!/usr/bin/env python3
"""Make test photos stuffed with fake private metadata (GPS, serials, owner, XMP,
IPTC, comments, thumbnail, motion-photo trailer). Needs: pillow, pillow-heif.
Usage: python3 tools/make_samples.py OUTDIR"""
import io, os, struct, sys
from PIL import Image, PngImagePlugin
import pillow_heif
pillow_heif.register_heif_opener()

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
img = Image.new("RGB", (320, 200), (200, 120, 40))
for x in range(200):
    img.putpixel((x, x // 2), (0, 0, 255))

ex = Image.Exif()
ex[0x010F] = "Google"; ex[0x0110] = "Pixel 8 Pro"; ex[0x0131] = "HDR+ 1.0"
ex[0x0132] = "2026:11:07 18:35:22"; ex[0x013B] = "Test Owner"; ex[0x0112] = 6
sub = ex.get_ifd(0x8769)
sub[0x9003] = "2026:11:07 18:35:22"; sub[0x9011] = "-07:00"; sub[0xA431] = "SN12345"
sub[0x927C] = b"GOOGLE" + b"\x00" * 50; sub[0x829A] = 1 / 120
gps = ex.get_ifd(0x8825)
gps[1] = "N"; gps[2] = (33.0, 26.0, 54.24); gps[3] = "W"; gps[4] = (112.0, 4.0, 26.64)
gps[6] = 331.0; gps[29] = "2026:11:08"
eb = ex.tobytes()

XMP = ('<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
       '<rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" '
       'xmp:CreateDate="2026-11-07T18:35:22" xmp:CreatorTool="Pixel 8 Pro" GCamera:MotionPhoto="1">'
       '<dc:creator xmlns:dc="http://purl.org/dc/elements/1.1/"><rdf:Seq><rdf:li>Test Owner</rdf:li></rdf:Seq></dc:creator>'
       '</rdf:Description></rdf:RDF></x:xmpmeta>')

# JPEG: EXIF (with thumbnail via Pillow? add manually below) + XMP + IPTC + comment + motion-photo trailer
b = io.BytesIO(); img.save(b, "JPEG", exif=eb, quality=90, comment=b"shot at home"); j = b.getvalue()
app1 = b"http://ns.adobe.com/xap/1.0/\x00" + XMP.encode()
iptc = (b"Photoshop 3.0\x008BIM\x04\x04\x00\x00" + struct.pack(">I", 32) +
        b"\x1c\x02\x50\x00\x0aTest Owner\x1c\x02\x5a\x00\x07Phoenix\x1c\x02\x65\x00\x03USA")
j = j[:2] + b"\xff\xe1" + struct.pack(">H", len(app1) + 2) + app1 + b"\xff\xed" + struct.pack(">H", len(iptc) + 2) + iptc + j[2:]
j += b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 200
open(os.path.join(out, "PXL_20261107_183522.jpg"), "wb").write(j)

pi = PngImagePlugin.PngInfo(); pi.add_text("Author", "Test Owner"); pi.add_text("Software", "GIMP 2.10")
pi.add_itxt("XML:com.adobe.xmp", XMP); pi.add_text("Creation Time", "2026-11-07")
img.save(os.path.join(out, "Screenshot_2026-11-10-12-33-45.png"), pnginfo=pi, exif=eb)
img.save(os.path.join(out, "pic.webp"), exif=eb, xmp=XMP.encode(), lossless=True)
img.convert("P").save(os.path.join(out, "anim.gif"), comment=b"made by Test Owner", save_all=True,
                      append_images=[img.convert("P").rotate(10)], loop=0)
img.save(os.path.join(out, "IMG_0042.heic"), exif=eb, xmp=XMP.encode(), quality=90)
try:
    img.save(os.path.join(out, "photo.avif"), exif=eb, xmp=XMP.encode(), quality=90)
except Exception as e:
    print("  (no AVIF sample:", e, ")")
print("samples in", out)
