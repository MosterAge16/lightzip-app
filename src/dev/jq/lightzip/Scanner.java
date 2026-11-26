package dev.jq.lightzip;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** 伪装文件检测 / 内嵌压缩包扫描与分离 / 7z 完整性检查 */
public final class Scanner {

    public static final String[] SIG_NAMES = {
            "7z", "ZIP", "RAR4", "RAR5", "gzip", "xz", "zstd", "TAR", "CAB",
            "PNG", "JPEG", "GIF"};
    static final byte[][] SIGS = {
            {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C},   // 7z
            {'P', 'K', 0x03, 0x04},                                // ZIP
            {'R', 'a', 'r', '!', 0x1A, 0x07, 0x00},                // RAR4
            {'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00},          // RAR5
            {0x1F, (byte) 0x8B, 0x08},                             // gzip
            {(byte) 0xFD, '7', 'z', 'X', 'Z', 0x00},               // xz
            {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD},                // zstd
            {'u', 's', 't', 'a', 'r'},                             // TAR
            {'M', 'S', 'C', 'F', 0, 0, 0, 0},                      // CAB
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A},  // PNG
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},               // JPEG
            {'G', 'I', 'F', '8'},                                  // GIF
    };

    public static final int IDX_ZIP = 1, IDX_RAR4 = 2, IDX_RAR5 = 3;

    public interface Logger { void log(String s); }

    private Scanner() {}

    /** 读文件头判断真实格式（含伪装识别） */
    public static int signatureOf(java.io.File f) {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(f), 32);
            byte[] b = new byte[16];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            for (int s = 0; s < SIGS.length; s++) {
                byte[] sig = SIGS[s];
                if (n < sig.length) continue;
                boolean ok = true;
                for (int i = 0; i < sig.length; i++) {
                    if (b[i] != sig[i]) { ok = false; break; }
                }
                if (ok) return s;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) {}
        }
    }

    public static class DeepResult {
        public int type = -1;      // SIGS 下标（压缩格式才计入）
        public long offset = -1;   // 首次出现偏移
        public final List<String> found = new ArrayList<>();  // 报告行
    }

    /** 全文件扫描，找内嵌压缩包（压缩格式 0..8，图片格式仅作报告） */
    public static DeepResult scanDeep(java.io.File f, Logger log) {
        DeepResult res = new DeepResult();
        long[] first = new long[SIGS.length];
        byte[][] ctx = new byte[SIGS.length][];
        for (int i = 0; i < SIGS.length; i++) first[i] = -1;
        InputStream in = null;
        try {
            final int OV = 24, CHUNK = 1 << 20;
            byte[] prev = new byte[OV];
            int prevLen = 0;
            long pos = 0;
            in = new BufferedInputStream(new FileInputStream(f), CHUNK);
            byte[] chunk = new byte[CHUNK];
            while (true) {
                int n = 0;
                while (n < CHUNK) {
                    int r = in.read(chunk, n, CHUNK - n);
                    if (r < 0) break;
                    n += r;
                }
                if (n <= 0 && prevLen == 0) break;
                byte[] win = new byte[prevLen + Math.max(n, 0)];
                System.arraycopy(prev, 0, win, 0, prevLen);
                System.arraycopy(chunk, 0, win, prevLen, Math.max(n, 0));
                long base = pos - prevLen;
                for (int s = 0; s < SIGS.length; s++) {
                    byte[] sig = SIGS[s];
                    for (int i = 0; i + sig.length <= win.length; i++) {
                        if (win[i] != sig[0]) continue;
                        boolean ok = true;
                        for (int j = 1; j < sig.length; j++) {
                            if (win[i + j] != sig[j]) { ok = false; break; }
                        }
                        if (!ok) continue;
                        long off = base + i;
                        if (off < pos - prevLen) continue;
                        if (first[s] < 0) {
                            first[s] = off;
                            int c = Math.min(16, win.length - i);
                            ctx[s] = new byte[c];
                            System.arraycopy(win, i, ctx[s], 0, c);
                        }
                    }
                }
                if (n <= 0) break;
                prevLen = Math.min(OV, n);
                System.arraycopy(chunk, n - prevLen, prev, 0, prevLen);
                pos += n;
            }
        } catch (Exception e) {
            if (log != null) log.log("[扫描异常] " + e);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) {}
        }
        for (int s = 0; s < SIGS.length; s++) {
            if (first[s] < 0) continue;
            String line = SIG_NAMES[s] + " @ " + first[s] + " 字节";
            res.found.add(line);
            boolean isArchive = s <= 8;
            if (isArchive && (res.type < 0 || first[s] < res.offset)) {
                res.type = s;
                res.offset = first[s];
            }
        }
        return res;
    }

    /** 从 offset 剪到文件尾 */
    public static boolean detach(java.io.File src, long offset, java.io.File dst) {
        InputStream in = null;
        java.io.OutputStream fo = null;
        try {
            in = new FileInputStream(src);
            fo = new java.io.FileOutputStream(dst);
            long skip = offset;
            while (skip > 0) {
                long r = in.skip(skip);
                if (r <= 0) return false;
                skip -= r;
            }
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) {}
            try { if (fo != null) fo.close(); } catch (Exception ignore) {}
        }
    }

    /** 7z 起始头完整性：返回 null=不是7z；[期望总大小, 实际大小, 加密头偏移, 加密头大小] */
    public static long[] check7z(java.io.File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] h = new byte[32];
            if (raf.length() < 32) return null;
            raf.readFully(h);
            for (int i = 0; i < 6; i++) {
                if ((h[i] & 0xFF) != SIGS[0][i]) return null;
            }
            long nho = le64(h, 12), nhs = le64(h, 20);
            return new long[]{32 + nho + nhs, raf.length(), nho, nhs};
        } catch (Exception e) {
            return null;
        } finally {
            try { if (raf != null) raf.close(); } catch (Exception ignore) {}
        }
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    // ---------- 分卷检测 ----------

    public static class VolumeSet {
        public boolean isSet;         // 是否分卷
        public File[] parts;          // 按序的全部卷（含主文件）
        public boolean complete;      // 编号是否连续齐全
        public String missing;        // 缺失的第一个卷文件名
    }

    /** 识别 .7z.001 / .zip.001 / xxx.001 / xxx.partN.rar / xxx.rar+r00 老式分卷 */
    public static VolumeSet detectVolumes(File f) {
        VolumeSet vs = new VolumeSet();
        File dir = f.getParentFile();
        if (dir == null) return vs;
        String name = f.getName();
        File[] all = dir.listFiles();
        if (all == null) return vs;

        // 模式1: prefix.ext.NNN（7z/zip/tar 常见，或纯 .001）
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0 && name.length() - lastDot == 4
                && isDigits(name, lastDot + 1)) {
            String prefix = name.substring(0, lastDot);
            List<File> parts = new ArrayList<File>();
            String missing = null;
            for (int n = 1; n <= 999; n++) {
                File p = new File(dir, prefix + String.format(".%03d", n));
                if (p.isFile()) {
                    parts.add(p);
                } else {
                    if (n == 1) { parts.clear(); break; }   // 不是从 001 开始，模式不成立
                    missing = p.getName();
                    break;
                }
            }
            if (parts.size() > 1 || (parts.size() == 1 && missing == null && !name.endsWith("001"))) {
                // 至少两卷才算分卷；单卷 + 编号后缀按普通文件处理
            }
            if (parts.size() >= 2) {
                vs.isSet = true;
                vs.parts = parts.toArray(new File[0]);
                vs.complete = missing == null;
                vs.missing = missing;
                return vs;
            }
        }

        // 模式2: xxx.partN.rar
        int pr = name.indexOf(".part");
        if (pr > 0 && name.endsWith(".rar")) {
            String mid = name.substring(pr + 5, name.length() - 4);
            if (isDigits(mid, 0)) {
                String prefix = name.substring(0, pr);
                List<File> parts = new ArrayList<File>();
                String missing = null;
                boolean any = false;
                for (int n = 1; n <= 999; n++) {
                    File p = new File(dir, prefix + ".part" + n + ".rar");
                    if (p.isFile()) { parts.add(p); any = true; }
                    else if (n == 1) { continue; }   // 允许从 part2 起没有 part1？part1 缺即缺
                    else if (n > 1) { missing = p.getName(); break; }
                }
                if (any) {
                    // part1 必须存在（解压入口）
                    File p1 = new File(dir, prefix + ".part1.rar");
                    vs.isSet = true;
                    vs.parts = parts.toArray(new File[0]);
                    vs.complete = p1.isFile() && missing == null;
                    vs.missing = p1.isFile() ? missing : p1.getName();
                    return vs;
                }
            }
        }

        // 模式3: xxx.rar + xxx.r00/.r01（老式 rar）
        if (name.endsWith(".rar")) {
            List<File> parts = new ArrayList<File>();
            parts.add(f);
            for (int n = 0; n <= 99; n++) {
                File p = new File(dir, name.substring(0, name.length() - 4)
                        + String.format(".r%02d", n));
                if (p.isFile()) parts.add(p); else break;
            }
            if (parts.size() >= 2) {
                vs.isSet = true;
                vs.parts = parts.toArray(new File[0]);
                vs.complete = true;   // r 编号不连续时引擎会报错，这里不预判
                return vs;
            }
        }
        return vs;
    }

    private static boolean isDigits(String s, int from) {
        if (from >= s.length()) return false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
