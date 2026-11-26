package dev.jq.lightzip;

/** 压缩包内条目 */
public class Entry {
    public String path;      // 包内路径，'/' 分隔
    public long size;        // 解压后大小
    public long packed;      // 压缩后大小
    public boolean isDir;
    public String modified;  // 可空
    public boolean selected = true;

    public String name() {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    public String dirPart() {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }
}
