package dev.jq.lightzip;

import android.content.Context;
import java.io.File;

/** 定位并运行两个官方引擎 */
public final class Engine {

    /** 7zz 退出码 */
    public static final int Z7_OK = 0, Z7_WARNING = 1, Z7_FATAL = 2;
    /** unrar 退出码（errhnd.hpp） */
    public static final int R_OK = 0, R_WARNING = 1, R_FATAL = 2, R_CRC = 3,
            R_WRITE = 5, R_OPEN = 6, R_CREATE = 7, R_MEMORY = 8, R_BADPWD = 11,
            R_READ = 12, R_BADARC = 13;

    private Engine() {}

    public static String bin7z(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/lib7zz.so";
    }

    public static String binUnrar(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libunrar.so";
    }

    /** 归一化输出，便于分类错误 */
    public static String flatten(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    public static boolean wrongPassword(int exit, String output) {
        String o = flatten(output);
        if (o.contains("wrong password")) return true;
        if (o.contains("cannot open encrypted archive")) return true;
        if (o.contains("enter password") || o.contains("missing password")) return true;
        return exit == R_BADPWD;
    }

    public static boolean corrupt(int exit, String output) {
        String o = flatten(output);
        if (o.contains("crc fail") || o.contains("crc error") || o.contains("data error")) return true;
        if (o.contains("unexpected end of archive")) return true;
        if (o.contains("is not archive") || o.contains("can't open as archive")) return true;
        return exit == R_CRC || exit == R_BADARC;
    }

    public static boolean noSpace(int exit, String output) {
        String o = flatten(output);
        return o.contains("no space left") || o.contains("disk full");
    }
}
