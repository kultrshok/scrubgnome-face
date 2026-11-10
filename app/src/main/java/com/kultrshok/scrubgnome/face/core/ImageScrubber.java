package com.kultrshok.scrubgnome.face.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Scrubgnome-Face core: finds and removes the hidden metadata in photos.
 *
 * Pure Java (no Android classes), so it drops into any Java/Kotlin project and
 * is unit-tested on a desktop JVM. A port of desktop Scrubgnome's hmd/face.py.
 *
 * JPEG, PNG, WebP and GIF are rewritten at the container level: the compressed
 * picture data is copied byte-for-byte, so not one pixel changes. HEIC/HEIF and
 * AVIF are cleaned IN PLACE: the Exif and XMP items are overwritten with an empty
 * stand-in of the same size, so every offset in the file stays valid and the
 * image itself is untouched. Orientation survives (a 1-tag EXIF for JPEG/PNG/WebP;
 * HEIF keeps it in its own irot/imir boxes).
 *
 * Author: kultrshok. MIT License.
 */
public final class ImageScrubber {

    private ImageScrubber() {}

    // ------------------------------------------------------------------ results

    /** One thing the photo was leaking. cat: location, time, identity, device, other. */
    public static final class Finding {
        public final String cat, where, key, value;
        Finding(String cat, String where, String key, String value) {
            this.cat = cat; this.where = where; this.key = key; this.value = value;
        }
        @Override public String toString() { return "[" + cat + "] " + where + ": " + key + " = " + value; }
    }

    public static final class Report {
        public final String format;
        public final List<Finding> findings;
        Report(String format, List<Finding> findings) { this.format = format; this.findings = findings; }

        /** Ordered, de-duplicated categories, worst first. */
        public Set<String> categories() {
            Set<String> s = new LinkedHashSet<>();
            for (String c : new String[]{"location", "time", "identity", "device", "other"})
                for (Finding f : findings) if (f.cat.equals(c)) { s.add(c); break; }
            return s;
        }

        /** Same 0-100 scale as desktop: location 50, time 35, identity 30, device 15 (capped). */
        public int score() {
            Set<String> c = categories();
            int s = (c.contains("location") ? 50 : 0) + (c.contains("time") ? 35 : 0)
                    + (c.contains("identity") ? 30 : 0) + (c.contains("device") ? 15 : 0);
            return Math.min(100, s);
        }

        public boolean isClean() { return findings.isEmpty(); }
    }

    public static final class Result {
        public final byte[] clean;
        public final Report before;
        Result(byte[] clean, Report before) { this.clean = clean; this.before = before; }
    }

    public static final class UnsupportedFormatException extends Exception {
        private static final long serialVersionUID = 1L;
        public UnsupportedFormatException(String m) { super(m); }
    }

    // ------------------------------------------------------------------ public API

    /** "jpeg", "png", "webp", "gif", "heic", "avif" or null. */
    public static String sniff(byte[] d) {
        if (d.length >= 3 && u8(d, 0) == 0xFF && u8(d, 1) == 0xD8 && u8(d, 2) == 0xFF) return "jpeg";
        if (d.length >= 8 && startsWith(d, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})) return "png";
        if (d.length >= 12 && ascii(d, 0, 4).equals("RIFF") && ascii(d, 8, 4).equals("WEBP")) return "webp";
        if (d.length >= 6 && (ascii(d, 0, 6).equals("GIF87a") || ascii(d, 0, 6).equals("GIF89a"))) return "gif";
        if (d.length >= 12 && ascii(d, 4, 4).equals("ftyp")) {
            String b = ascii(d, 8, 4);
            if (b.equals("avif") || b.equals("avis")) return "avif";
            if (b.equals("heic") || b.equals("heix") || b.equals("hevc") || b.equals("hevx") || b.equals("heim")
                    || b.equals("heis") || b.equals("mif1") || b.equals("msf1")) return "heic";
        }
        return null;
    }

    public static Report scan(byte[] d) throws UnsupportedFormatException {
        String fmt = sniff(d);
        if (fmt == null) throw new UnsupportedFormatException("That's not a photo format I know.");
        List<Finding> f;
        try {
            switch (fmt) {
                case "jpeg": f = jpegReport(d); break;
                case "png": f = pngReport(d); break;
                case "webp": f = webpReport(d); break;
                case "gif": f = gifReport(d); break;
                default: f = heifReport(d);
            }
        } catch (RuntimeException e) {
            throw new UnsupportedFormatException("This " + fmt.toUpperCase(Locale.US) + " looks damaged (" + e.getMessage() + ").");
        }
        return new Report(fmt, f);
    }

    public static Result scrub(byte[] d) throws UnsupportedFormatException {
        Report before = scan(d);
        byte[] out;
        try {
            switch (before.format) {
                case "jpeg": out = jpegStrip(d); break;
                case "png": out = pngStrip(d); break;
                case "webp": out = webpStrip(d); break;
                case "gif": out = gifStrip(d); break;
                default: out = heifStrip(d);
            }
        } catch (RuntimeException e) {
            throw new UnsupportedFormatException("Couldn't take this " + before.format + " apart safely (" + e.getMessage() + ").");
        }
        return new Result(out, before);
    }

    /** Matches dates like 20261107_183522 or 2026-11-10-12-33-45 in a filename. */
    private static final Pattern STAMP = Pattern.compile(
            "(?<!\\d)(19|20)\\d{2}[-_.]?[01]\\d[-_.]?[0-3]\\d(?:[ _T.-]*(?:at[ _])?[0-2]\\d[-_.:]?[0-5]\\d(?:[-_.:]?[0-5]\\d)?)?");

    public static String dateInName(String name) {
        if (name == null) return null;
        Matcher m = STAMP.matcher(name);
        return m.find() ? m.group() : null;
    }

    public static String extensionFor(String fmt) {
        switch (fmt) {
            case "jpeg": return ".jpg";
            case "heic": return ".heic";
            default: return "." + fmt;
        }
    }

    public static String mimeFor(String fmt) {
        return "image/" + fmt;
    }

    // ------------------------------------------------------------------ byte helpers

    static int u8(byte[] d, int i) { return d[i] & 0xFF; }
    static int be16(byte[] d, int i) { return (u8(d, i) << 8) | u8(d, i + 1); }
    static long be32(byte[] d, int i) { return ((long) u8(d, i) << 24) | ((long) u8(d, i + 1) << 16) | ((long) u8(d, i + 2) << 8) | u8(d, i + 3); }
    static int le16(byte[] d, int i) { return u8(d, i) | (u8(d, i + 1) << 8); }
    static long le32(byte[] d, int i) { return u8(d, i) | ((long) u8(d, i + 1) << 8) | ((long) u8(d, i + 2) << 16) | ((long) u8(d, i + 3) << 24); }
    static String ascii(byte[] d, int off, int len) {
        if (off < 0 || off + len > d.length) return "";
        return new String(d, off, len, StandardCharsets.ISO_8859_1);
    }
    static boolean startsWith(byte[] d, int off, byte[] p) {
        if (off + p.length > d.length) return false;
        for (int i = 0; i < p.length; i++) if (d[off + i] != p[i]) return false;
        return true;
    }
    static byte[] slice(byte[] d, int a, int b) {
        a = Math.max(0, Math.min(a, d.length)); b = Math.max(a, Math.min(b, d.length));
        byte[] r = new byte[b - a];
        System.arraycopy(d, a, r, 0, r.length);
        return r;
    }
    static String shorten(String v) {
        v = v.replace("\u0000", "").trim();
        return v.length() <= 90 ? v : v.substring(0, 89) + "…";
    }
    static boolean allZero(byte[] d, int a, int b) {
        for (int i = a; i < b && i < d.length; i++) if (d[i] != 0) return false;
        return true;
    }

    // ------------------------------------------------------------------ EXIF / TIFF

    private static final int[] TYPE_SIZE = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4};

    private static final Map<Integer, String> TIME = new HashMap<>(), DEVICE = new HashMap<>(),
            IDENTITY = new HashMap<>(), OTHER = new HashMap<>();
    static {
        TIME.put(0x0132, "DateTime"); TIME.put(0x9003, "DateTimeOriginal"); TIME.put(0x9004, "DateTimeDigitized");
        TIME.put(0x9010, "OffsetTime"); TIME.put(0x9011, "OffsetTimeOriginal"); TIME.put(0x9012, "OffsetTimeDigitized");
        TIME.put(0x9290, "SubSecTime"); TIME.put(0x9291, "SubSecTimeOriginal"); TIME.put(0x9292, "SubSecTimeDigitized");
        DEVICE.put(0x010F, "Make"); DEVICE.put(0x0110, "Model"); DEVICE.put(0x0131, "Software");
        DEVICE.put(0x013C, "HostComputer"); DEVICE.put(0xA433, "LensMake"); DEVICE.put(0xA434, "LensModel");
        DEVICE.put(0x927C, "MakerNote");
        IDENTITY.put(0x013B, "Artist"); IDENTITY.put(0x8298, "Copyright"); IDENTITY.put(0xA430, "CameraOwnerName");
        IDENTITY.put(0xA431, "BodySerialNumber"); IDENTITY.put(0xA435, "LensSerialNumber");
        IDENTITY.put(0xA420, "ImageUniqueID"); IDENTITY.put(0x9C9D, "XPAuthor");
        OTHER.put(0x010E, "ImageDescription"); OTHER.put(0x9286, "UserComment"); OTHER.put(0x9C9C, "XPComment");
        OTHER.put(0x9C9B, "XPTitle"); OTHER.put(0x9C9E, "XPKeywords"); OTHER.put(0x9C9F, "XPSubject");
    }

    /** One parsed IFD entry. value: String (ASCII), byte[] (BYTE/UNDEFINED), double[] or long[]. */
    static final class Entry {
        final String ifd; final int tag, type; final Object value;
        Entry(String ifd, int tag, int type, Object value) { this.ifd = ifd; this.tag = tag; this.type = type; this.value = value; }
    }

    /** Defensive TIFF walker: IFD0 -> IFD1, plus Exif and GPS sub-IFDs. Never throws on bad data. */
    static List<Entry> parseTiff(byte[] t) {
        List<Entry> out = new ArrayList<>();
        if (t.length < 8) return out;
        boolean le;
        if (t[0] == 'I' && t[1] == 'I') le = true;
        else if (t[0] == 'M' && t[1] == 'M') le = false;
        else return out;
        Set<Long> seen = new HashSet<>();
        try {
            long ifd0 = r32(t, 4, le);
            long next = walk(t, le, ifd0, "IFD0", out, seen, 0);
            if (next > 0) walk(t, le, next, "IFD1", out, seen, 0);
        } catch (RuntimeException ignored) {
            // truncated / hostile EXIF: keep what we have
        }
        return out;
    }

    private static int r16(byte[] t, int o, boolean le) { return le ? le16(t, o) : be16(t, o); }
    private static long r32(byte[] t, int o, boolean le) { return le ? le32(t, o) : be32(t, o); }

    private static long walk(byte[] t, boolean le, long off, String name, List<Entry> out, Set<Long> seen, int depth) {
        if (depth > 4 || off < 8 || off + 2 > t.length || !seen.add(off)) return 0;
        int o = (int) off;
        int n = r16(t, o, le);
        if (n > 1000 || o + 2 + 12L * n + 4 > t.length) return 0;
        for (int k = 0; k < n; k++) {
            int e = o + 2 + 12 * k;
            int tag = r16(t, e, le), type = r16(t, e + 2, le);
            long cnt = r32(t, e + 4, le);
            Object v = value(t, le, type, cnt, e + 8);
            out.add(new Entry(name, tag, type, v));
            if ((tag == 0x8769 || tag == 0x8825) && v instanceof long[] && ((long[]) v).length == 1) {
                walk(t, le, ((long[]) v)[0], tag == 0x8769 ? "Exif" : "GPS", out, seen, depth + 1);
            }
        }
        return r32(t, o + 2 + 12 * n, le);
    }

    private static Object value(byte[] t, boolean le, int type, long cnt, int rawOff) {
        int unit = type >= 1 && type < TYPE_SIZE.length ? TYPE_SIZE[type] : 1;
        long size = unit * cnt;
        if (cnt > 1_000_000) return null;
        long off = size <= 4 ? rawOff : r32(t, rawOff, le);
        if (off < 0 || off + size > t.length) return null;
        int a = (int) off;
        switch (type) {
            case 2: {
                int end = a;
                while (end < a + size && t[end] != 0) end++;
                return new String(t, a, end - a, StandardCharsets.ISO_8859_1);
            }
            case 3: case 8: {
                long[] r = new long[(int) cnt];
                for (int i = 0; i < cnt; i++) { int v = r16(t, a + 2 * i, le); r[i] = type == 8 ? (short) v : v; }
                return r;
            }
            case 4: case 9: case 13: {
                long[] r = new long[(int) cnt];
                for (int i = 0; i < cnt; i++) { long v = r32(t, a + 4 * i, le); r[i] = type == 9 ? (int) v : v; }
                return r;
            }
            case 5: case 10: {
                double[] r = new double[(int) cnt];
                for (int i = 0; i < cnt; i++) {
                    long n = r32(t, a + 8 * i, le), dd = r32(t, a + 8 * i + 4, le);
                    if (type == 10) { n = (int) n; dd = (int) dd; }
                    r[i] = dd == 0 ? 0 : (double) n / dd;
                }
                return r;
            }
            default:
                return slice(t, a, (int) (a + size));
        }
    }

    private static String show(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        if (v instanceof long[]) { long[] a = (long[]) v; return a.length == 1 ? Long.toString(a[0]) : java.util.Arrays.toString(a); }
        if (v instanceof double[]) {
            double[] a = (double[]) v;
            if (a.length == 1) return fmt(a[0]);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) sb.append(i > 0 ? ", " : "").append(fmt(a[i]));
            return sb.append(']').toString();
        }
        return ((byte[]) v).length + " bytes";
    }

    private static String fmt(double x) { return x == Math.rint(x) ? Long.toString((long) x) : String.format(Locale.US, "%.4f", x); }

    private static String xp(Object v) {
        if (!(v instanceof byte[])) return show(v);
        return new String((byte[]) v, StandardCharsets.UTF_16LE).replace("\u0000", "");
    }

    private static Double dms(Object v, Object ref) {
        if (!(v instanceof double[]) || ((double[]) v).length != 3) return null;
        double[] a = (double[]) v;
        double x = a[0] + a[1] / 60 + a[2] / 3600;
        String r = ref instanceof String ? (String) ref : "";
        return (r.equals("S") || r.equals("W")) ? -x : x;
    }

    static List<Finding> exifFindings(byte[] tiff, String where) {
        List<Finding> f = new ArrayList<>();
        Map<Integer, Object> gps = new HashMap<>();
        int settings = 0;
        for (Entry e : parseTiff(tiff)) {
            if (e.ifd.equals("GPS")) { gps.put(e.tag, e.value); continue; }
            if (e.ifd.equals("IFD1")) {
                if (e.tag == 0x0201) f.add(new Finding("other", where, "embedded thumbnail", "preview image - can still show the uncropped original"));
                continue;
            }
            int tag = e.tag;
            if (tag == 0x8769 || tag == 0x8825 || tag == 0xA005 || tag == 0x0112) continue;
            if (TIME.containsKey(tag)) f.add(new Finding("time", where, TIME.get(tag), shorten(show(e.value))));
            else if (DEVICE.containsKey(tag)) {
                String val = tag == 0x927C && e.value instanceof byte[]
                        ? "vendor data blob (" + ((byte[]) e.value).length + " bytes) - can hold serial numbers"
                        : shorten(show(e.value));
                f.add(new Finding("device", where, DEVICE.get(tag), val));
            } else if (IDENTITY.containsKey(tag)) {
                f.add(new Finding("identity", where, IDENTITY.get(tag), shorten(tag == 0x9C9D ? xp(e.value) : show(e.value))));
            } else if (OTHER.containsKey(tag)) {
                String val;
                if (tag >= 0x9C9B) val = xp(e.value);
                else if (e.value instanceof byte[]) {
                    byte[] b = (byte[]) e.value;
                    val = new String(b, tag == 0x9286 ? Math.min(8, b.length) : 0, tag == 0x9286 ? Math.max(0, b.length - 8) : b.length, StandardCharsets.ISO_8859_1);
                } else val = show(e.value);
                if (!val.replace("\u0000", "").trim().isEmpty()) f.add(new Finding("other", where, OTHER.get(tag), shorten(val)));
            } else if (e.ifd.equals("Exif")) settings++;
        }
        if (!gps.isEmpty()) {
            Double lat = dms(gps.get(2), gps.get(1)), lon = dms(gps.get(4), gps.get(3));
            if (lat != null && lon != null) {
                String pos;
                if (lat == 0 && lon == 0) pos = "present (coordinates zeroed or hidden by the phone)";
                else {
                    pos = String.format(Locale.US, "%.5f, %.5f", lat, lon);
                    Object alt = gps.get(6);
                    if (alt instanceof double[] && ((double[]) alt).length == 1)
                        pos += String.format(Locale.US, " (alt %.0f m)", ((double[]) alt)[0]);
                }
                f.add(new Finding("location", where, "GPS position", pos));
            } else if (gps.containsKey(1) || gps.containsKey(2) || gps.containsKey(3) || gps.containsKey(4)) {
                f.add(new Finding("location", where, "GPS position", "present"));
            }
            if (gps.containsKey(29) || gps.containsKey(7)) {
                String d = gps.containsKey(29) ? show(gps.get(29)) : "";
                String t = gps.containsKey(7) ? show(gps.get(7)) : "";
                f.add(new Finding("time", where, "GPS date/time", shorten((d + " " + t).trim())));
            }
            int extra = 0;
            for (int k : gps.keySet()) if (k > 7 && k != 29) extra++;
            if (extra > 0) f.add(new Finding("location", where, "GPS extras", extra + " more GPS fields (speed, direction, method...)"));
        }
        if (settings > 0) f.add(new Finding("other", where, "camera settings", settings + " technical tags (exposure, ISO, focal length...)"));
        return f;
    }

    static int exifOrientation(byte[] tiff) {
        for (Entry e : parseTiff(tiff))
            if (e.ifd.equals("IFD0") && e.tag == 0x0112 && e.value instanceof long[] && ((long[]) e.value).length == 1) {
                long v = ((long[]) e.value)[0];
                if (v >= 1 && v <= 8) return (int) v;
            }
        return 1;
    }

    /** A 1-tag big-endian TIFF block holding only Orientation. */
    static byte[] minimalExif(int orientation) {
        return new byte[]{'M', 'M', 0, 0x2A, 0, 0, 0, 8, 0, 1,
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
                0, 0, 0, 0};
    }

    // ------------------------------------------------------------------ XMP / IPTC

    private static final Pattern XMP_ATTR = Pattern.compile("\\b([A-Za-z][\\w.-]*):([A-Za-z][\\w.-]*)=\"([^\"]*)\"");
    private static final Pattern XMP_ELEM = Pattern.compile("<([A-Za-z][\\w.-]*):([A-Za-z][\\w.-]*)>([^<]+)</\\1:\\2>");
    private static final Pattern XMP_LIST = Pattern.compile("<([A-Za-z][\\w.-]*):([A-Za-z][\\w.-]*)>\\s*<rdf:(?:Seq|Bag|Alt)>(.*?)</rdf:", Pattern.DOTALL);
    private static final Pattern XMP_LI = Pattern.compile("<rdf:li[^>]*>([^<]*)</rdf:li>");
    private static final Set<String> XMP_SKIP = new HashSet<>(java.util.Arrays.asList("xmlns", "rdf", "x", "xml", "stEvt", "stRef"));

    static String xmpCat(String p, String n) {
        String k = (p + ":" + n).toLowerCase(Locale.US);
        if (k.matches(".*(gps|latitude|longitude|altitude|location|city|state|country|sublocation|province).*")) return "location";
        if (k.matches(".*(creator\\b|author|owner|by-?line|rights|copyright|serial|usage|credit|marked).*")) return "identity";
        if (k.matches(".*(date|time|when).*")) return "time";
        if (k.matches(".*(make|model|lens|creatortool|software|agent|camera).*")) return "device";
        return "other";
    }

    static List<Finding> xmpFindings(String x, String where) {
        List<Finding> f = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int hist = 0;
        Matcher h = Pattern.compile("stEvt:action").matcher(x);
        while (h.find()) hist++;
        for (Pattern rx : new Pattern[]{XMP_ATTR, XMP_ELEM}) {
            Matcher m = rx.matcher(x);
            while (m.find()) {
                String p = m.group(1), n = m.group(2), v = m.group(3).trim();
                if (XMP_SKIP.contains(p) || v.isEmpty() || !seen.add(p + ":" + n)) continue;
                if ((p.equals("GCamera") || p.equals("Camera")) && n.matches(".*(MotionPhoto|MicroVideo).*")) {
                    f.add(new Finding("other", where, p + ":" + n, "motion photo (hidden video attached)"));
                    continue;
                }
                f.add(new Finding(xmpCat(p, n), where, p + ":" + n, shorten(v)));
            }
        }
        Matcher m = XMP_LIST.matcher(x);
        while (m.find()) {
            String p = m.group(1), n = m.group(2);
            List<String> vals = new ArrayList<>();
            Matcher li = XMP_LI.matcher(m.group(3));
            while (li.find()) vals.add(li.group(1));
            if (!vals.isEmpty() && !XMP_SKIP.contains(p) && seen.add(p + ":" + n))
                f.add(new Finding(xmpCat(p, n), where, p + ":" + n, shorten(String.join("; ", vals))));
        }
        if (hist > 0) f.add(new Finding("time", where, "edit history", hist + " saved edit steps (with timestamps + app names)"));
        return f;
    }

    private static final Map<Integer, String[]> IPTC = new HashMap<>();
    static {
        Object[][] t = {{5, "other", "ObjectName"}, {25, "other", "Keywords"}, {55, "time", "DateCreated"},
                {60, "time", "TimeCreated"}, {62, "time", "DigitalCreationDate"}, {63, "time", "DigitalCreationTime"},
                {80, "identity", "By-line"}, {85, "identity", "By-lineTitle"}, {90, "location", "City"},
                {92, "location", "Sublocation"}, {95, "location", "Province-State"}, {100, "location", "CountryCode"},
                {101, "location", "Country"}, {110, "identity", "Credit"}, {115, "identity", "Source"},
                {116, "identity", "Copyright"}, {118, "identity", "Contact"}, {120, "other", "Caption"},
                {122, "identity", "Writer-Editor"}};
        for (Object[] r : t) IPTC.put((Integer) r[0], new String[]{(String) r[1], (String) r[2]});
    }

    static List<Finding> iptcFindings(byte[] p) {
        List<Finding> f = new ArrayList<>();
        int i = -1;
        for (int k = 0; k + 1 < p.length; k++) if (p[k] == 0x1C && p[k + 1] == 0x02) { i = k; break; }
        while (i >= 0 && i + 5 <= p.length && p[i] == 0x1C) {
            int rec = u8(p, i + 1), ds = u8(p, i + 2), ln = be16(p, i + 3);
            if (rec == 2 && IPTC.containsKey(ds)) {
                String[] c = IPTC.get(ds);
                int end = Math.min(p.length, i + 5 + ln);
                f.add(new Finding(c[0], "IPTC", c[1], shorten(new String(p, i + 5, end - i - 5, StandardCharsets.UTF_8))));
            }
            i += 5 + ln;
        }
        if (f.isEmpty() && new String(p, StandardCharsets.ISO_8859_1).contains("8BIM"))
            f.add(new Finding("other", "IPTC", "Photoshop resources", "editor data block"));
        return f;
    }

    // ------------------------------------------------------------------ JPEG

    static final class Seg {
        final int marker, start, end; final byte[] payload; final boolean trail;
        Seg(int marker, int start, int end, byte[] payload, boolean trail) {
            this.marker = marker; this.start = start; this.end = end; this.payload = payload; this.trail = trail;
        }
    }

    static List<Seg> jpegSegments(byte[] b) {
        if (b.length < 2 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) throw new IllegalArgumentException("not a JPEG");
        List<Seg> segs = new ArrayList<>();
        segs.add(new Seg(0xD8, 0, 2, new byte[0], false));
        int i = 2, n = b.length;
        while (i < n) {
            if (u8(b, i) != 0xFF) throw new IllegalArgumentException("corrupt JPEG at byte " + i);
            while (i < n && u8(b, i) == 0xFF) i++;
            if (i >= n) break;
            int m = u8(b, i), start = i - 1;
            i++;
            if (m == 0xD9) {
                segs.add(new Seg(0xD9, start, i, new byte[0], false));
                if (i < n) segs.add(new Seg(-1, i, n, slice(b, i, n), true));
                return segs;
            }
            if ((m >= 0xD0 && m <= 0xD7) || m == 0x01) { segs.add(new Seg(m, start, i, new byte[0], false)); continue; }
            if (i + 2 > n) throw new IllegalArgumentException("truncated JPEG");
            int ln = be16(b, i), end = i + ln;
            if (end > n) throw new IllegalArgumentException("truncated JPEG segment");
            byte[] payload = slice(b, i + 2, end);
            if (m == 0xDA) {
                int j = end;
                while (true) {
                    while (j < n && u8(b, j) != 0xFF) j++;
                    if (j + 1 >= n) { j = n; break; }
                    int nx = u8(b, j + 1);
                    if (nx == 0x00 || (nx >= 0xD0 && nx <= 0xD7)) { j += 2; continue; }
                    if (nx == 0xFF) { j += 1; continue; }
                    break;
                }
                end = j;
            }
            segs.add(new Seg(m, start, end, payload, false));
            i = end;
        }
        return segs;
    }

    static String appKind(int m, byte[] p) {
        if (m == 0xE0 && startsWith(p, 0, "JFIF\0".getBytes(StandardCharsets.ISO_8859_1))) return "jfif";
        if (m == 0xE0 && startsWith(p, 0, "JFXX\0".getBytes(StandardCharsets.ISO_8859_1))) return "jfxx";
        if (m == 0xE1 && startsWith(p, 0, "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1))) return "exif";
        if (m == 0xE1 && startsWith(p, 0, "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1))) return "xmp";
        if (m == 0xE1 && startsWith(p, 0, "http://ns.adobe.com/xmp/extension/".getBytes(StandardCharsets.ISO_8859_1))) return "xmpext";
        if (m == 0xE2 && startsWith(p, 0, "ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1))) return "icc";
        if (m == 0xE2 && startsWith(p, 0, "MPF\0".getBytes(StandardCharsets.ISO_8859_1))) return "mpf";
        if (m == 0xED) return "iptc";
        if (m == 0xEB) return "jumbf";
        if (m == 0xEE && startsWith(p, 0, "Adobe".getBytes(StandardCharsets.ISO_8859_1))) return "adobe";
        if (m == 0xFE) return "comment";
        if (m >= 0xE0 && m <= 0xEF) return "app" + (m - 0xE0);
        return null;
    }

    static List<Finding> jpegReport(byte[] b) {
        List<Finding> f = new ArrayList<>();
        for (Seg s : jpegSegments(b)) {
            if (s.trail) {
                if (allZero(s.payload, 0, s.payload.length)) continue;
                String head = ascii(s.payload, 0, Math.min(64, s.payload.length));
                String what = head.contains("ftyp") ? "hidden video (motion photo)"
                        : (s.payload.length > 1 && u8(s.payload, 0) == 0xFF && u8(s.payload, 1) == 0xD8) ? "extra image (HDR gain map / depth)"
                        : "unknown data";
                f.add(new Finding("other", "after image end", "trailing data", String.format(Locale.US, "%,d bytes - %s", s.payload.length, what)));
                continue;
            }
            String k = appKind(s.marker, s.payload);
            if (k == null) continue;
            switch (k) {
                case "exif": f.addAll(exifFindings(slice(s.payload, 6, s.payload.length), "EXIF")); break;
                case "xmp": case "xmpext": f.addAll(xmpFindings(new String(s.payload, StandardCharsets.UTF_8), "XMP")); break;
                case "iptc": f.addAll(iptcFindings(s.payload)); break;
                case "mpf": f.add(new Finding("other", "MPF", "multi-picture index", "points at extra images stored in the file")); break;
                case "jumbf": f.add(new Finding("device", "C2PA/JUMBF", "content credentials", "signed capture/edit record (device, app, sometimes time)")); break;
                case "comment": f.add(new Finding("other", "JPEG comment", "comment", shorten(new String(s.payload, StandardCharsets.ISO_8859_1)))); break;
                case "jfxx": f.add(new Finding("other", "JFXX", "embedded thumbnail", "preview image")); break;
                default:
                    if (k.startsWith("app") && !k.equals("app0"))
                        f.add(new Finding("other", k.toUpperCase(Locale.US), "vendor block", s.payload.length + " bytes"));
            }
        }
        return f;
    }

    static byte[] jpegStrip(byte[] b) {
        List<Seg> segs = jpegSegments(b);
        int orient = 1;
        for (Seg s : segs) if (!s.trail && "exif".equals(appKind(s.marker, s.payload))) { orient = exifOrientation(slice(s.payload, 6, s.payload.length)); break; }
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF); out.write(0xD8);
        boolean exifDone = orient == 1;
        for (Seg s : segs) {
            if (s.marker == 0xD8 || s.trail) continue;
            String k = appKind(s.marker, s.payload);
            if ("jfif".equals(k)) { out.write(b, s.start, s.end - s.start); continue; }
            if (!exifDone && k == null) {
                byte[] tiff = minimalExif(orient);
                int len = tiff.length + 6 + 2;
                out.write(0xFF); out.write(0xE1); out.write(len >> 8); out.write(len & 0xFF);
                out.write("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), 0, 6);
                out.write(tiff, 0, tiff.length);
                exifDone = true;
            }
            if ("icc".equals(k) || "adobe".equals(k) || k == null) out.write(b, s.start, s.end - s.start);
            if (s.marker == 0xD9) break;
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ PNG

    static final class Chunk {
        final String type; final byte[] data; final int start, end;
        Chunk(String type, byte[] data, int start, int end) { this.type = type; this.data = data; this.start = start; this.end = end; }
    }

    static List<Chunk> pngChunks(byte[] b) {
        List<Chunk> r = new ArrayList<>();
        int i = 8;
        while (i + 8 <= b.length) {
            long ln = be32(b, i);
            String t = ascii(b, i + 4, 4);
            if (i + 12 + ln > b.length) throw new IllegalArgumentException("truncated PNG chunk " + t);
            r.add(new Chunk(t, slice(b, i + 8, (int) (i + 8 + ln)), i, (int) (i + 12 + ln)));
            i += 12 + (int) ln;
            if (t.equals("IEND")) {
                if (i < b.length && !allZero(b, i, b.length)) r.add(new Chunk("TRAIL", slice(b, i, b.length), i, b.length));
                break;
            }
        }
        return r;
    }

    private static final Set<String> PNG_DROP = new HashSet<>(java.util.Arrays.asList("tEXt", "zTXt", "iTXt", "eXIf", "tIME", "caBX", "dSIG"));

    private static String[] pngText(String type, byte[] d) {
        try {
            int z = indexOf(d, 0, (byte) 0);
            String key = new String(d, 0, z, StandardCharsets.ISO_8859_1);
            if (type.equals("tEXt")) return new String[]{key, new String(d, z + 1, d.length - z - 1, StandardCharsets.ISO_8859_1)};
            if (type.equals("zTXt")) return new String[]{key, new String(inflate(slice(d, z + 2, d.length)), StandardCharsets.ISO_8859_1)};
            int comp = d[z + 1];
            int lz = indexOf(d, z + 3, (byte) 0);
            int tz = indexOf(d, lz + 1, (byte) 0);
            byte[] txt = slice(d, tz + 1, d.length);
            if (comp != 0) txt = inflate(txt);
            return new String[]{key, new String(txt, StandardCharsets.UTF_8)};
        } catch (RuntimeException e) {
            return new String[]{"?", ""};
        }
    }

    private static int indexOf(byte[] d, int from, byte v) {
        for (int i = from; i < d.length; i++) if (d[i] == v) return i;
        throw new IllegalArgumentException("no separator");
    }

    private static byte[] inflate(byte[] z) {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(z);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                o.write(buf, 0, n);
                if (o.size() > 16 << 20) break;
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IllegalArgumentException("bad compressed text");
        } finally {
            inf.end();
        }
        return o.toByteArray();
    }

    static List<Finding> pngReport(byte[] b) {
        List<Finding> f = new ArrayList<>();
        for (Chunk c : pngChunks(b)) {
            switch (c.type) {
                case "tEXt": case "zTXt": case "iTXt": {
                    String[] kv = pngText(c.type, c.data);
                    String k = kv[0], kl = k.toLowerCase(Locale.US);
                    if (k.equals("XML:com.adobe.xmp")) f.addAll(xmpFindings(kv[1], "XMP"));
                    else if (kl.startsWith("raw profile type exif")) f.add(new Finding("other", "PNG text", k, "hex-encoded EXIF block (ImageMagick)"));
                    else {
                        String cat = (kl.equals("author") || kl.equals("copyright") || kl.equals("disclaimer") || kl.equals("source")) ? "identity"
                                : (kl.contains("time") || kl.contains("date")) ? "time"
                                : kl.equals("software") ? "device" : "other";
                        f.add(new Finding(cat, "PNG text", k, shorten(kv[1])));
                    }
                    break;
                }
                case "eXIf": f.addAll(exifFindings(c.data, "EXIF")); break;
                case "tIME":
                    if (c.data.length >= 7)
                        f.add(new Finding("time", "PNG", "tIME (last modified)", String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                                be16(c.data, 0), u8(c.data, 2), u8(c.data, 3), u8(c.data, 4), u8(c.data, 5), u8(c.data, 6))));
                    break;
                case "caBX": f.add(new Finding("device", "C2PA", "content credentials", "signed capture/edit record")); break;
                case "TRAIL": f.add(new Finding("other", "after image end", "trailing data", String.format(Locale.US, "%,d bytes", c.data.length))); break;
                default:
            }
        }
        return f;
    }

    static byte[] pngChunk(String type, byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(data.length + 12);
        int n = data.length;
        o.write(n >>> 24); o.write(n >>> 16); o.write(n >>> 8); o.write(n);
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        o.write(t, 0, 4); o.write(data, 0, n);
        CRC32 crc = new CRC32(); crc.update(t); crc.update(data);
        long c = crc.getValue();
        o.write((int) (c >>> 24)); o.write((int) (c >>> 16)); o.write((int) (c >>> 8)); o.write((int) c);
        return o.toByteArray();
    }

    static byte[] pngStrip(byte[] b) {
        List<Chunk> cs = pngChunks(b);
        int orient = 1;
        for (Chunk c : cs) if (c.type.equals("eXIf")) orient = exifOrientation(c.data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 8);
        for (Chunk c : cs) {
            if (c.type.equals("TRAIL") || PNG_DROP.contains(c.type)) continue;
            if (c.type.equals("IDAT") && orient != 1) {
                byte[] e = pngChunk("eXIf", minimalExif(orient));
                out.write(e, 0, e.length);
                orient = 1;
            }
            out.write(b, c.start, c.end - c.start);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ WebP

    static List<Chunk> riffChunks(byte[] b) {
        List<Chunk> r = new ArrayList<>();
        int i = 12;
        while (i + 8 <= b.length) {
            String cc = ascii(b, i, 4);
            long ln = le32(b, i + 4);
            long pad = ln & 1;
            if (i + 8 + ln > b.length) throw new IllegalArgumentException("truncated WebP chunk " + cc);
            int end = (int) Math.min(b.length, i + 8 + ln + pad);
            r.add(new Chunk(cc, slice(b, i + 8, (int) (i + 8 + ln)), i, end));
            i = end;
        }
        return r;
    }

    private static byte[] exifPayload(byte[] d) {
        return startsWith(d, 0, "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)) ? slice(d, 6, d.length) : d;
    }

    static List<Finding> webpReport(byte[] b) {
        List<Finding> f = new ArrayList<>();
        for (Chunk c : riffChunks(b)) {
            if (c.type.equals("EXIF")) f.addAll(exifFindings(exifPayload(c.data), "EXIF"));
            else if (c.type.equals("XMP ")) f.addAll(xmpFindings(new String(c.data, StandardCharsets.UTF_8), "XMP"));
        }
        return f;
    }

    static byte[] webpStrip(byte[] b) {
        List<Chunk> cs = riffChunks(b);
        int orient = 1;
        boolean vp8x = false;
        for (Chunk c : cs) {
            if (c.type.equals("EXIF")) orient = exifOrientation(exifPayload(c.data));
            if (c.type.equals("VP8X")) vp8x = true;
        }
        boolean keepExif = orient != 1 && vp8x;
        ByteArrayOutputStream body = new ByteArrayOutputStream(b.length);
        for (Chunk c : cs) {
            if (c.type.equals("EXIF") || c.type.equals("XMP ")) continue;
            if (c.type.equals("VP8X") && c.data.length >= 1) {
                byte[] raw = slice(b, c.start, c.end);
                int flags = u8(c.data, 0) & ~0x0C;
                if (keepExif) flags |= 0x08;
                raw[8] = (byte) flags;
                body.write(raw, 0, raw.length);
            } else body.write(b, c.start, c.end - c.start);
        }
        if (keepExif) {
            byte[] ex = minimalExif(orient);
            int n = ex.length;
            body.write('E'); body.write('X'); body.write('I'); body.write('F');
            body.write(n); body.write(n >>> 8); body.write(n >>> 16); body.write(n >>> 24);
            body.write(ex, 0, n);
            if ((n & 1) == 1) body.write(0);
        }
        byte[] payload = body.toByteArray();
        int total = payload.length + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream(total + 8);
        out.write('R'); out.write('I'); out.write('F'); out.write('F');
        out.write(total); out.write(total >>> 8); out.write(total >>> 16); out.write(total >>> 24);
        out.write('W'); out.write('E'); out.write('B'); out.write('P');
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ GIF

    static final class Block {
        final String kind; final int start, end;
        Block(String kind, int start, int end) { this.kind = kind; this.start = start; this.end = end; }
    }

    static List<Block> gifBlocks(byte[] b) {
        List<Block> r = new ArrayList<>();
        int flags = u8(b, 10);
        int i = 13 + ((flags & 0x80) != 0 ? 3 * (2 << (flags & 7)) : 0);
        r.add(new Block("header", 0, i));
        int n = b.length;
        while (i < n) {
            int t = u8(b, i), start = i;
            if (t == 0x3B) {
                r.add(new Block("trailer", i, i + 1));
                if (i + 1 < n && !allZero(b, i + 1, n)) r.add(new Block("trail", i + 1, n));
                return r;
            }
            if (t == 0x21) {
                int label = u8(b, i + 1);
                i += 2;
                String app = "";
                if (label == 0xFF && i < n) app = ascii(b, i + 1, Math.min(11, u8(b, i)));
                while (i < n && b[i] != 0) i += 1 + u8(b, i);
                i += 1;
                r.add(new Block(String.format(Locale.US, "ext:%02X:%s", label, app), start, Math.min(i, n)));
            } else if (t == 0x2C) {
                int fl = u8(b, i + 9);
                i += 10 + ((fl & 0x80) != 0 ? 3 * (2 << (fl & 7)) : 0);
                i += 1;
                while (i < n && b[i] != 0) i += 1 + u8(b, i);
                i += 1;
                r.add(new Block("image", start, Math.min(i, n)));
            } else throw new IllegalArgumentException("corrupt GIF at byte " + i);
        }
        return r;
    }

    private static final String[] GIF_KEEP = {"NETSCAPE2.0", "ANIMEXTS1.0", "ICCRGBG1012"};

    private static boolean gifKeepApp(String app) {
        for (String k : GIF_KEEP) if (app.startsWith(k)) return true;
        return false;
    }

    static List<Finding> gifReport(byte[] b) {
        List<Finding> f = new ArrayList<>();
        for (Block k : gifBlocks(b)) {
            if (k.kind.startsWith("ext:FE")) {
                StringBuilder sb = new StringBuilder();
                int i = k.start + 2;
                while (i < k.end && b[i] != 0) { int l = u8(b, i); sb.append(ascii(b, i + 1, Math.min(l, k.end - i - 1))); i += 1 + l; }
                f.add(new Finding("other", "GIF", "comment", shorten(sb.toString())));
            } else if (k.kind.startsWith("ext:FF")) {
                String app = k.kind.substring(7);
                if (app.startsWith("XMP DataXMP")) f.addAll(xmpFindings(new String(b, k.start, k.end - k.start, StandardCharsets.UTF_8), "XMP"));
                else if (!gifKeepApp(app)) f.add(new Finding("other", "GIF", "application block", app.trim()));
            } else if (k.kind.equals("trail")) {
                f.add(new Finding("other", "after image end", "trailing data", String.format(Locale.US, "%,d bytes", k.end - k.start)));
            }
        }
        return f;
    }

    static byte[] gifStrip(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        for (Block k : gifBlocks(b)) {
            if (k.kind.equals("trail") || k.kind.startsWith("ext:FE")) continue;
            if (k.kind.startsWith("ext:FF") && !gifKeepApp(k.kind.substring(7))) continue;
            out.write(b, k.start, k.end - k.start);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ HEIF / AVIF

    /** A metadata item inside a HEIF file and where its bytes live. */
    static final class Item {
        final long id; final String type, contentType; final List<long[]> extents = new ArrayList<>();
        Item(long id, String type, String contentType) { this.id = id; this.type = type; this.contentType = contentType; }
        boolean isExif() { return type.equals("Exif"); }
        boolean isXmp() { return type.equals("mime") && contentType != null && (contentType.contains("rdf+xml") || contentType.contains("xmp")); }
    }

    private static long readN(byte[] d, int off, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | u8(d, off + i);
        return v;
    }

    /** Finds Exif / XMP items via meta -> iinf + iloc. Extents are absolute file ranges. */
    static List<Item> heifItems(byte[] d) {
        int meta = -1, metaEnd = -1;
        int i = 0;
        while (i + 8 <= d.length) {
            long size = be32(d, i);
            String t = ascii(d, i + 4, 4);
            int hdr = 8;
            if (size == 1) { size = readN(d, i + 8, 8); hdr = 16; }
            else if (size == 0) size = d.length - i;
            if (size < hdr || i + size > d.length) break;
            if (t.equals("meta")) { meta = i + hdr + 4; metaEnd = (int) (i + size); break; }
            i += (int) size;
        }
        List<Item> items = new ArrayList<>();
        if (meta < 0) return items;
        Map<Long, Item> byId = new HashMap<>();
        int ilocAt = -1, ilocEnd = -1, idatAt = -1;
        int j = meta;
        while (j + 8 <= metaEnd) {
            long size = be32(d, j);
            String t = ascii(d, j + 4, 4);
            if (size < 8 || j + size > metaEnd) break;
            int body = j + 8, end = (int) (j + size);
            if (t.equals("iinf")) {
                int v = u8(d, body);
                int p = body + 4;
                long cnt = v == 0 ? be16(d, p) : be32(d, p);
                p += v == 0 ? 2 : 4;
                for (long k = 0; k < cnt && p + 8 <= end; k++) {
                    long isz = be32(d, p);
                    if (isz < 8 || p + isz > end || !ascii(d, p + 4, 4).equals("infe")) break;
                    int iv = u8(d, p + 8);
                    int q = p + 12;
                    if (iv >= 2) {
                        long id = iv == 2 ? be16(d, q) : be32(d, q);
                        q += iv == 2 ? 2 : 4;
                        q += 2;                               // protection index
                        String type = ascii(d, q, 4);
                        q += 4;
                        int nz = q;
                        while (nz < p + isz && d[nz] != 0) nz++;  // item_name
                        q = nz + 1;
                        String ct = null;
                        if (type.equals("mime") && q < p + isz) {
                            int cz = q;
                            while (cz < p + isz && d[cz] != 0) cz++;
                            ct = ascii(d, q, cz - q).toLowerCase(Locale.US);
                        }
                        Item it = new Item(id, type, ct);
                        byId.put(id, it);
                    }
                    p += (int) isz;
                }
            } else if (t.equals("iloc")) { ilocAt = body; ilocEnd = end; }
            else if (t.equals("idat")) idatAt = body;
            j = end;
        }
        if (ilocAt < 0) return items;
        int v = u8(d, ilocAt);
        int p = ilocAt + 4;
        int offSize = u8(d, p) >> 4, lenSize = u8(d, p) & 15;
        int baseSize = u8(d, p + 1) >> 4, idxSize = (v == 1 || v == 2) ? (u8(d, p + 1) & 15) : 0;
        p += 2;
        long count = v < 2 ? be16(d, p) : be32(d, p);
        p += v < 2 ? 2 : 4;
        for (long k = 0; k < count && p < ilocEnd; k++) {
            long id = v < 2 ? be16(d, p) : be32(d, p);
            p += v < 2 ? 2 : 4;
            int method = 0;
            if (v == 1 || v == 2) { method = be16(d, p) & 15; p += 2; }
            p += 2;                                           // data_reference_index
            long base = readN(d, p, baseSize);
            p += baseSize;
            int ext = be16(d, p);
            p += 2;
            Item it = byId.get(id);
            for (int e = 0; e < ext; e++) {
                p += idxSize;
                long eo = readN(d, p, offSize); p += offSize;
                long el = readN(d, p, lenSize); p += lenSize;
                if (it == null) continue;
                long abs;
                if (method == 0) abs = base + eo;
                else if (method == 1 && idatAt >= 0) abs = idatAt + base + eo;
                else continue;
                if (el == 0) el = d.length - abs;
                if (abs >= 0 && abs + el <= d.length) it.extents.add(new long[]{abs, el});
            }
        }
        for (Item it : byId.values()) if ((it.isExif() || it.isXmp()) && !it.extents.isEmpty()) items.add(it);
        items.sort((a, b) -> Long.compare(a.id, b.id));
        return items;
    }

    private static byte[] gather(byte[] d, Item it) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (long[] e : it.extents) o.write(d, (int) e[0], (int) e[1]);
        return o.toByteArray();
    }

    /** HEIF Exif item = 4-byte offset to the TIFF header, then (usually "Exif\0\0" +) TIFF. */
    private static byte[] heifTiff(byte[] item) {
        if (item.length < 4) return new byte[0];
        long off = be32(item, 0);
        int s = (int) Math.min(item.length, 4 + off);
        for (int k = s; k + 4 <= item.length && k < s + 16; k++)
            if ((item[k] == 'I' && item[k + 1] == 'I' && item[k + 2] == '*') || (item[k] == 'M' && item[k + 1] == 'M' && item[k + 3] == '*'))
                return slice(item, k, item.length);
        for (int k = 0; k + 4 <= item.length && k < 64; k++)
            if ((item[k] == 'I' && item[k + 1] == 'I' && item[k + 2] == '*') || (item[k] == 'M' && item[k + 1] == 'M' && item[k + 3] == '*'))
                return slice(item, k, item.length);
        return new byte[0];
    }

    static List<Finding> heifReport(byte[] d) {
        List<Finding> f = new ArrayList<>();
        for (Item it : heifItems(d)) {
            byte[] data = gather(d, it);
            if (it.isExif()) f.addAll(exifFindings(heifTiff(data), "EXIF"));
            else f.addAll(xmpFindings(new String(data, StandardCharsets.UTF_8), "XMP"));
        }
        return f;
    }

    private static final byte[] EMPTY_TIFF = {'M', 'M', 0, 0x2A, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0};
    private static final byte[] EMPTY_XMP = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>".getBytes(StandardCharsets.US_ASCII);

    /** Overwrites Exif/XMP items in place with a same-size empty stand-in. Pixels and offsets untouched. */
    static byte[] heifStrip(byte[] d) {
        byte[] out = d.clone();
        for (Item it : heifItems(d)) {
            long total = 0;
            for (long[] e : it.extents) total += e[1];
            byte[] repl = new byte[(int) total];
            if (it.isExif() && total >= 4 + EMPTY_TIFF.length) {
                System.arraycopy(EMPTY_TIFF, 0, repl, 4, EMPTY_TIFF.length);   // offset 0, empty IFD, zero fill
            } else if (it.isXmp()) {
                java.util.Arrays.fill(repl, (byte) ' ');
                if (total >= EMPTY_XMP.length) System.arraycopy(EMPTY_XMP, 0, repl, 0, EMPTY_XMP.length);
            }
            int p = 0;
            for (long[] e : it.extents) {
                System.arraycopy(repl, p, out, (int) e[0], (int) e[1]);
                p += (int) e[1];
            }
        }
        return out;
    }
}
