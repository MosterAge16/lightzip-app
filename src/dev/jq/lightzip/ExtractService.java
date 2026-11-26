package dev.jq.lightzip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前台解压服务：Activity 切后台/被杀后解压继续，通知栏显示进度、可取消。
 * 与 Activity 同进程，进度/输出/结果通过静态状态 + 轮询交换。
 */
public class ExtractService extends Service {

    static final String ACTION_RUN = "dev.jq.lightzip.action.RUN";
    static final String ACTION_CANCEL = "dev.jq.lightzip.action.CANCEL";
    static final String CH = "extract";
    static final int NOTIF_ID = 42;
    static final String EXTRA_ARGS = "args";
    static final String EXTRA_OUT = "out";

    static final int RES_NONE = 0, RES_OK = 1, RES_WARN = 2, RES_ERR = 3,
            RES_CANCEL = 4, RES_PWD = 5, RES_SPACE = 6;

    // —— 同进程共享状态（Activity 每 400ms 轮询）——
    static volatile boolean running;
    static volatile int percent = -1;      // -1 = 未知
    static volatile int doneFiles = 0;
    static volatile int resultToken = 0;   // 每次出新结果 +1
    static volatile int resultCode = RES_NONE;
    static volatile String resultDetail = "";
    static volatile String resultOutPath;  // RES_OK/WARN 时用于"查看文件夹"
    static volatile Process proc;

    // 引擎输出缓冲（Activity 增量拉取显示）
    static final Object OUT_LOCK = new Object();
    static final StringBuilder OUT = new StringBuilder();

    static void outReset() { synchronized (OUT_LOCK) { OUT.setLength(0); } }

    static int outLen() { synchronized (OUT_LOCK) { return OUT.length(); } }

    static String outSince(int from) {
        synchronized (OUT_LOCK) {
            if (from >= OUT.length()) return null;
            return OUT.substring(from);
        }
    }

    static void outAppend(String s) {
        synchronized (OUT_LOCK) {
            OUT.append(s);
            if (OUT.length() > 30000) OUT.delete(0, OUT.length() - 20000);
        }
    }

    /** 启动一次解压（调用方需自行处理权限/空间预检） */
    static void startExtract(android.content.Context c, String[] args, File outDir) {
        Intent i = new Intent(c, ExtractService.class);
        i.setAction(ACTION_RUN);
        i.putExtra(EXTRA_ARGS, args);
        i.putExtra(EXTRA_OUT, outDir.getPath());
        outReset();
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void cancelCurrent() {
        Process p = proc;
        if (p != null) p.destroy();
    }

    Handler main = new Handler(Looper.getMainLooper());
    Pattern pctPat = Pattern.compile("^\\s*(\\d{1,3})%");
    volatile boolean cancelledFlag;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelCurrent();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        final String[] args = intent != null ? intent.getStringArrayExtra(EXTRA_ARGS) : null;
        final String outPath = intent != null ? intent.getStringExtra(EXTRA_OUT) : null;
        if (args == null || outPath == null) return START_NOT_STICKY;
        startNotif();
        running = true;
        cancelledFlag = false;
        percent = -1;
        doneFiles = 0;
        resultCode = RES_NONE;
        resultDetail = "";
        resultOutPath = null;
        new Thread(new Runnable() { public void run() { runExtract(args, outPath); } }).start();
        return START_NOT_STICKY;
    }

    void runExtract(String[] args, String outPath) {
        int exit = -1;
        List<String> out = new ArrayList<String>();
        Process pr = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            try {
                pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            } catch (Throwable ignore) {}
            pb.environment().put("TMPDIR", getCacheDir().getPath());
            pb.environment().put("HOME", getCacheDir().getPath());
            outAppend("$ " + MainActivity.shortCmd(args) + "\n");
            pr = pb.start();
            proc = pr;
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(pr.getInputStream(), "UTF-8"));
            long lastNotif = 0;
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
                outAppend(line + "\n");
                Matcher m = pctPat.matcher(line);
                if (m.find()) {
                    try { percent = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignore) {}
                }
                if (line.startsWith("Files: ")) {
                    try { doneFiles = Integer.parseInt(line.substring(7).trim()); } catch (NumberFormatException ignore) {}
                }
                long now = System.currentTimeMillis();
                if (now - lastNotif > 400) {
                    lastNotif = now;
                    main.post(new Runnable() { public void run() { updateNotif(); } });
                }
            }
            exit = pr.waitFor();
        } catch (Exception e) {
            outAppend("[异常] " + e + "\n");
        } finally {
            proc = null;
            if (pr != null) pr.destroy();
        }
        final String all = join(out);
        final boolean wasCancelled = cancelledFlag;

        int code;
        String detail;
        if (wasCancelled) {
            code = RES_CANCEL;
            detail = "已取消";
        } else if (Engine.wrongPassword(exit, all)) {
            code = RES_PWD;
            detail = "密码不对或缺少密码（退出码 " + exit + "）";
        } else if (Engine.noSpace(exit, all)) {
            code = RES_SPACE;
            detail = "存储空间不足，请清理后重试";
        } else if (exit == Engine.Z7_OK || exit == Engine.R_OK) {
            code = RES_OK;
            detail = "✅ 已解压 " + doneFiles + " 个文件 → " + outPath;
            resultOutPath = outPath;
        } else if (exit == Engine.Z7_WARNING || exit == Engine.R_WARNING) {
            code = RES_WARN;
            detail = "⚠ 解压完成但有警告（个别文件可能未解出）→ " + outPath;
            resultOutPath = outPath;
        } else if (Engine.corrupt(exit, all)) {
            code = RES_ERR;
            detail = "压缩包损坏或数据错误（CRC）。若文件是分卷/传输中断，请重新获取";
        } else {
            code = RES_ERR;
            detail = "解压失败（退出码 " + exit + "），详见应用内输出";
        }
        resultCode = code;
        resultDetail = detail;
        resultToken++;
        if (!wasCancelled && code == RES_OK) percent = 100;
        main.post(new Runnable() {
            public void run() {
                postFinalNotif(resultCode, resultDetail);
                running = false;
                stopForeground(true);
                stopSelf();
            }
        });
    }

    static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    // ---------- 通知 ----------

    void startNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "解压进度", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        updateNotif();
    }

    Notification.Builder bld() {
        return Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
    }

    void updateNotif() {
        int pct = percent;
        Notification.Builder b = bld()
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentTitle("轻解压正在解压");
        if (pct >= 0) {
            b.setProgress(100, pct, false);
            b.setContentText(pct + "% · 已解压 " + doneFiles + " 个文件");
        } else {
            b.setProgress(0, 0, true);
            b.setContentText("准备中…");
        }
        b.addAction(0, "取消", cancelPi());
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    void postFinalNotif(int code, String detail) {
        Notification.Builder b = bld()
                .setSmallIcon(code == RES_OK || code == RES_WARN
                        ? android.R.drawable.stat_sys_download_done
                        : android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentTitle("轻解压")
                .setContentText(detail);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID + 1, b.build());
    }

    PendingIntent cancelPi() {
        Intent i = new Intent(this, ExtractService.class);
        i.setAction(ACTION_CANCEL);
        int fl = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getService(this, 1, i, fl);
    }
}
