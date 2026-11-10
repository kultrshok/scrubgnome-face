package com.kultrshok.scrubgnome.face;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.kultrshok.scrubgnome.face.core.ImageScrubber;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scrubgnome-Face - pick photos, see what they leak, save clean copies.
 * One screen, no internet permission, nothing leaves the phone.
 * Author: kultrshok. MIT License.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 42, REQ_LOC = 43;

    // palette (same as Away from Home)
    private static final int BG = 0xFF121417, CARD = 0xFF1E2228, TEXT = 0xFFE6E6E6, MUTED = 0xFF9AA4AE;
    private static final int ACCENT = 0xFF7BC67E, ACCENT_DARK = 0xFF2F5D33, WARN = 0xFFE8B04B, DANGER = 0xFFE5534B;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** One picked photo. */
    private static final class Photo {
        Uri uri, saved;
        String name, error;
        File work;
        ImageScrubber.Report report;
        TextView score, done, geek;
    }

    private final List<Photo> photos = new ArrayList<>();
    private List<Uri> pendingLoad;
    private boolean busy, askedLocation;

    // views
    private TextView twigSays, status, savedText;
    private LinearLayout list, savedCard;
    private Button pickBtn, scrubBtn, shareBtn, againBtn;
    private ProgressBar progress;
    private Switch geek;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        clearWork();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        if (isFinishing()) clearWork();
    }

    /** "Share" from the gallery lands here, one photo or many. */
    @SuppressWarnings("deprecation")
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = android.os.Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> l = android.os.Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (!uris.isEmpty()) load(uris);
    }

    // =============================================================================
    // UI
    // =============================================================================

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(18), dp(18), dp(40));
        scroll.addView(col);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView twig = new ImageView(this);
        twig.setImageResource(R.drawable.twig);
        twig.setAdjustViewBounds(true);
        twig.setContentDescription("Twig the tinker gnome");
        header.addView(twig, new LinearLayout.LayoutParams(dp(88), dp(88)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("Scrubgnome", 26, TEXT, true));
        titles.addView(text("Face · photo scrubber", 16, ACCENT, false));
        header.addView(titles);
        col.addView(header);

        twigSays = text(getString(R.string.twig_intro), 15, TEXT, false);
        LinearLayout bubble = card();
        bubble.addView(twigSays);
        col.addView(bubble, cardParams());

        pickBtn = button("🖼️  Pick photos", true);
        pickBtn.setOnClickListener(v -> pick());
        col.addView(pickBtn, btnParams());

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        scrubBtn = button("🧹  Scrub & save clean copies", true);
        scrubBtn.setOnClickListener(v -> scrub());
        scrubBtn.setVisibility(View.GONE);
        col.addView(scrubBtn, btnParams());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setVisibility(View.GONE);
        col.addView(progress, new LinearLayout.LayoutParams(-1, dp(10)));
        status = text("", 13, MUTED, false);
        col.addView(status);

        savedCard = card();
        savedText = text("", 15, TEXT, false);
        savedCard.addView(savedText);
        shareBtn = button("📤  Share / upload clean copies", true);
        shareBtn.setOnClickListener(v -> share());
        savedCard.addView(shareBtn, btnParams());
        againBtn = button("Scrub more photos", false);
        againBtn.setOnClickListener(v -> reset());
        savedCard.addView(againBtn, btnParams());
        savedCard.setVisibility(View.GONE);
        col.addView(savedCard, cardParams());

        geek = new Switch(this);
        geek.setText("Geek mode (show the gory details)");
        geek.setTextColor(MUTED);
        geek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        geek.setOnCheckedChangeListener((b, on) -> applyGeek());
        col.addView(geek, cardParams());

        col.addView(text(getString(R.string.footer), 12, MUTED, false), cardParams());
        TextView ai = text("Generated with AI", 11, MUTED, false);
        ai.setGravity(Gravity.END);
        col.addView(ai, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private void applyGeek() {
        boolean on = geek.isChecked();
        for (Photo p : photos)
            if (p.geek != null) p.geek.setVisibility(on && p.geek.getText().length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void setBusy(boolean b, String msg) {
        busy = b;
        pickBtn.setEnabled(!b);
        scrubBtn.setEnabled(!b);
        float a = b ? 0.5f : 1f;
        pickBtn.setAlpha(a);
        scrubBtn.setAlpha(a);
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        progress.setProgress(0);
        status.setTextColor(MUTED);
        status.setText(msg == null ? "" : msg);
    }

    private void setProgress(float f) {
        ui.post(() -> progress.setProgress((int) (f * 1000)));
    }

    // =============================================================================
    // Pick + load
    // =============================================================================

    private void pick() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (!uris.isEmpty()) load(uris);
    }

    private boolean hasLocationAccess() {
        return checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Ask once per session so Twig can show the real GPS (he scrubs it either way). */
    private void load(List<Uri> uris) {
        if (busy) return;
        if (!hasLocationAccess() && !askedLocation) {
            askedLocation = true;
            pendingLoad = uris;
            requestPermissions(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION}, REQ_LOC);
            return;
        }
        loadNow(uris);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_LOC && pendingLoad != null) {
            List<Uri> u = pendingLoad;
            pendingLoad = null;
            loadNow(u);
        }
    }

    /**
     * Android hides a photo's GPS from apps unless they hold ACCESS_MEDIA_LOCATION AND ask
     * for the "original". Try for the original; fall back to what we were given.
     */
    private InputStream openPhoto(ContentResolver cr, Uri uri) throws IOException {
        if (hasLocationAccess()) {
            try {
                Uri m = uri;
                if (DocumentsContract.isDocumentUri(this, uri)) m = MediaStore.getMediaUri(this, uri);
                if (m != null && MediaStore.AUTHORITY.equals(m.getAuthority())) {
                    InputStream in = cr.openInputStream(MediaStore.setRequireOriginal(m));
                    if (in != null) return in;
                }
            } catch (Exception ignored) {
                // not a MediaStore item, or no grant for the original: use the plain stream
            }
        }
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("Couldn't open that photo.");
        return in;
    }

    private void loadNow(List<Uri> uris) {
        reset();
        setBusy(true, "Hauling them into my workshop… careful, careful…");
        worker.execute(() -> {
            ContentResolver cr = getContentResolver();
            File dir = workDir();
            int leaky = 0, bad = 0;
            for (int i = 0; i < uris.size(); i++) {
                Photo p = new Photo();
                p.uri = uris.get(i);
                p.name = "photo";
                Bitmap thumb = null;
                try {
                    try (Cursor c = cr.query(p.uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (c != null && c.moveToFirst() && !c.isNull(0)) p.name = c.getString(0);
                    } catch (Exception ignored) {
                        // some providers don't do queries; keep the default name
                    }
                    p.work = new File(dir, "in_" + i);
                    try (InputStream in = openPhoto(cr, p.uri); OutputStream out = new FileOutputStream(p.work)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    p.report = ImageScrubber.scan(Files.readAllBytes(p.work.toPath()));
                    if (!p.report.isClean()) leaky++;
                    thumb = thumbnail(p.work);
                } catch (ImageScrubber.UnsupportedFormatException e) {
                    bad++;
                    p.error = "#@%&! " + e.getMessage() + " I do JPEG, PNG, WebP, GIF, HEIC and AVIF.";
                } catch (Exception e) {
                    bad++;
                    p.error = "Gah! Couldn't read this one: " + e.getMessage();
                }
                final Bitmap t = thumb;
                ui.post(() -> {
                    photos.add(p);
                    list.addView(photoCard(p, t), cardParams());
                    applyGeek();
                });
                setProgress((i + 1f) / uris.size());
            }
            final int lk = leaky, bd = bad, total = uris.size();
            ui.post(() -> {
                setBusy(false, null);
                int ok = total - bd;
                scrubBtn.setVisibility(ok > 0 ? View.VISIBLE : View.GONE);
                scrubBtn.setText(ok == 1 ? "🧹  Scrub & save a clean copy" : "🧹  Scrub all " + ok + " & save clean copies");
                if (ok == 0) twigSays.setText("Nothing here I can work on. Photos only, please: JPEG, PNG, WebP, GIF, HEIC or AVIF.");
                else if (lk == 0) twigSays.setText("Huh. Not a single snitch in the bunch. Either you're a pro or someone beat me to it. I'm mildly offended. You can still save fresh copies with random names.");
                else twigSays.setText("Great gears! " + (lk == 1 ? "This photo is" : lk + " of these are")
                        + " blabbing like tourists. Hit Scrub and I'll make " + (lk == 1 ? "it" : "them") + " forget everything."
                        + (bd > 0 ? " (I skipped the ones I couldn't read.)" : ""));
            });
        });
    }

    private Bitmap thumbnail(File f) {
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, src) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                float s = Math.min(1f, 192f / Math.max(1, Math.max(w, h)));
                decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
            });
        } catch (Exception e) {
            return null;    // older phones can't preview AVIF; that's fine
        }
    }

    private View photoCard(Photo p, Bitmap thumb) {
        LinearLayout c = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0xFF0B0D11);
        ph.setCornerRadius(dp(10));
        iv.setBackground(ph);
        iv.setClipToOutline(true);
        if (thumb != null) iv.setImageBitmap(thumb);
        top.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        mid.addView(text(p.name, 15, TEXT, true));
        String fmt = p.report == null ? "unreadable" : p.report.format.toUpperCase(Locale.US);
        mid.addView(text(p.work != null ? human(p.work.length()) + " · " + fmt : fmt, 13, MUTED, false));
        top.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
        p.score = text("", 20, TEXT, true);
        top.addView(p.score);
        c.addView(top);
        if (p.error != null) {
            c.addView(spacer(6));
            c.addView(text(p.error, 14, DANGER, false));
            return c;
        }
        int s = p.report.score();
        p.score.setText(String.valueOf(s));
        p.score.setTextColor(s >= 50 ? DANGER : s > 0 ? WARN : ACCENT);
        List<String[]> lines = plainLines(p.report.findings, p.name);
        if (lines.isEmpty() && p.report.isClean()) {
            c.addView(spacer(6));
            c.addView(text("Already squeaky clean. Suspicious… but clean.", 14, ACCENT, false));
        }
        for (String[] l : lines) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(l[0]).append(' ').append(l[1]).append(": ");
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(l[2]);
            TextView t = text("", 14, TEXT, false);
            t.setText(sb);
            t.setPadding(0, dp(4), 0, 0);
            c.addView(t);
        }
        StringBuilder g = new StringBuilder();
        for (ImageScrubber.Finding f : p.report.findings) g.append("› ").append(f).append('\n');
        p.geek = text(g.toString().trim(), 12, MUTED, false);
        p.geek.setTypeface(Typeface.MONOSPACE);
        p.geek.setPadding(0, dp(8), 0, 0);
        p.geek.setVisibility(View.GONE);
        c.addView(p.geek);
        p.done = text("", 14, ACCENT, false);
        p.done.setVisibility(View.GONE);
        c.addView(p.done);
        return c;
    }

    /** Plain-language summary, one line per kind of leak: {icon, label, value}. */
    static List<String[]> plainLines(List<ImageScrubber.Finding> fs, String name) {
        List<String[]> out = new ArrayList<>();
        ImageScrubber.Finding gps = find(fs, null, "GPS position");
        if (gps != null) out.add(new String[]{"📍", "Where you were", gps.value});
        else {
            List<String> loc = new ArrayList<>();
            for (ImageScrubber.Finding f : fs) if (f.cat.equals("location")) loc.add(f.value);
            if (!loc.isEmpty()) out.add(new String[]{"📍", "Where you were", String.join(", ", loc)});
        }
        ImageScrubber.Finding when = find(fs, null, "DateTimeOriginal");
        if (when == null) when = find(fs, "time", null);
        if (when != null) out.add(new String[]{"🕒", "When", when.value});
        ImageScrubber.Finding make = find(fs, null, "Make"), model = find(fs, null, "Model");
        if (make != null || model != null)
            out.add(new String[]{"📱", "What with", ((make != null ? make.value : "") + " " + (model != null ? model.value : "")).trim()});
        else {
            ImageScrubber.Finding dev = find(fs, "device", null);
            if (dev != null) out.add(new String[]{"📱", "What with", dev.value});
        }
        List<String> who = new ArrayList<>();
        for (ImageScrubber.Finding f : fs) if (f.cat.equals("identity") && who.size() < 3) who.add(f.key + ": " + f.value);
        if (!who.isEmpty()) out.add(new String[]{"🪪", "Who", String.join(" · ", who)});
        List<String> extras = new ArrayList<>();
        if (find(fs, null, "embedded thumbnail") != null) extras.add("a hidden preview thumbnail");
        for (ImageScrubber.Finding f : fs) if (f.value.contains("motion photo")) { extras.add("a motion-photo video"); break; }
        if (find(fs, null, "edit history") != null) extras.add("edit history");
        if (find(fs, null, "comment") != null) extras.add("a comment");
        if (!extras.isEmpty()) out.add(new String[]{"🧩", "Hidden extras", String.join(", ", extras)});
        String stamp = ImageScrubber.dateInName(name);
        if (stamp != null) out.add(new String[]{"🏷️", "The file name", "\"" + stamp + "\" is a timestamp, so the clean copy gets a random name"});
        return out;
    }

    private static ImageScrubber.Finding find(List<ImageScrubber.Finding> fs, String cat, String key) {
        for (ImageScrubber.Finding f : fs)
            if ((cat == null || f.cat.equals(cat)) && (key == null || f.key.equals(key))) return f;
        return null;
    }

    // =============================================================================
    // Scrub + save
    // =============================================================================

    private void scrub() {
        if (busy) return;
        setBusy(true, "Scrubbing! Zap… wipe… forget…");
        final List<Photo> todo = new ArrayList<>();
        for (Photo p : photos) if (p.error == null && p.saved == null) todo.add(p);
        worker.execute(() -> {
            int removed = 0, saved = 0;
            for (int i = 0; i < todo.size(); i++) {
                Photo p = todo.get(i);
                try {
                    ImageScrubber.Result r = ImageScrubber.scrub(Files.readAllBytes(p.work.toPath()));
                    ImageScrubber.Report verify = ImageScrubber.scan(r.clean);
                    if (!verify.isClean()) {
                        fail("#@%&! Something survived the scrub in " + p.name + ": " + verify.categories()
                                + ". Don't post that one. Tell kultrshok.");
                        return;
                    }
                    String outName = "photo_" + randomHex(3) + ImageScrubber.extensionFor(r.before.format);
                    Uri u = saveToPictures(r.clean, outName, ImageScrubber.mimeFor(r.before.format));
                    p.saved = u;
                    removed += r.before.findings.size();
                    saved++;
                    final int before = r.before.score();
                    final String msg = "✔ Saved Pictures/Scrubgnome/" + outName + " (" + human(r.clean.length) + ")";
                    ui.post(() -> {
                        p.score.setText(before + " → 0");
                        p.score.setTextColor(ACCENT);
                        p.done.setText(msg);
                        p.done.setPadding(0, dp(8), 0, 0);
                        p.done.setVisibility(View.VISIBLE);
                    });
                } catch (Exception e) {
                    fail("Gah! Scrub failed on " + p.name + ": " + e.getMessage());
                    return;
                }
                setProgress((i + 1f) / todo.size());
            }
            final int rm = removed, sv = saved;
            ui.post(() -> {
                setBusy(false, null);
                scrubBtn.setVisibility(View.GONE);
                savedCard.setVisibility(View.VISIBLE);
                savedText.setText("Saved and double-checked: zero metadata left in " + sv + " clean cop" + (sv == 1 ? "y" : "ies")
                        + ".\n\n📁 Pictures/Scrubgnome/\n\nRemoved " + rm + " snitching bits. The pictures themselves are untouched, not one pixel re-encoded.");
                twigSays.setText("Ha HA! Squeaky clean! Share the copies from Pictures/Scrubgnome, not the originals. The originals still blab.");
            });
        });
    }

    private Uri saveToPictures(byte[] data, String name, String mime) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Scrubgnome");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri u = cr.insert(coll, v);
        if (u == null) throw new IOException("Android wouldn't give me a spot in Pictures.");
        try (OutputStream out = cr.openOutputStream(u)) {
            if (out == null) throw new IOException("Couldn't write to Pictures.");
            out.write(data);
        } catch (IOException e) {
            cr.delete(u, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, v, null, null);
        return u;
    }

    private void share() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Photo p : photos) if (p.saved != null) uris.add(p.saved);
        if (uris.isEmpty()) return;
        Intent s;
        if (uris.size() == 1) {
            s = new Intent(Intent.ACTION_SEND);
            s.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            s = new Intent(Intent.ACTION_SEND_MULTIPLE);
            s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        s.setType("image/*");
        ClipData clip = ClipData.newRawUri("", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        s.setClipData(clip);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "Send the clean copies to…"));
    }

    // =============================================================================
    // helpers
    // =============================================================================

    private void reset() {
        photos.clear();
        list.removeAllViews();
        clearWork();
        scrubBtn.setVisibility(View.GONE);
        savedCard.setVisibility(View.GONE);
        twigSays.setText(getString(R.string.twig_intro));
        status.setText("");
    }

    private void fail(String msg) {
        ui.post(() -> {
            setBusy(false, null);
            status.setTextColor(DANGER);
            status.setText(msg);
            twigSays.setText("Well, #@%&. That didn't go to plan. Read the red bit below.");
        });
    }

    private File workDir() {
        File d = new File(getCacheDir(), "work");
        if (!d.exists() && !d.mkdirs()) d = getCacheDir();
        return d;
    }

    private void clearWork() {
        File[] fs = new File(getCacheDir(), "work").listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1 << 20) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(14));
        c.setBackground(g);
        return c;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(primary ? Color.BLACK : TEXT);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(54));
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : CARD);
        g.setCornerRadius(dp(14));
        if (!primary) g.setStroke(dp(1), ACCENT_DARK);
        b.setBackground(g);
        return b;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(14);
        return p;
    }

    private LinearLayout.LayoutParams btnParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        return p;
    }
}
