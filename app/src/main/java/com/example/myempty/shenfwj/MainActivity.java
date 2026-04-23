package com.yourname.shexec;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements TerminalSessionClient {
    private Spinner spinnerSh;
    private Button btnRun, btnStop, btnRefresh, btnClear;
    private TerminalView terminalView;
    private View statusDot;
    private TextView tvShellStatus;
    
    private final String[] SOURCE_DIRS = {
        "/storage/emulated/0/Android/data/org.thunderdog.challegram/files/documents/",
        "/storage/emulated/0/Android/data/org.telegram.messenger/files/Telegram/Telegram Files/",
        "/storage/emulated/0/Download/"
        
    };

    private final String TARGET_DIR = "/data/adb/khs/";
    private String selectedSh = "";
    private TerminalSession terminalSession;
    private volatile boolean isSessionRunning = false;
    private volatile boolean hasRoot = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String TAG = "TerminalPTY";
    private Runnable heartbeatRunnable;
    private static final long HEARTBEAT_INTERVAL = 2000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(
                (thread, throwable) -> {
                    String stackTrace = getStackTraceString(throwable);
                    Log.e(TAG, "未捕获的异常: " + stackTrace);
                    writeLogToFile(stackTrace);
                    mainHandler.post(() -> showErrorDialog("应用崩溃", stackTrace));
                });
        setContentView(R.layout.activity_main);
        initView();
        initTerminalView();
        initBtnEvent();
        new Thread(this::initScriptDir).start();
        new Thread(this::loadShList).start();
        mainHandler.postDelayed(this::startShellSession, 500);
    }

    private void initView() {
        spinnerSh = findViewById(R.id.spinner_sh);
        btnRun = findViewById(R.id.btnRun);
        btnStop = findViewById(R.id.btnStop);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnClear = findViewById(R.id.btnClear);
        terminalView = findViewById(R.id.terminal_view);
        statusDot = findViewById(R.id.statusDot);
        tvShellStatus = findViewById(R.id.tvShellStatus);

        btnStop.setText("重启");
        btnRun.setText("运行");
        btnClear.setText("清屏");
        btnRefresh.setText("终止");

        spinnerSh.setFocusable(true);
        spinnerSh.setFocusableInTouchMode(true);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        updateStatusUI(false);
    }

    private void initTerminalView() {
        terminalView.setTextSize(35);
        terminalView.setKeepScreenOn(true);

        terminalView.setOnClickListener(
                v -> {
                    terminalView.requestFocus();
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
                    }
                });

        terminalView.setTerminalViewClient(
                new TerminalViewClient() {
                    @Override
                    public void onEmulatorSet() {}

                    @Override
                    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
                        return false;
                    }

                    @Override
                    public boolean onKeyUp(int keyCode, KeyEvent e) {
                        return false;
                    }

                    @Override
                    public boolean onCodePoint(
                            int codePoint, boolean ctrlDown, TerminalSession session) {
                        return false;
                    }

                    @Override
                    public float onScale(float scaleFactor) {
                        return scaleFactor;
                    }

                    @Override
                    public boolean onLongPress(MotionEvent event) {
                        return false;
                    }

                    @Override
                    public void onSingleTapUp(MotionEvent event) {
                        terminalView.requestFocus();
                        InputMethodManager imm =
                                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
                        }
                    }

                    @Override
                    public boolean readControlKey() {
                        return false;
                    }

                    @Override
                    public boolean readAltKey() {
                        return false;
                    }

                    @Override
                    public boolean readShiftKey() {
                        return false;
                    }

                    @Override
                    public boolean readFnKey() {
                        return false;
                    }

                    // 保持原始代码的无参版本，不加 @Override
                    public void onToggleIme() {}

                    @Override
                    public boolean shouldBackButtonBeMappedToEscape() {
                        return true;
                    }

                    @Override
                    public boolean shouldEnforceCharBasedInput() {
                        return true;
                    }

                    @Override
                    public boolean shouldUseCtrlSpaceWorkaround() {
                        return false;
                    }

                    @Override
                    public boolean isTerminalViewSelected() {
                        return true;
                    }

                    @Override
                    public void copyModeChanged(boolean copyMode) {}

                    @Override
                    public void logError(String tag, String message) {
                        Log.e(tag, message);
                    }

                    @Override
                    public void logWarn(String tag, String message) {
                        Log.w(tag, message);
                    }

                    @Override
                    public void logInfo(String tag, String message) {
                        Log.i(tag, message);
                    }

                    @Override
                    public void logDebug(String tag, String message) {
                        Log.d(tag, message);
                    }

                    @Override
                    public void logVerbose(String tag, String message) {
                        Log.v(tag, message);
                    }

                    @Override
                    public void logStackTraceWithMessage(String tag, String message, Exception e) {
                        Log.e(tag, message, e);
                    }

                    @Override
                    public void logStackTrace(String tag, Exception e) {
                        Log.e(tag, "Stack trace", e);
                    }
                });
    }

    private void startShellSession() {
        if (terminalSession != null) {
            try {
                terminalSession.finishIfRunning();
            } catch (Exception e) {
                Log.e(TAG, "finishIfRunning 异常", e);
            }
            terminalSession = null;
        }
        isSessionRunning = false;
        hasRoot = false;
        stopHeartbeat();

        try {
            File cwd = new File("/");
            terminalSession =
                    new TerminalSession(
                            "/system/bin/sh",
                            cwd.getAbsolutePath(),
                            new String[] {},
                            new String[] {
                                "TERM=xterm-256color",
                                "HOME=/data/data/com.yourname.shexec",
                                "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin",
                                "PS1=$ "
                            },
                            1000,
                            this);

            mainHandler.post(
                    () -> {
                        try {
                            terminalView.attachSession(terminalSession);
                            isSessionRunning = true;
                            updateStatusUI(true);

                            terminalView.postDelayed(
                                    () -> {
                                        terminalView.requestFocus();
                                        InputMethodManager imm =
                                                (InputMethodManager)
                                                        getSystemService(
                                                                Context.INPUT_METHOD_SERVICE);
                                        if (imm != null) {
                                            imm.showSoftInput(
                                                    terminalView, InputMethodManager.SHOW_IMPLICIT);
                                        }
                                    },
                                    150);

                            startHeartbeat();

                            mainHandler.postDelayed(
                                    () -> {
                                        if (terminalSession != null && isSessionRunning) {
                                            terminalSession.write("su\n");
                                            mainHandler.postDelayed(
                                                    () -> {
                                                        if (terminalSession != null
                                                                && isSessionRunning) {
                                                            terminalSession.write(
                                                                    "mkdir -p "
                                                                            + TARGET_DIR
                                                                            + "\n");
                                                            terminalSession.write(
                                                                    "cd " + TARGET_DIR + "\n");
                                                            terminalSession.write(
                                                                    "export PS1='# '\n");
                                                            terminalSession.write("clear\n");
                                                            updateStatusUI(true);
                                                        }
                                                    },
                                                    1200);
                                        }
                                    },
                                    800);

                        } catch (Exception e) {
                            handleError("attachSession 失败", e);
                        }
                    });
        } catch (Exception e) {
            handleError("启动 Shell 失败", e);
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        if (terminalSession != null && terminalSession.isRunning()) {
                            isSessionRunning = true;
                        } else {
                            isSessionRunning = false;
                            hasRoot = false;
                        }
                        updateStatusUI(isSessionRunning);
                        mainHandler.postDelayed(this, HEARTBEAT_INTERVAL);
                    }
                };
        mainHandler.post(heartbeatRunnable);
    }

    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            mainHandler.removeCallbacks(heartbeatRunnable);
        }
    }

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        runOnUiThread(
                () -> {
                    if (terminalView != null) {
                        terminalView.onScreenUpdated();
                    }
                });
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {}

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        isSessionRunning = false;
        hasRoot = false;
        updateStatusUI(false);
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {}

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {}

    @Override
    public void onBell(TerminalSession session) {}

    @Override
    public void onColorsChanged(TerminalSession session) {}

    @Override
    public void onTerminalCursorStateChange(boolean state) {}

    @Override
    public void setTerminalShellPid(TerminalSession session, int pid) {}

    @Override
    public Integer getTerminalCursorStyle() {
        return null;
    }

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, "Stack trace", e);
    }

    private void handleError(String context, Throwable e) {
        String fullMessage = context + ": " + e.getMessage();
        String stackTrace = getStackTraceString(e);
        Log.e(TAG, fullMessage, e);
        mainHandler.post(
                () -> {
                    updateStatusUI(false);
                    showErrorDialog(context, stackTrace);
                });
    }

    private String getStackTraceString(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    private void writeLogToFile(String content) {
        try {
            File logFile = new File(getExternalFilesDir(null), "error_log.txt");
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(
                        "["
                                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                        .format(new Date())
                                + "]\n");
                fw.write(content);
                fw.write("\n\n");
            }
        } catch (Exception ignored) {
        }
    }

    private void showErrorDialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(
                "复制并关闭",
                (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip =
                            android.content.ClipData.newPlainText("错误日志", message);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                });
        builder.setCancelable(true);
        builder.show();
    }

    private void updateStatusUI(boolean connected) {
        runOnUiThread(
                () -> {
                    if (statusDot == null || tvShellStatus == null) return;
                    if (connected) {
                        statusDot.setBackgroundResource(R.drawable.dot_green);
                        tvShellStatus.setText(hasRoot ? "终端已连接（Root权限）" : "终端已连接（普通权限）");
                    } else {
                        statusDot.setBackgroundResource(R.drawable.dot_red);
                        tvShellStatus.setText("终端未连接");
                    }
                });
    }

    private void writeToShell(String command) {
        if (terminalSession != null && isSessionRunning) {
            try {
                terminalSession.write(command);
            } catch (Exception e) {
                Log.e(TAG, "写入命令失败", e);
            }
        }
    }

    private void reconnectShell() {
        killForegroundTask();
        mainHandler.postDelayed(this::startShellSession, 300);
    }

    private void killForegroundTask() {
        if (terminalSession != null && isSessionRunning) {
            try {
                terminalSession.write("\u0003");
            } catch (Exception e) {
                Log.e(TAG, "发送Ctrl+C失败", e);
            }
        }
    }

    private void initScriptDir() {
        try {
            String mkdirCmd = "mkdir -p \"" + TARGET_DIR + "\" && chmod 777 \"" + TARGET_DIR + "\"";
            Process p1 = Runtime.getRuntime().exec(new String[] {"su", "-c", mkdirCmd});
            p1.waitFor();
            for (String sourceDir : SOURCE_DIRS) {
                if (TextUtils.isEmpty(sourceDir)) continue;
                String copyCmd =
                        "cp -f \"" + sourceDir + "\"*.sh \"" + TARGET_DIR + "\" 2>/dev/null";
                Process p2 = Runtime.getRuntime().exec(new String[] {"su", "-c", copyCmd});
                p2.waitFor();
            }
            String chmodCmd = "chmod 777 \"" + TARGET_DIR + "\"*.sh 2>/dev/null";
            Process p3 = Runtime.getRuntime().exec(new String[] {"su", "-c", chmodCmd});
            p3.waitFor();

            mainHandler.post(() -> Toast.makeText(this, "初始化成功", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            mainHandler.post(
                    () ->
                            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG)
                                    .show());
        }
    }

    private void loadShList() {
        List<String> list = new ArrayList<>();
        try {
            Process p =
                    Runtime.getRuntime()
                            .exec(
                                    new String[] {
                                        "su",
                                        "-c",
                                        "find \""
                                                + TARGET_DIR
                                                + "\" -maxdepth 1 -name \"*.sh\" -type f -printf \"%f\\n\" 2>/dev/null"
                                    });
            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (!TextUtils.isEmpty(line)) list.add(line);
            }
            p.waitFor();
        } catch (Exception e) {
            mainHandler.post(() -> Toast.makeText(this, "读取脚本列表失败", Toast.LENGTH_SHORT).show());
        }
        mainHandler.post(
                () -> {
                    if (list.isEmpty()) list.add("无脚本文件");
                    ArrayAdapter<String> adp =
                            new ArrayAdapter<>(this, R.layout.spinner_item, list);
                    adp.setDropDownViewResource(R.layout.spinner_dropdown_item);
                    spinnerSh.setAdapter(adp);
                });
    }

    private void initBtnEvent() {
        spinnerSh.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        selectedSh = parent.getItemAtPosition(position).toString();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        selectedSh = "";
                    }
                });

        btnRun.setOnClickListener(
                v -> {
                    terminalView.requestFocus();
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
                    }

                    if (selectedSh.isEmpty() || selectedSh.equals("无脚本文件")) {
                        Toast.makeText(this, "请先选择脚本", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!isSessionRunning) {
                        Toast.makeText(this, "终端未连接", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String cmd =
                            String.format(
                                    "chmod 777 '%s' && ./'%s' 2>&1\n", selectedSh, selectedSh);
                    writeToShell(cmd);
                });

        btnStop.setOnClickListener(
                v -> {
                    Toast.makeText(this, "重启终端中...", Toast.LENGTH_SHORT).show();
                    reconnectShell();
                });

        btnRefresh.setOnClickListener(
                v -> {
                    killForegroundTask();
                    Toast.makeText(this, "任务已终止", Toast.LENGTH_SHORT).show();
                });

        btnClear.setOnClickListener(
                v -> {
                    if (terminalSession != null) {
                        terminalSession.write("clear\n");
                    }
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopHeartbeat();
        if (terminalSession != null) {
            terminalSession.finishIfRunning();
        }
    }
}
