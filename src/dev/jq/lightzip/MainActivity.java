package dev.jq.lightzip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final int REQ_PICK = 1;

    ExecutorService pool;
    Process running;          // 当前引擎进程（列表等轻操作）
    volatile boolean busy;    // 一次只允许一个引擎操作（UI 写、后台读）
    boolean dark;             // 暗色主题
    Handler timer;            // 服务进度轮询
    int lastOutLen;           // 服务输出已读取位置
    static int sLastResultToken;   // 已消费的服务结果（跨 Activity 重建）

    int cLogText() { return dark ? Color.rgb(204, 204, 204) : Color.rgb(60, 60, 60); }
    int cCardBg() { return dark ? Color.rgb(32, 36, 42) : Color.rgb(240, 244, 248); }
    int cGray() { return dark ? Color.rgb(150, 150, 150) : Color.GRAY; }
    int cOk() { return dark ? Color.rgb(102, 187, 106) : Color.rgb(0, 110, 0); }
    int cErr() { return dark ? Color.rgb(239, 83, 80) : Color.rgb(190, 40, 40); }
    int cLink() { return dark ? Color.rgb(100, 181, 246) : Color.rgb(30, 100, 200); }
    int cTitle() { return dark ? Color.WHITE : Color.rgb(20, 20, 20); }

    File workingFile;         // 当前操作的文件（原文件或分离后的内层文件）
    String origName;          // 最初选中的文件名（用于命名解压文件夹）
    int sigIdx = -1;          // 真实格式（Scanner.SIGS 下标）
    List<Entry> entries;      // 包内条目
    ListParser.Result listResult;
    String password = "";     // 当前使用的密码（内存态，不落盘）
    Scanner.VolumeSet vols;   // 分卷信息（isSet=false 为单文件）
    List<Integer> viewIdx;    // 过滤后的显示索引（null=全部）
    String baseDir;           // 解压位置（目录，经文件夹选择器或默认值）

    // UI
    TextView fileCard, statusLine, targetLabel, pctText, logView, stepView, outPathView;
    ScrollView logScroll;
    EditText pwd, filterBox;
    CheckBox sameName;
    android.widget.RadioGroup modeGroup;
    Button btnList, btnExtract, btnToggleAll, btnCancel, btnScan, btnDetach, btnDiag;
    boolean logExpanded;

    // 四步进度：0扫描 1分离 2列表 3解压
    static final String[] STEP_NAMES = {"深度扫描", "分离内嵌", "加载列表", "解压"};
    boolean[] steps = new boolean[4];
    ProgressBar progress;
    ListView list;
    EntryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashReporter();
        // 暗色主题：跟随系统 DayNight（uiMode 变化会重建 Activity）
        int night = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        dark = night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        try {
            pool = Executors.newSingleThreadExecutor();
            timer = new Handler();
            buildUi();
            loadPrefs();
            refreshTarget();
            showCrashIfAny();
            Intent i = getIntent();
            if (i != null) handleViewIntent(i);
        } catch (Throwable e) {
            // 兜底：初始化失败也要让 App 打得开，展示崩溃信息供复制
            fallbackUi(e);
        }
    }

    /** 最小应急界面：崩溃信息 + 复制按钮，保证不再出现"打不开" */
    void fallbackUi(Throwable e) {
        try {
            String body = "初始化失败：\n" + stackOf(e) + "\n\n上次崩溃：\n";
            try {
                java.io.File base = getExternalFilesDir(null);
                java.io.File f = new File(base != null ? base : getCacheDir(), "crash.txt");
                if (f.isFile()) body += new String(readAll(f), "UTF-8");
            } catch (Throwable ignore) {}
            TextView tv = new TextView(this);
            tv.setTextSize(11);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setText(body);
            ScrollView sc = new ScrollView(this);
            sc.addView(tv);
            setContentView(sc);
            final String txt = body;
            android.app.AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("App 初始化异常")
                    .setMessage("点确定可复制全部信息")
                    .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface dd, int w) {
                            try {
                                android.content.ClipboardManager cm =
                                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", txt));
                                toast("已复制");
                            } catch (Throwable ignore) {}
                        }
                    })
                    .create();
            d.show();
        } catch (Throwable ignore) {}
    }

    static String stackOf(Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /** 崩溃时把堆栈写到外部缓存 + 尽量写到 Download，下次启动弹窗展示 */
    void installCrashReporter() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    String txt = "轻解压崩溃 Android " + android.os.Build.VERSION.RELEASE
                            + " thread=" + t.getName() + "\n\n" + stackOf(e);
                    byte[] b = txt.getBytes("UTF-8");
                    java.io.File base = getExternalFilesDir(null);
                    if (base == null) base = getCacheDir();
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(
                            new File(base, "crash.txt"));
                    fo.write(b);
                    fo.close();
                    // 尽量放一份到 Download，方便文件管理器/电脑直接取（失败无所谓）
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 30
                                && Environment.isExternalStorageManager()) {
                            java.io.File pub = new File(
                                    Environment.getExternalStoragePublicDirectory(
                                            Environment.DIRECTORY_DOWNLOADS),
                                    "lightzip-crash.txt");
                            java.io.FileOutputStream fo2 = new java.io.FileOutputStream(pub);
                            fo2.write(b);
                            fo2.close();
                        }
                    } catch (Throwable ignore) {}
                } catch (Throwable ignore) {}
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    void showCrashIfAny() {
        try {
            java.io.File base = getExternalFilesDir(null);
            final java.io.File f = new File(base != null ? base : getCacheDir(), "crash.txt");
            if (!f.isFile()) return;
            final String txt = new String(readAll(f), "UTF-8");
            TextView tv = new TextView(this);
            tv.setTextSize(11);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setText(txt);
            ScrollView sc = new ScrollView(this);
            sc.addView(tv);
            new AlertDialog.Builder(this)
                    .setTitle("检测到上次的崩溃记录")
                    .setView(sc)
                    .setPositiveButton("复制并清除", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            try {
                                android.content.ClipboardManager cm =
                                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", txt));
                                toast("崩溃日志已复制");
                            } catch (Exception ignore) {}
                            f.delete();
                        }
                    })
                    .setNegativeButton("稍后再说", null)
                    .show();
        } catch (Throwable ignore) {}
    }

    static byte[] readAll(File f) throws Exception {
        java.io.FileInputStream fi = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            fi.close();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    /** pool 关闭后仍可能被生命周期尾巴上的链条调用，这里兜底防崩溃 */
    void exec(Runnable r) {
        try {
            pool.execute(r);
        } catch (Exception ignore) {}
    }

    void handleViewIntent(Intent i) {
        String action = i.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = i.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            Object extra = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) uri = (Uri) extra;
        } else {
            return;
        }
        if (uri == null) return;
        log("\n[" + (Intent.ACTION_SEND.equals(action) ? "分享进入" : "打开方式") + "] " + uri + "\n");
        final Uri fu = uri;
        exec(new Runnable() { public void run() { openUri(fu); } });
    }

    // ================= UI =================

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        int p = dp(10);
        root.setPadding(p, p, p, p);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("轻解压");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(cTitle());
        titleRow.addView(title);
        TextView ver = new TextView(this);
        ver.setText("  v1.2 · 7-Zip 26.06 + unrar 7.3");
        ver.setTextSize(11);
        ver.setTextColor(cGray());
        titleRow.addView(ver);

        View spacer = new View(this);
        titleRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        titleRow.addView(smallLink("展开日志", new VL() { public void v() { toggleLog(); } }));
        titleRow.addView(smallLink("复制日志", new VL() { public void v() { copyLog(); } }));
        titleRow.addView(smallLink("关于", new VL() { public void v() { about(); } }));
        root.addView(titleRow);

        fileCard = new TextView(this);
        fileCard.setTextSize(12);
        fileCard.setBackgroundColor(cCardBg());
        fileCard.setPadding(dp(8), dp(6), dp(8), dp(6));
        fileCard.setText("未选择文件。可以从文件管理器/QQ/微信「打开方式」选择「轻解压」，或在下面挑选文件。");
        root.addView(fileCard, row());

        statusLine = new TextView(this);
        statusLine.setTextSize(13);
        statusLine.setTypeface(Typeface.DEFAULT_BOLD);
        statusLine.setVisibility(View.GONE);
        root.addView(statusLine, row());

        root.addView(btn("选择压缩包…", new VL() { public void v() { pick(); } }), row());

        pwd = new EditText(this);
        pwd.setHint("密码（可空；密码不对时会自动询问）");
        pwd.setTextSize(14);
        root.addView(pwd, row());

        // 解压位置：点击进入系统文件夹选择器，不再手填路径
        LinearLayout outRow = new LinearLayout(this);
        outRow.setOrientation(LinearLayout.HORIZONTAL);
        outRow.setGravity(Gravity.CENTER_VERTICAL);
        outPathView = new TextView(this);
        outPathView.setTextSize(13);
        outPathView.setSingleLine(true);
        outPathView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickTree(); }
        });
        LinearLayout.LayoutParams opv = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        outRow.addView(outPathView, opv);
        outRow.addView(btn("选择文件夹", new VL() { public void v() { pickTree(); } }));
        root.addView(outRow, row());

        sameName = new CheckBox(this);
        sameName.setText("解压到以压缩包命名的文件夹");
        sameName.setTextSize(13);
        sameName.setChecked(true);
        root.addView(sameName, row());

        LinearLayout mr = new LinearLayout(this);
        mr.setOrientation(LinearLayout.HORIZONTAL);
        mr.setGravity(Gravity.CENTER_VERTICAL);
        TextView ml = new TextView(this);
        ml.setText("重名文件:");
        ml.setTextSize(13);
        mr.addView(ml);
        modeGroup = new android.widget.RadioGroup(this);
        modeGroup.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        String[] modes = {"覆盖", "跳过", "重命名"};
        for (int i = 0; i < modes.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(modes[i]);
            rb.setTextSize(13);
            rb.setId(101 + i);
            modeGroup.addView(rb);
        }
        modeGroup.check(101);   // 默认覆盖，loadPrefs 会修正
        mr.addView(modeGroup);
        root.addView(mr, row());

        targetLabel = new TextView(this);
        targetLabel.setTextSize(12);
        targetLabel.setTextColor(cOk());
        // 点路径 → 在文件管理器中打开该文件夹
        targetLabel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openTargetDir(); }
        });
        root.addView(targetLabel, row());

        sameName.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) { refreshTarget(); }
        });

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        btnList = btn("加载列表", new VL() { public void v() { if (ensureFile()) runList(); } });
        r1.addView(btnList, grow());
        btnExtract = btn("解压", new VL() { public void v() { if (ensureFile()) extract(); } });
        r1.addView(btnExtract, grow());
        btnToggleAll = btn("全不选", new VL() { public void v() { toggleAll(); } });
        r1.addView(btnToggleAll, grow());
        root.addView(r1, row());

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        btnScan = btn("深度扫描", new VL() { public void v() { if (ensureFile()) scan(); } });
        r2.addView(btnScan, grow());
        btnDetach = btn("分离内嵌", new VL() { public void v() { if (ensureFile()) detach(); } });
        r2.addView(btnDetach, grow());
        btnDiag = btn("文件诊断", new VL() { public void v() { if (ensureFile()) diag(); } });
        r2.addView(btnDiag, grow());
        root.addView(r2, row());

        // 步骤完成指示条：每完成一步点亮对应项
        stepView = new TextView(this);
        stepView.setTextSize(13);
        stepView.setTypeface(Typeface.DEFAULT_BOLD);
        stepView.setTextColor(cGray());
        refreshSteps();
        root.addView(stepView, row());

        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        progress.setVisibility(View.GONE);
        pr.addView(progress, plp);
        pctText = new TextView(this);
        pctText.setTextSize(12);
        pctText.setPadding(dp(6), 0, dp(6), 0);
        pctText.setVisibility(View.GONE);
        pr.addView(pctText);
        btnCancel = btn("取消", new VL() { public void v() { cancelRun(); } });
        btnCancel.setVisibility(View.GONE);
        pr.addView(btnCancel);
        root.addView(pr, row());

        filterBox = new EditText(this);
        filterBox.setHint("搜索包内文件（可留空）");
        filterBox.setSingleLine(true);
        filterBox.setTextSize(13);
        filterBox.setVisibility(View.GONE);
        filterBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                rebuildViewIdx();
                adapter.notifyDataSetChanged();
            }
        });
        root.addView(filterBox, row());

        list = new ListView(this);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setVisibility(View.GONE);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextColor(cLogText());
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150), 0f);
        root.addView(logScroll, llp);

        setContentView(root);
    }

    class VH {
        CheckBox cb;
        TextView t1, t2;
    }

    class EntryAdapter extends BaseAdapter {
        public int getCount() { return shownCount(); }
        public Object getItem(int pos) { return entries.get(mappedPos(pos)); }
        public long getItemId(int pos) { return pos; }

        public View getView(final int listPos, View conv, ViewGroup parent) {
            final int pos = mappedPos(listPos);
            VH h;
            if (conv == null) {
                LinearLayout box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                h = new VH();
                h.cb = new CheckBox(MainActivity.this);
                h.cb.setClickable(false);
                box.addView(h.cb);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                h.t1 = new TextView(MainActivity.this);
                h.t1.setTextSize(13);
                h.t2 = new TextView(MainActivity.this);
                h.t2.setTextSize(10);
                h.t2.setTextColor(cGray());
                col.addView(h.t1);
                col.addView(h.t2);
                box.addView(col, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                conv = box;
                conv.setTag(h);
            } else {
                h = (VH) conv.getTag();
            }
            // 每次绑定都要重设监听（ListView 会复用行视图，只设一次会捕获过期位置）
            conv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (entries == null || pos < 0 || pos >= entries.size()) return;
                    Entry e = entries.get(pos);
                    e.selected = !e.selected;
                    adapter.notifyDataSetChanged();
                    updateToggleLabel();
                }
            });
            Entry e = entries.get(pos);
            h.t1.setText((e.isDir ? "📁 " : "") + e.name());
            StringBuilder sb = new StringBuilder();
            if (e.dirPart().length() > 0) sb.append(e.dirPart()).append('/').append(' ');
            if (!e.isDir) {
                sb.append(fmtSize(e.size));
                if (e.packed > 0) sb.append(" ← ").append(fmtSize(e.packed));
                if (e.modified != null && e.modified.length() > 0) sb.append("  ").append(e.modified);
            }
            h.t2.setText(sb);
            h.cb.setChecked(e.selected);
            return conv;
        }
    }

    int shownCount() {
        if (entries == null) return 0;
        return viewIdx == null ? entries.size() : viewIdx.size();
    }

    int mappedPos(int listPos) {
        return viewIdx == null ? listPos : viewIdx.get(listPos);
    }

    void rebuildViewIdx() {
        if (entries == null || filterBox == null) { viewIdx = null; return; }
        String q = filterBox.getText().toString().trim().toLowerCase();
        if (q.length() == 0) { viewIdx = null; return; }
        List<Integer> v = new ArrayList<Integer>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).path.toLowerCase().contains(q)) v.add(i);
        }
        viewIdx = v;
    }

    interface VL { void v(); }

    Button btn(String text, final VL vl) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { vl.v(); }
        });
        return b;
    }

    LinearLayout.LayoutParams row() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    LinearLayout.LayoutParams grow() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    TextView smallLink(String text, final VL vl) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(cLink());
        t.setPadding(dp(8), dp(4), dp(4), dp(4));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { vl.v(); }
        });
        return t;
    }

    /** 日志区在紧凑（固定高）与全屏（隐藏列表）之间切换 */
    void toggleLog() {
        logExpanded = !logExpanded;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) logScroll.getLayoutParams();
        if (logExpanded) {
            lp.height = 0;
            lp.weight = 1f;
            list.setVisibility(View.GONE);
        } else {
            lp.height = dp(150);
            lp.weight = 0f;
            if (entries != null && !entries.isEmpty()) list.setVisibility(View.VISIBLE);
        }
        logScroll.setLayoutParams(lp);
    }

    void copyLog() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "lightzip-log", logView.getText()));
            toast("日志已复制到剪贴板");
        } catch (Exception e) {
            toast("复制失败: " + e);
        }
    }

    void about() {
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setPadding(dp(20), dp(10), dp(20), dp(10));
        tv.setText("轻解压 v1.1\n\n"
                + "基于官方解压引擎构建：\n"
                + "· 7-Zip 26.06（Igor Pavlov）——支持 7z/ZIP/TAR/GZIP/XZ/ZSTD/CAB 等格式解压。\n"
                + "  许可：LGPL-2.1 + BSD 3-Clause + BSD 2-Clause，含 unRAR 许可限制。\n"
                + "· unrar 7.3.2（Alexander Roshal）——RAR/RAR5 解压。\n"
                + "  unRAR 源码许可：仅可用于创建支持 RAR 解压的软件，不得用于重建 RAR 压缩算法。\n\n"
                + "本应用只提供界面与调用调度，解压能力全部来自上述引擎，"
                + "并附加：伪装文件深度扫描与分离、分卷识别、解压位置管理等。");
        ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setView(sc)
                .setPositiveButton("关闭", null)
                .show();
    }

    void markStep(int i) {
        steps[i] = true;
        refreshSteps();
    }

    /** 换文件时重置：keepScanDetach=true 保留扫描/分离的完成状态（分离后的内层文件沿用） */
    void resetSteps(boolean keepScanDetach) {
        steps = keepScanDetach
                ? new boolean[]{true, true, false, false}
                : new boolean[4];
        refreshSteps();
    }

    void refreshSteps() {
        if (stepView == null) return;
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append("    ");
            if (steps[i]) { sb.append("✅ ").append(STEP_NAMES[i]); any = true; }
            else sb.append(STEP_NAMES[i]);
        }
        stepView.setTextColor(any ? cOk() : cGray());
        stepView.setText(sb);
    }

    // ================= 偏好 / 目标目录 =================

    void loadPrefs() {
        android.content.SharedPreferences sp = getSharedPreferences("lightzip", 0);
        baseDir = sp.getString("base_dir", defaultBase());
        sameName.setChecked(sp.getBoolean("same_name", true));
        String m = sp.getString("overwrite_mode", "overwrite");
        modeGroup.check("skip".equals(m) ? 102 : "rename".equals(m) ? 103 : 101);
        if (outPathView != null) outPathView.setText(baseDir);
    }

    void savePrefs() {
        android.content.SharedPreferences.Editor sp =
                getSharedPreferences("lightzip", 0).edit();
        sp.putString("base_dir", baseDir);
        sp.putBoolean("same_name", sameName.isChecked());
        int cid = modeGroup.getCheckedRadioButtonId();
        sp.putString("overwrite_mode", cid == 102 ? "skip" : cid == 103 ? "rename" : "overwrite");
        sp.apply();
    }

    String defaultBase() {
        File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return d != null ? d.getPath() : getCacheDir().getPath();
    }

    String sanitizeName(String s) {
        s = s == null ? "" : s.trim();
        if (s.length() == 0) return "extract";
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.length() == 0 ? "extract" : s;
    }

    String archiveBaseName() {
        String n = origName != null ? origName
                : (workingFile != null ? workingFile.getName() : null);
        if (n == null || n.length() == 0) return "extract";
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    File targetDir() {
        String base = baseDir != null ? baseDir.trim() : "";
        if (base.length() == 0) base = defaultBase();
        File dir = new File(base);
        if (sameName.isChecked()) dir = new File(dir, sanitizeName(archiveBaseName()));
        return dir;
    }

    void refreshTarget() {
        if (targetLabel == null) return;
        targetLabel.setText("解压位置: " + targetDir().getPath());
    }

    boolean ensureStorage() {
        if (android.os.Build.VERSION.SDK_INT >= 30
                && !Environment.isExternalStorageManager()) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignore) {}
            }
            toast("请在设置里允许「所有文件访问」，返回后重试");
            return false;
        }
        File dir = targetDir();
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            showStatus("无法创建目录: " + dir.getPath(), true);
            return false;
        }
        return true;
    }

    // ================= 选文件 / 打开 =================

    void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    static final int REQ_TREE = 2;

    /** 系统文件夹选择器 */
    void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addCategory(Intent.CATEGORY_DEFAULT);
        // 尽量让选择器停在当前目录
        try {
            if (baseDir != null && baseDir.startsWith("/storage/emulated/0/")) {
                String rel = baseDir.substring("/storage/emulated/0/".length());
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents", "primary:" + rel));
            } else if (baseDir != null && baseDir.equals("/storage/emulated/0")) {
                i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents", "primary:"));
            }
        } catch (Throwable ignore) {}
        startActivityForResult(i, REQ_TREE);
    }

    /** SAF 目录树 URI → 真实路径（支持主存储与外置存储卡卷） */
    String treeToPath(Uri uri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(uri);
            if (!id.contains(":")) return null;
            String vol = id.substring(0, id.indexOf(':'));
            String rel = id.substring(id.indexOf(':') + 1);
            if (rel.startsWith("/")) rel = rel.substring(1);
            if ("primary".equals(vol) || "home".equals(vol) || "downloads".equals(vol)) {
                return rel.length() == 0
                        ? "/storage/emulated/0"
                        : "/storage/emulated/0/" + rel;
            }
            if (vol.matches("[0-9A-Za-z\\-]{4,}")) {   // 外置存储卡卷 ID
                return rel.length() == 0
                        ? "/storage/" + vol
                        : "/storage/" + vol + "/" + rel;
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK && data.getData() != null) {
            final Uri uri = data.getData();
            exec(new Runnable() { public void run() { openUri(uri); } });
        } else if (requestCode == REQ_TREE && data.getData() != null) {
            String path = treeToPath(data.getData());
            if (path == null) {
                toast("该位置不受支持，请选手机存储里的文件夹");
                return;
            }
            baseDir = path;
            outPathView.setText(baseDir);
            refreshTarget();
            savePrefs();
            toast("解压位置已选择");
        }
    }

    /** content URI → 真实路径（仅主存储）或复制到缓存 */
    void openUri(Uri uri) {
        try {
            uiBusyStart("读取文件…");
            File f = resolveRealPath(uri);
            String name = queryName(uri);
            if (f == null) {
                f = new File(getCacheDir(), sanitizeName(name));
                copyWithProgress(uri, f);
            }
            uiBusyEnd();
            if (f == null || !f.isFile()) {
                ui(new Runnable() { public void run() { toast("读取文件失败"); } });
                return;
            }
            // SAF 挑选方式拿不到其余分卷，提前告知
            Scanner.VolumeSet vset = Scanner.detectVolumes(f);
            if (vset.isSet && !vset.complete) {
                uiLog("⚠ 检测到这是分卷之一，但其余分卷无法获取。\n"
                        + "请用文件管理器「打开方式→轻解压」进入（分卷需在同一目录）。\n");
            }
            final File ff = f;
            ui(new Runnable() { public void run() { loadArchive(ff, name, false); } });
        } catch (Throwable e) {
            uiBusyEnd();
            uiLog("[读取异常] " + e + "\n");
        }
    }

    /** 仅支持 content://com.android.externalstorage.documents/document/primary:... */
    File resolveRealPath(Uri uri) {
        try {
            if (!"com.android.externalstorage.documents".equals(uri.getAuthority()))
                return null;
            String doc = DocumentsContract.getDocumentId(uri);
            if (!doc.startsWith("primary:")) return null;
            String rel = doc.substring("primary:".length());
            File f = new File("/storage/emulated/0", rel);
            return f.isFile() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    void copyWithProgress(Uri uri, File dst) {
        InputStream in = null;
        FileOutputStream fo = null;
        try {
            in = getContentResolver().openInputStream(uri);
            fo = new FileOutputStream(dst);
            byte[] buf = new byte[1 << 16];
            long total = 0, step = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                total += n;
                step += n;
                if (step >= (32 << 20)) {
                    step = 0;
                    uiLog("…已复制 " + fmtSize(total) + "\n");
                }
            }
        } catch (Exception e) {
            uiLog("复制失败: " + e + "\n");
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) {}
            try { if (fo != null) fo.close(); } catch (Exception ignore) {}
        }
    }

    String queryName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String s = c.getString(i);
                    if (s != null && s.length() > 0) return s;
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
        return "archive.bin";
    }

    /** 载入并自动拉取列表（UI 线程调用）。keepSteps=true 保留扫描/分离完成标记 */
    void loadArchive(File f, String name, boolean keepSteps) {
        try {
            workingFile = f;
            origName = name;
            sigIdx = -1;
            entries = null;
            listResult = null;
            vols = null;
            viewIdx = null;
            resetSteps(keepSteps);
            adapter.notifyDataSetChanged();
            list.setVisibility(View.GONE);
            if (filterBox != null) {
                filterBox.getText().clear();   // 触发 watcher → rebuildViewIdx
                filterBox.setVisibility(View.GONE);
            }
            showStatus(null, false);
            refreshTarget();
            log("\n=================================\n文件: " + name + " (" + fmtSize(f.length()) + ")\n");
            final File ff = f;
            exec(new Runnable() { public void run() { identify(ff); } });
        } catch (Throwable e) {
            uiLog("[载入异常] " + stackOf(e) + "\n");
        }
    }

    /** 后台：识别格式并决定下一步 */
    void identify(final File f) {
        final int sig = Scanner.signatureOf(f);
        ui(new Runnable() {
            public void run() {
                try {
                    sigIdx = sig;
                    vols = Scanner.detectVolumes(f);
                    String sigName = sig >= 0 ? Scanner.SIG_NAMES[sig] : "未知格式";
                    StringBuilder fc = new StringBuilder();
                    fc.append(origName).append("  (").append(fmtSize(f.length()))
                            .append(")\n真实格式: ").append(sigName);
                    if (vols.isSet) {
                        fc.append("\n分卷: ").append(vols.parts.length).append(" 个，从 ")
                                .append(f.getName()).append(" 解");
                    }
                    fileCard.setText(fc);
                    if (vols.isSet && !vols.complete) {
                        showStatus("分卷不完整：缺少 " + vols.missing
                                + "。请把全部分卷放在同一目录后再试", true);
                        return;
                    }
                    if (sig >= 0 && sig <= 8) {
                        preCheck7z(f);
                    } else {
                        showStatus("这不是标准压缩包（" + sigName
                                + "）。如果是伪装文件，点「深度扫描」找内嵌压缩包。", true);
                    }
                } catch (Throwable e) {
                    uiLog("[识别异常] " + e + "\n");
                }
            }
        });
    }

    /** 7z 截断预检 → 通过后加载列表 */
    void preCheck7z(final File f) {
        exec(new Runnable() {
            public void run() {
                final long[] ck = Scanner.check7z(f);
                if (ck == null) {
                    ui(new Runnable() { public void run() { runList(); } });
                    return;
                }
                if (ck[0] > ck[1]) {
                    ui(new Runnable() { public void run() {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("文件可能不完整")
                                .setMessage("7z 头显示期望 " + fmtSize(ck[0])
                                        + "，实际只有 " + fmtSize(ck[1])
                                        + "（缺 " + fmtSize(ck[0] - ck[1])
                                        + "）。传输中断的包会解压失败。\n仍要尝试吗？")
                                .setPositiveButton("仍要尝试", new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        exec(new Runnable() {
                                            public void run() { runListBg(); }
                                        });
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }});
                } else {
                    ui(new Runnable() { public void run() { runList(); } });
                }
            }
        });
    }

    boolean ensureFile() {
        if (workingFile == null) {
            toast("请先选择压缩包");
            return false;
        }
        return true;
    }

    // ================= 列表 =================

    void runList() {
        password = pwd.getText().toString();   // UI 线程同步，后台只用 password 字段
        exec(new Runnable() { public void run() { runListBg(); } });
    }

    void runListBg() {
        if (busy) { uiLog("[忙碌中，忽略]\n"); return; }
        uiBusyStart("读取列表…");
        try {
            List<String> out = runEngine(listCmd());
            final ListParser.Result r = ListParser.parse(is7zKind(), out);
            final int exit = lastExit;
            final String all = join(out);
            ui(new Runnable() {
                public void run() {
                    if (Engine.wrongPassword(exit, all)) {
                        askPassword(new VL() { public void v() { runList(); } });
                        return;
                    }
                    listResult = r;
                    entries = r.entries;
                    rebuildViewIdx();
                    adapter.notifyDataSetChanged();
                    if (entries.isEmpty()) {
                        list.setVisibility(View.GONE);
                        filterBox.setVisibility(View.GONE);
                        if (sigIdx < 0 || sigIdx > 8) {
                            showStatus("这不是标准压缩包（可能是伪装文件），请先「深度扫描」", true);
                        } else {
                            showStatus("列表为空或读取失败（退出码 " + exit + "），详见下方输出", true);
                        }
                    } else {
                        list.setVisibility(View.VISIBLE);
                        filterBox.setVisibility(View.VISIBLE);
                        updateToggleLabel();
                        markStep(2);   // 列表加载完成
                        showStatus(String.format("%s · %d 个文件 / %d 个目录 · 合计 %s（压缩后 %s）",
                                r.archiveType, r.fileCount, r.dirCount,
                                fmtSize(r.totalSize), fmtSize(r.totalPacked)), false);
                    }
                }
            });
        } catch (Throwable e) {
            uiLog("[列表异常] " + stackOf(e) + "\n");
            showStatus("读取列表出错（详见输出）", true);
        } finally {
            uiBusyEnd();
        }
    }

    boolean is7zKind() {
        // RAR 用 unrar，其余都交给 7zz
        return sigIdx != Scanner.IDX_RAR4 && sigIdx != Scanner.IDX_RAR5;
    }

    String[] listCmd() {
        String p = password;
        if (is7zKind()) {
            return new String[]{Engine.bin7z(this), "l", "-slt", "-y",
                    p.isEmpty() ? "-p" : "-p" + p, workingFile.getPath()};
        }
        List<String> a = new ArrayList<String>();
        a.add(Engine.binUnrar(this));
        a.add("vt");          // unrar 的技术列表是命令后缀 t，没有 -slt 开关
        a.add("-y");
        if (!p.isEmpty()) a.add("-p" + p);
        a.add(workingFile.getPath());
        return a.toArray(new String[0]);
    }

    void toggleAll() {
        if (entries == null || entries.isEmpty()) return;
        boolean to = false;
        for (Entry e : entries) if (e.selected) { to = true; break; }  // 有选中→全不选
        boolean target = !to;
        for (Entry e : entries) e.selected = target;
        adapter.notifyDataSetChanged();
        updateToggleLabel();
    }

    void updateToggleLabel() {
        if (entries == null) return;
        boolean any = false, all = true;
        for (Entry e : entries) {
            if (e.selected) any = true; else all = false;
        }
        btnToggleAll.setText(all ? "全不选" : "全选");
        btnExtract.setText(any && !all ? "解压选中" : "解压全部");
    }

    // ================= 解压 =================

    void extract() {
        if (!ensureStorage()) return;
        savePrefs();
        password = pwd.getText().toString();
        final File outDir = targetDir();
        final String[] args = buildExtractArgs(outDir);
        showStatus(null, false);
        // 空间预检：解压后总大小 + 5% 余量
        long need = listResult != null ? listResult.totalSize : 0;
        long avail = outDir.getUsableSpace();
        if (need > 0 && avail > 0 && avail < need + need / 20) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("磁盘空间可能不足")
                    .setMessage("解压约需 " + fmtSize(need) + "，目标位置可用 "
                            + fmtSize(avail) + "。\n仍要继续吗？（中途空间耗尽会解压失败）")
                    .setPositiveButton("仍要解压", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            startExtractService(args, outDir);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            startExtractService(args, outDir);
        }
    }

    void startExtractService(String[] args, File outDir) {
        // Android 13+ 需要通知权限才能在通知栏显示进度；拒绝也不影响解压本身
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2);
            }
        }
        ExtractService.startExtract(this, args, outDir);
        lastOutLen = 0;
        timer.removeCallbacks(poller);
        timer.postDelayed(poller, 300);
    }

    String[] buildExtractArgs(File outDir) {
        password = pwd.getText().toString();
        List<String> include = includePaths();
        String p = password;
        String mode = getSharedPreferences("lightzip", 0)
                .getString("overwrite_mode", "overwrite");
        String[] args;
        if (is7zKind()) {
            List<String> a = new ArrayList<String>();
            a.add(Engine.bin7z(this));
            a.add("x");
            if ("skip".equals(mode)) a.add("-aos");       // 跳过已存在
            else if ("rename".equals(mode)) a.add("-aou"); // 自动重命名
            a.add("-y");
            a.add(p.isEmpty() ? "-p" : "-p" + p);          // v1.2 重构时曾丢失，导致 7z 解压永远报密码错
            a.add("-o" + outDir.getPath());
            a.add(workingFile.getPath());
            if (include.size() < entryFileCount()) a.addAll(include);
            args = a.toArray(new String[0]);
        } else {
            List<String> a = new ArrayList<String>();
            a.add(Engine.binUnrar(this));
            a.add("x");
            if ("skip".equals(mode)) {
                a.add("-o-");
            } else if ("rename".equals(mode)) {
                a.add("-or");
                a.add("-y");
            } else {
                a.add("-o+");
                a.add("-y");
            }
            if (!p.isEmpty()) a.add("-p" + p);
            a.add(workingFile.getPath());
            if (include.size() < entryFileCount()) {
                // RAR 存的名字可能用 '\' 分隔，两种形式都传，不匹配的会被忽略
                for (String s : include) {
                    a.add(s);
                    if (s.indexOf('/') >= 0) a.add(s.replace('/', '\\'));
                }
            }
            a.add(outDir.getPath() + "/");
            args = a.toArray(new String[0]);
        }
        return args;
    }

    /** 选中的文件路径清单；选中的目录展开为其下全部文件 */
    List<String> includePaths() {
        List<String> out = new ArrayList<String>();
        if (entries == null) return out;
        Set<String> selDirs = new HashSet<String>();
        for (Entry e : entries) if (e.isDir && e.selected) selDirs.add(e.path);
        for (Entry e : entries) {
            if (e.isDir) continue;
            if (e.selected) { out.add(e.path); continue; }
            String d = e.dirPart();
            boolean covered = false;
            while (d.length() > 0) {
                if (selDirs.contains(d)) { covered = true; break; }
                int i = d.lastIndexOf('/');
                d = i < 0 ? "" : d.substring(0, i);
            }
            if (covered) out.add(e.path);
        }
        return out;
    }

    int entryFileCount() {
        int n = 0;
        if (entries != null) for (Entry e : entries) if (!e.isDir) n++;
        return n;
    }

    // 解压执行已迁移至 ExtractService（前台服务，切后台仍继续），结果由 poller 消费

    void consumeResult(int code, String detail, String outPath) {
        if (code == ExtractService.RES_PWD) {
            showStatus("密码不对或缺少密码", true);
            askPassword(new VL() { public void v() { extract(); } });
            return;
        }
        boolean err = code == ExtractService.RES_ERR
                || code == ExtractService.RES_CANCEL
                || code == ExtractService.RES_SPACE;
        if (code == ExtractService.RES_OK || code == ExtractService.RES_WARN) {
            markStep(3);   // 解压完成
        }
        showStatus(detail, err);
        if (outPath != null) offerViewDir(new File(outPath));
    }

    // ---------- 服务进度轮询（400ms） ----------

    Runnable poller = new Runnable() {
        public void run() {
            if (isDestroyed() || isFinishing()) return;
            boolean srv = ExtractService.running;
            btnList.setEnabled(!srv && !busy);
            btnExtract.setEnabled(!srv && !busy);
            btnToggleAll.setEnabled(!busy);
            boolean show = srv || busy;
            if (show) {
                progress.setVisibility(View.VISIBLE);
                btnCancel.setVisibility(View.VISIBLE);
                pctText.setVisibility(View.VISIBLE);
                if (busy) {
                    progress.setIndeterminate(true);
                    pctText.setText("…");
                } else {
                    progress.setIndeterminate(false);
                    progress.setMax(100);
                    int pct = ExtractService.percent;
                    progress.setProgress(pct < 0 ? 0 : pct);
                    pctText.setText(pct < 0 ? "…" : pct + "%");
                }
                String chunk = ExtractService.outSince(lastOutLen);
                lastOutLen = ExtractService.outLen();
                if (chunk != null) appendLog(chunk);
            } else {
                progress.setVisibility(View.GONE);
                progress.setIndeterminate(false);
                pctText.setVisibility(View.GONE);
                btnCancel.setVisibility(View.GONE);
            }
            int tok = ExtractService.resultToken;
            if (!srv && tok != sLastResultToken) {
                sLastResultToken = tok;
                consumeResult(ExtractService.resultCode,
                        ExtractService.resultDetail, ExtractService.resultOutPath);
            }
            timer.postDelayed(this, 400);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (timer != null) {
            timer.removeCallbacks(poller);
            timer.postDelayed(poller, 300);
        }
    }

    @Override
    protected void onPause() {
        if (timer != null) timer.removeCallbacks(poller);
        super.onPause();
    }

    // ================= 深度扫描 / 分离 / 诊断 =================

    void scan() {
        exec(new Runnable() {
            public void run() {
                if (busy) { uiLog("[忙碌中，请等当前操作结束]\n"); return; }
                uiBusyStart("深度扫描中…（大文件需数秒）");
                try {
                    uiLog("\n[深度扫描] " + workingFile.getName() + " …\n");
                    final Scanner.DeepResult r = Scanner.scanDeep(workingFile, new Scanner.Logger() {
                        public void log(String s) { uiLog(s + "\n"); }
                    });
                ui(new Runnable() {
                    public void run() {
                        markStep(0);   // 扫描完成（无论是否发现内嵌）
                        if (r.found.isEmpty()) {
                                uiLog("❌ 全文件未发现任何已知压缩格式签名。\n"
                                        + "可能是像素级图片化（压缩包被编进了图片像素），需要原工具还原。\n");
                                showStatus("未找到内嵌压缩包（详见输出）", true);
                            } else {
                                StringBuilder sb = new StringBuilder("发现:\n");
                                for (String s : r.found) sb.append("  ").append(s).append('\n');
                                uiLog(sb.toString());
                                if (r.type >= 0) {
                                    uiLog("→ 可点「分离内嵌」从 " + r.offset + " 字节处剥离\n");
                                    showStatus("✅ 扫描完成：发现内嵌 " + Scanner.SIG_NAMES[r.type]
                                            + "（@" + r.offset + "），点「分离内嵌」即可剥离", false);
                                } else {
                                    showStatus("扫描完成：只看到图片等格式，无内嵌压缩包", true);
                                }
                            }
                        }
                    });
                } finally {
                    uiBusyEnd();
                }
            }
        });
    }

    void detach() {
        exec(new Runnable() {
            public void run() {
                if (busy) { uiLog("[忙碌中，请等当前操作结束]\n"); return; }
                uiBusyStart("分离内嵌中…");
                try {
                    if (workingFile.getName().startsWith("inner_")) {
                        ui(new Runnable() { public void run() {
                            markStep(1);
                            showStatus("✅ 当前已是分离后的内层文件，直接点「加载列表」或「解压」", false);
                        }});
                        return;
                    }
                    uiLog("[分离] 扫描定位…\n");
                    final Scanner.DeepResult r = Scanner.scanDeep(workingFile, null);
                    if (r.type < 0 || r.offset <= 0) {
                        ui(new Runnable() { public void run() {
                            showStatus("❌ 未找到内嵌压缩包（先「深度扫描」看看）", true);
                        }});
                        return;
                    }
                    // 清理上一次分离留下的旧拷贝，避免缓存堆积
                    for (File old : getCacheDir().listFiles()) {
                        if (old.getName().startsWith("inner_")) old.delete();
                    }
                    File dst = new File(getCacheDir(), "inner_" + System.currentTimeMillis() + ".bin");
                    boolean ok = Scanner.detach(workingFile, r.offset, dst);
                    if (!ok) {
                        ui(new Runnable() { public void run() { toast("分离失败"); } });
                        return;
                    }
                    final long len = dst.length();
                    ui(new Runnable() { public void run() {
                        log("[分离] " + Scanner.SIG_NAMES[r.type] + " @" + r.offset
                                + " → " + dst.getName() + " (" + fmtSize(len) + ")\n");
                        showStatus("✅ 分离成功：内层 " + Scanner.SIG_NAMES[r.type] + " "
                                + fmtSize(len) + "，正在加载列表…", false);
                        loadArchive(dst, origName != null ? origName : dst.getName(), true);
                    }});
                } finally {
                    uiBusyEnd();
                }
            }
        });
    }

    void diag() {
        exec(new Runnable() {
            public void run() {
                if (busy) { uiLog("[忙碌中，请等当前操作结束]\n"); return; }
                uiBusyStart("诊断中…");
                try {
                    StringBuilder sb = new StringBuilder("\n===== 文件诊断 =====\n");
                    sb.append("文件: ").append(workingFile.getName()).append('\n');
                    sb.append("大小: ").append(workingFile.length()).append(" 字节\n");
                    int sig = Scanner.signatureOf(workingFile);
                    sb.append("头部签名: ").append(sig >= 0 ? Scanner.SIG_NAMES[sig] : "未知").append('\n');
                    long[] ck = Scanner.check7z(workingFile);
                    if (ck != null) {
                        sb.append("7z 结构: 加密头偏移 ").append(ck[2]).append(", 大小 ").append(ck[3])
                                .append(", 期望总大小 ").append(ck[0]).append('\n');
                        if (ck[0] > ck[1]) {
                            sb.append("判定: ❌ 文件不完整，缺 ").append(ck[0] - ck[1])
                                    .append(" 字节（").append(100 * (ck[0] - ck[1]) / ck[0]).append("%）\n");
                        } else if (ck[0] == ck[1]) {
                            sb.append("判定: ✅ 文件完整\n");
                        } else {
                            sb.append("判定: ⚠ 文件比期望多 ").append(ck[1] - ck[0]).append(" 字节\n");
                        }
                    }
                    sb.append("====================\n");
                    uiLog(sb.toString());
                } finally {
                    uiBusyEnd();
                }
            }
        });
    }

    // ================= 引擎执行 =================

    int lastExit = -1;
    boolean cancelled = false;

    /** 运行引擎，逐行收集输出并写入日志 */
    List<String> runEngine(String[] args) {
        List<String> out = new ArrayList<String>();
        Process pr = null;
        cancelled = false;
        lastExit = -1;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            try {
                pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            } catch (Throwable ignore) {}
            pb.environment().put("TMPDIR", getCacheDir().getPath());
            pb.environment().put("HOME", getCacheDir().getPath());
            uiLog("$ " + shortCmd(args) + "\n");
            pr = pb.start();
            running = pr;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pr.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
                uiLog(line + "\n");
            }
            lastExit = pr.waitFor();
            uiLog("[退出码 " + lastExit + "]\n");
            return out;
        } catch (Exception e) {
            uiLog("[异常] " + e + "\n");
            return out;
        } finally {
            running = null;
            if (pr != null) pr.destroy();
        }
    }

    void cancelRun() {
        if (ExtractService.running) {
            ExtractService.cancelCurrent();
            uiLog("[已请求取消，服务将结束进程]\n");
            return;
        }
        Process pr = running;
        if (pr != null) {
            cancelled = true;
            pr.destroy();
            uiLog("[用户取消]\n");
        }
    }

    // ================= 密码询问 =================

    void askPassword(final VL onOk) {
        ui(new Runnable() { public void run() {
            final EditText input = new EditText(MainActivity.this);
            input.setHint("输入密码");
            input.setText(password);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("需要密码")
                    .setView(input)
                    .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int w) {
                            // 此处已在 UI 线程，直接同步密码框，避免异步 setText 与重试操作竞态
                            password = input.getText().toString();
                            pwd.setText(password);
                            onOk.v();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }});
    }

    // ================= 其它 =================

    /** 点击"解压位置"：在文件管理器中打开目标文件夹（不存在则先创建） */
    void openTargetDir() {
        File dir = targetDir();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            File parent = dir.getParentFile();
            if (parent != null && parent.isDirectory()) dir = parent;
        }
        if (!offerViewDir(dir)) {
            toast("没有找到可用的文件管理器\n" + dir.getPath());
        }
    }

    /** 用系统文件管理器打开文件夹；成功返回 true */
    boolean offerViewDir(final File dir) {
        try {
            String path = dir.getCanonicalPath();
            String docId;
            String emu = "/storage/emulated/0";
            if (path.equals(emu)) {
                docId = "primary:";
            } else if (path.startsWith(emu + "/")) {
                docId = "primary:" + path.substring(emu.length() + 1);
            } else if (path.startsWith("/storage/") && path.indexOf('/', 9) > 0) {
                // 外置存储卡：/storage/<卷ID>/...
                String rest = path.substring("/storage/".length());
                int slash = rest.indexOf('/');
                docId = slash < 0 ? rest + ":" : rest.substring(0, slash) + ":"
                        + rest.substring(slash + 1);
            } else {
                return false;
            }
            Uri uri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", docId);
            startActivity(new Intent(Intent.ACTION_VIEW).setData(uri));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void uiBusyStart(final String msg) {
        ui(new Runnable() { public void run() {
            busy = true;
            setButtonsEnabled(false);
            if (msg != null) showStatus(msg, false);
        }});
    }

    void uiBusyEnd() {
        ui(new Runnable() { public void run() {
            busy = false;
            setButtonsEnabled(true);
        }});
    }

    void setButtonsEnabled(boolean en) {
        btnList.setEnabled(en);
        btnExtract.setEnabled(en);
        btnToggleAll.setEnabled(en);
        btnScan.setEnabled(en);
        btnDetach.setEnabled(en);
        btnDiag.setEnabled(en);
    }

    void showStatus(final String s, final boolean err) {
        ui(new Runnable() { public void run() {
            if (s == null) { statusLine.setVisibility(View.GONE); return; }
            statusLine.setVisibility(View.VISIBLE);
            statusLine.setTextColor(err ? cErr() : cOk());
            statusLine.setText(s);
        }});
    }

    void ui(Runnable r) { runOnUiThread(r); }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    void uiLog(final String s) {
        synchronized (logLock) { logBuf.append(s); }
        boolean need;
        synchronized (logLock) {
            need = !logFlushScheduled;
            logFlushScheduled = true;
        }
        if (need) logView.postDelayed(logFlusher, 60);
    }

    final Object logLock = new Object();
    final StringBuilder logBuf = new StringBuilder();
    boolean logFlushScheduled;
    final Runnable logFlusher = new Runnable() {
        public void run() {
            String chunk;
            synchronized (logLock) {
                chunk = logBuf.toString();
                logBuf.setLength(0);
                logFlushScheduled = false;
            }
            if (chunk.length() > 0) appendLog(chunk);
        }
    };

    void log(String s) {
        if (logView == null) return;
        appendLog(s);
    }

    /** 追加日志：始终自动滚到底部（操作流水以"能看到最新结果"为第一优先） */
    void appendLog(String chunk) {
        if (logView == null || logScroll == null) return;
        CharSequence t = logView.getText();
        if (t.length() > 200000) {
            logView.setText(t.subSequence(t.length() - 100000, t.length()));
            logScroll.post(new Runnable() {
                public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
            });
        }
        logView.append(chunk);
        logScroll.post(new Runnable() {
            public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    static String shortCmd(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(' ');
            String s = a[i];
            if (s != null && s.startsWith("-p") && s.length() > 2) s = "-p***";
            if (s != null && s.length() > 60) s = s.substring(0, 57) + "…";
            sb.append(s);
        }
        return sb.toString();
    }

    static String fmtSize(long v) {
        if (v < 1024) return v + " B";
        if (v < 1048576) return String.format("%.1f KB", v / 1024.0);
        if (v < 1073741824L) return String.format("%.1f MB", v / 1048576.0);
        return String.format("%.2f GB", v / 1073741824.0);
    }

    @Override
    protected void onDestroy() {
        cancelRun();
        if (pool != null) pool.shutdown();
        super.onDestroy();
    }
}
