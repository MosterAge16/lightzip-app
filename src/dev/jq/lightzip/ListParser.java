package dev.jq.lightzip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 解析 7zz l -slt / unrar v -slt 的输出为条目列表 */
public final class ListParser {

    public static class Result {
        public final List<Entry> entries = new ArrayList<>();
        public String archiveType = "";   // 7z / RAR5 v5 / zip ...
        public String method = "";        // LZMA2:24 7zAES 等
        public int fileCount = 0, dirCount = 0;
        public long totalSize = 0, totalPacked = 0;
    }

    private ListParser() {}

    public static Result parse(boolean sevenZip, List<String> lines) {
        Result r = new Result();
        List<String> block = new ArrayList<>();
        for (String ln : lines) {
            if (ln.trim().length() == 0 || ln.startsWith("----------")) {
                absorb(sevenZip, r, block);
                block.clear();
            } else {
                block.add(ln);
            }
        }
        absorb(sevenZip, r, block);
        sort(r.entries);
        return r;
    }

    private static void absorb(boolean sevenZip, Result r, List<String> block) {
        if (block.isEmpty()) return;
        String path = null, size = null, packed = null, attrs = null, mod = null,
                type = null, method = null;
        for (String ln0 : block) {
            // unrar 的标签右对齐（%12ls:），行首有空格，必须 trim 后再匹配
            String ln = ln0.trim();
            if (sevenZip) {
                if (ln.startsWith("Path = ")) path = val(ln);
                else if (ln.startsWith("Size = ")) size = val(ln);
                else if (ln.startsWith("Packed Size = ")) packed = val(ln);
                else if (ln.startsWith("Attributes = ")) attrs = val(ln);
                else if (ln.startsWith("Modified = ")) mod = val(ln);
                else if (ln.startsWith("Type = ")) type = val(ln);
                else if (ln.startsWith("Method = ")) method = val(ln);
            } else {
                if (ln.startsWith("Name:")) path = val2(ln);
                else if (ln.startsWith("Size:")) size = val2(ln);
                else if (ln.startsWith("Packed size:")) packed = val2(ln);
                else if (ln.startsWith("Attributes:")) attrs = val2(ln);
                else if (ln.startsWith("mtime:")) mod = val2(ln);
                else if (ln.startsWith("Type:")) type = val2(ln);
                else if (ln.startsWith("Details:")) type = val2(ln);
            }
        }
        if (type != null && r.archiveType.length() == 0
                && !"File".equalsIgnoreCase(type) && !"Directory".equalsIgnoreCase(type)) {
            r.archiveType = type;
        }
        if (method != null && r.method.length() == 0 && r.archiveType.length() > 0) {
            r.method = method;
        }
        if (path == null) return;
        boolean isDir;
        if (sevenZip) {
            isDir = (attrs != null && attrs.startsWith("D"))
                    || (path.endsWith("/") && (size == null || "0".equals(size)));
        } else {
            isDir = "Directory".equalsIgnoreCase(type)
                    || (attrs != null && attrs.startsWith("d"));
        }
        // 7zz -slt 的第一个块是压缩包自身信息（含 Type=），跳过
        if (sevenZip && type != null && !"Directory".equalsIgnoreCase(type)) return;

        Entry e = new Entry();
        e.path = normalize(path);
        if (e.path.length() == 0) return;
        e.isDir = isDir;
        e.size = parseL(size);
        e.packed = parseL(packed);
        e.modified = mod;
        r.entries.add(e);
        if (isDir) r.dirCount++;
        else { r.fileCount++; r.totalSize += e.size; r.totalPacked += e.packed; }
    }

    private static String normalize(String p) {
        p = p.replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static void sort(List<Entry> es) {
        Collections.sort(es, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                int c = a.dirPart().compareToIgnoreCase(b.dirPart());
                if (c != 0) return c;
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.name().compareToIgnoreCase(b.name());
            }
        });
    }

    private static String val(String ln) {   // "Key = value"
        return ln.substring(ln.indexOf('=') + 1).trim();
    }

    private static String val2(String ln) {  // "Key:value"
        return ln.substring(ln.indexOf(':') + 1).trim();
    }

    private static long parseL(String s) {
        if (s == null || s.length() == 0) return 0;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
