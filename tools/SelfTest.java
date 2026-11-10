import com.kultrshok.scrubgnome.face.core.ImageScrubber;

import java.io.File;
import java.nio.file.Files;

/**
 * Desktop self-test for the pure-Java core. For every photo in IN_DIR:
 * it must report leaks, scrub must produce a file, and a re-scan of that
 * file must come back completely clean. Clean copies go to OUT_DIR.
 * Usage: java SelfTest IN_DIR OUT_DIR
 */
public class SelfTest {
    public static void main(String[] a) throws Exception {
        File in = new File(a[0]), out = new File(a[1]);
        out.mkdirs();
        File[] files = in.listFiles();
        if (files == null || files.length == 0) throw new AssertionError("no samples in " + in);
        java.util.Arrays.sort(files);
        int n = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            byte[] d = Files.readAllBytes(f.toPath());
            ImageScrubber.Report before = ImageScrubber.scan(d);
            if (before.isClean()) throw new AssertionError(f.getName() + ": found no leaks in a leaky sample");
            ImageScrubber.Result r = ImageScrubber.scrub(d);
            ImageScrubber.Report after = ImageScrubber.scan(r.clean);
            System.out.printf("%-40s %-5s %3d -> %3d  (%d findings, %,d -> %,d bytes)%n", f.getName(), before.format,
                    before.score(), after.score(), before.findings.size(), d.length, r.clean.length);
            if (!after.isClean()) {
                for (ImageScrubber.Finding x : after.findings) System.out.println("   LEFT: " + x);
                throw new AssertionError(f.getName() + ": something survived the scrub");
            }
            if (!before.format.equals(ImageScrubber.sniff(r.clean))) throw new AssertionError(f.getName() + ": format changed");
            Files.write(new File(out, f.getName()).toPath(), r.clean);
            StringBuilder rep = new StringBuilder();
            for (ImageScrubber.Finding x : before.findings) rep.append(x.cat).append('|').append(x.where).append('|').append(x.key).append('|').append(x.value).append('\n');
            Files.write(new File(out, f.getName() + ".findings.txt").toPath(), rep.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            n++;
        }
        if (ImageScrubber.dateInName("PXL_20261107_183522.jpg") == null) throw new AssertionError("date-in-name");
        if (ImageScrubber.dateInName("holiday.jpg") != null) throw new AssertionError("date-in-name false positive");
        System.out.println("java core: " + n + " photos scrubbed clean");
    }
}
