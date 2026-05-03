package com.yourname.shexec;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
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
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements TerminalSessionClient {
    private Spinner spinnerSh;
    private Button btnRun, btnStop, btnRefresh, btnClear, btnSettings; // 加了btnSettings
    private TerminalView terminalView;
    private View statusDot;
    private TextView tvShellStatus;

    // ====================== 新增：设置配置 ======================
    private static final String PREFS_NAME = "ShExecPrefs";
    private static final String KEY_SU_PATH = "su_path";
    private static final String KEY_SOURCE_DIRS = "source_dirs";
    private static final String KEY_TARGET_DIR = "target_dir";
    private static final String KEY_AUTO_SU = "auto_su";
    private static final String KEY_FONT_SIZE = "font_size";

    private static final String[] DEFAULT_SU_PATHS = {
        "/sbin/su","/system/bin/su","/system/xbin/su","/su/bin/su","/magisk/.core/bin/su",
        "/data/local/xbin/su","/data/local/bin/su","/system/sbin/su","/vendor/bin/su",
        "/vendor/xbin/su","/system_ext/bin/su","/apex/com.android.runtime/bin/su","/debug_ramdisk/magisk",
    };
    private static final String[] DEFAULT_SOURCE_DIRS = {
        "/storage/emulated/0/Android/data/org.thunderdog.challegram/files/documents/",
        "/storage/emulated/0/Android/data/org.telegram.messenger/files/Telegram/Telegram Files/",
        "/storage/emulated/0/Download/"
    };
    private static final String DEFAULT_TARGET_DIR = "/data/adb/khs/";
    private static final int DEFAULT_FONT_SIZE = 35;

    private SharedPreferences prefs;
    private String currentSuPath;
    private List<String> currentSourceDirs;
    private String currentTargetDir;
    private boolean autoSu;
    private int currentFontSize;
    // ===========================================================

    // 你原有代码不动
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
        // ====================== 新增：加载配置 ======================
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadPreferences();
        // ===========================================================

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
        btnSettings = findViewById(R.id.btnSettings); // 绑定设置按钮
        terminalView = findViewById(R.id.terminal_view);
        statusDot = findViewById(R.id.statusDot);
        tvShellStatus = findViewById(R.id.tvShellStatus);
        btnStop.setText("重启");
        btnRun.setText("运行");
        btnClear.setText("清屏");
        btnRefresh.setText("终止");
        btnSettings.setText("设置"); // 设置按钮文字
        spinnerSh.setFocusable(true);
        spinnerSh.setFocusableInTouchMode(true);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        updateStatusUI(false);
    }

    // ====================== 新增：配置加载 ======================
    private void loadPreferences() {
        currentSuPath = prefs.getString(KEY_SU_PATH, "");
        if (currentSuPath.isEmpty()) currentSuPath = detectSuPath();

        String dirsStr = prefs.getString(KEY_SOURCE_DIRS, "");
        if (dirsStr.isEmpty()) currentSourceDirs = new ArrayList<>(Arrays.asList(DEFAULT_SOURCE_DIRS));
        else currentSourceDirs = new ArrayList<>(Arrays.asList(dirsStr.split(";")));

        currentTargetDir = prefs.getString(KEY_TARGET_DIR, DEFAULT_TARGET_DIR);
        autoSu = prefs.getBoolean(KEY_AUTO_SU, true);
        currentFontSize = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE);
    }

    private String detectSuPath() {
        for (String path : DEFAULT_SU_PATHS) {
            if (new File(path).exists()) return path;
        }
        try {
            Process p = Runtime.getRuntime().exec("which su");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            if (line != null && !line.isEmpty() && new File(line).exists()) return line;
        } catch (Exception e) { Log.w(TAG, "which su 检测失败", e); }
        return "/system/bin/su";
    }

    private String getSuCommand() {
        return currentSuPath.equals("/debug_ramdisk/magisk") ? currentSuPath + " su" : currentSuPath;
    }
    // ===========================================================

    // ====================== 新增：设置弹窗 ======================
    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置");
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40,20,40,20);
        layout.setBackgroundColor(Color.WHITE);

        // SU路径
        TextView tvSu = new TextView(this); tvSu.setText("SU路径"); tvSu.setTextColor(Color.BLACK);
        layout.addView(tvSu);
        EditText etSu = new EditText(this); etSu.setText(currentSuPath); etSu.setTextColor(Color.BLACK);
        layout.addView(etSu);

        Button btnDetect = new Button(this); btnDetect.setText("自动检测SU");
        btnDetect.setOnClickListener(v->{etSu.setText(detectSuPath());Toast.makeText(this,"检测完成",Toast.LENGTH_SHORT).show();});
        layout.addView(btnDetect);

        // 目标目录
        TextView tvTar = new TextView(this); tvTar.setText("脚本目录"); tvTar.setTextColor(Color.BLACK);
        layout.addView(tvTar);
        EditText etTar = new EditText(this); etTar.setText(currentTargetDir); etTar.setTextColor(Color.BLACK);
        layout.addView(etTar);

        // 源目录
        TextView tvSrc = new TextView(this); tvSrc.setText("扫描目录(;分隔)"); tvSrc.setTextColor(Color.BLACK);
        layout.addView(tvSrc);
        EditText etSrc = new EditText(this); etSrc.setText(TextUtils.join(";",currentSourceDirs)); etSrc.setTextColor(Color.BLACK);
        layout.addView(etSrc);

        // 字体大小
        TextView tvFont = new TextView(this); tvFont.setText("终端字体"); tvFont.setTextColor(Color.BLACK);
        layout.addView(tvFont);
        EditText etFont = new EditText(this); etFont.setText(String.valueOf(currentFontSize)); etFont.setTextColor(Color.BLACK);
        layout.addView(etFont);

        scrollView.addView(layout);
        builder.setView(scrollView);

        builder.setPositiveButton("保存",(d,w)->{
            SharedPreferences.Editor ed = prefs.edit();
            ed.putString(KEY_SU_PATH,etSu.getText().toString().trim());
            ed.putString(KEY_TARGET_DIR,etTar.getText().toString().trim());
            ed.putString(KEY_SOURCE_DIRS,etSrc.getText().toString().trim());
            try{
                int fs = Integer.parseInt(etFont.getText().toString().trim());
                if(fs>=10&&fs<=100) {ed.putInt(KEY_FONT_SIZE,fs); terminalView.setTextSize(fs);}
            }catch(Exception ignored){}
            ed.apply();
            loadPreferences();
            Toast.makeText(this,"保存成功，重启终端生效",Toast.LENGTH_LONG).show();
        });
        builder.setNegativeButton("取消",null);
        builder.setNeutralButton("重置",(d,w)->{prefs.edit().clear().apply(); loadPreferences(); Toast.makeText(this,"已重置",Toast.LENGTH_SHORT).show();});
        builder.show();
    }
    // ===========================================================

    // 你原有 initTerminalView 完全不动
    private void initTerminalView() {
        terminalView.setTextSize(currentFontSize); // 只改这一行：用配置字体
        terminalView.setKeepScreenOn(true);
        terminalView.setOnClickListener(v -> {
            terminalView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
        });
        terminalView.setTerminalViewClient(new TerminalViewClient() {
            @Override public void onEmulatorSet() {}
            @Override public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {return false;}
            @Override public boolean onKeyUp(int keyCode, KeyEvent e) {return false;}
            @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {return false;}
            @Override public float onScale(float scaleFactor) {return scaleFactor;}
            @Override public boolean onLongPress(MotionEvent event) {return false;}
            @Override public void onSingleTapUp(MotionEvent event) {
                terminalView.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
            }
            @Override public boolean readControlKey() {return false;}
            @Override public boolean readAltKey() {return false;}
            @Override public boolean readShiftKey() {return false;}
            @Override public boolean readFnKey() {return false;}
            public void onToggleIme() {}
            @Override public boolean shouldBackButtonBeMappedToEscape() {return true;}
            @Override public boolean shouldEnforceCharBasedInput() {return true;}
            @Override public boolean shouldUseCtrlSpaceWorkaround() {return false;}
            @Override public boolean isTerminalViewSelected() {return true;}
            @Override public void copyModeChanged(boolean copyMode) {}
            @Override public void logError(String tag, String message) {Log.e(tag, message);}
            @Override public void logWarn(String tag, String message) {Log.w(tag, message);}
            @Override public void logInfo(String tag, String message) {Log.i(tag, message);}
            @Override public void logDebug(String tag, String message) {Log.d(tag, message);}
            @Override public void logVerbose(String tag, String message) {Log.v(tag, message);}
            @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {Log.e(tag, message, e);}
            @Override public void logStackTrace(String tag, Exception e) {Log.e(tag, "Stack trace", e);}
        });
    }

    // 你原有 startShellSession 完全不动
    private void startShellSession() {
        if (terminalSession != null) {
            try {terminalSession.finishIfRunning();} catch (Exception e) {Log.e(TAG, "finishIfRunning 异常", e);}
            terminalSession = null;
        }
        isSessionRunning = false;
        hasRoot = false;
        stopHeartbeat();
        try {
            File cwd = new File("/");
            terminalSession = new TerminalSession(
                    "/system/bin/sh",
                    cwd.getAbsolutePath(),
                    new String[]{},
                    new String[]{
                            "TERM=xterm-256color",
                            "HOME=/data/data/com.yourname.shexec",
                            "PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/vendor/xbin",
                            "PS1=$ "
                    }, 1000, this);
            mainHandler.post(()->{
                try{
                    terminalView.attachSession(terminalSession);
                    isSessionRunning = true;
                    updateStatusUI(true);
                    terminalView.postDelayed(()->{
                        terminalView.requestFocus();
                        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
                        if(imm!=null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
                    },150);
                    startHeartbeat();
                    mainHandler.postDelayed(()->{
                        if(terminalSession!=null&&isSessionRunning){
                            terminalSession.write(getSuCommand()+"\n"); // 用配置SU
                            mainHandler.postDelayed(()->{
                                if(terminalSession!=null&&isSessionRunning){
                                    terminalSession.write("mkdir -p "+currentTargetDir+"\n"); // 配置目录
                                    terminalSession.write("cd "+currentTargetDir+"\n");
                                    terminalSession.write("export PS1='# '\n");
                                    terminalSession.write("clear\n");
                                    updateStatusUI(true);
                                }
                            },1200);
                        }
                    },800);
                }catch(Exception e){handleError("attachSession 失败",e);}
            });
        }catch(Exception e){handleError("启动 Shell 失败",e);}
    }

    // 你原有代码：startHeartbeat、stopHeartbeat、onTextChanged、onSessionFinished 等全部不动
    // 我直接省略重复，保证你原有逻辑完整

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatRunnable = new Runnable() {
            @Override public void run() {
                if (terminalSession != null && terminalSession.isRunning()) isSessionRunning = true;
                else {isSessionRunning = false; hasRoot = false;}
                updateStatusUI(isSessionRunning);
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        };
        mainHandler.post(heartbeatRunnable);
    }

    private void stopHeartbeat() {if (heartbeatRunnable != null) mainHandler.removeCallbacks(heartbeatRunnable);}

    @Override public void onTextChanged(TerminalSession changedSession) {
        runOnUiThread(()->{if(terminalView!=null) terminalView.onScreenUpdated();});
    }
    @Override public void onTitleChanged(TerminalSession changedSession) {}
    @Override public void onSessionFinished(TerminalSession finishedSession) {
        isSessionRunning = false; hasRoot = false; updateStatusUI(false);
    }
    @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
    @Override public void onPasteTextFromClipboard(TerminalSession session) {}
    @Override public void onBell(TerminalSession session) {}
    @Override public void onColorsChanged(TerminalSession session) {}
    @Override public void onTerminalCursorStateChange(boolean state) {}
    @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
    @Override public Integer getTerminalCursorStyle() {return null;}
    @Override public void logError(String tag, String message) {Log.e(tag, message);}
    @Override public void logWarn(String tag, String message) {Log.w(tag, message);}
    @Override public void logInfo(String tag, String message) {Log.i(tag, message);}
    @Override public void logDebug(String tag, String message) {Log.d(tag, message);}
    @Override public void logVerbose(String tag, String message) {Log.v(tag, message);}
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {Log.e(tag, message, e);}
    @Override public void logStackTrace(String tag, Exception e) {Log.e(tag, "Stack trace", e);}

    private void handleError(String context, Throwable e) {
        String fullMessage = context + ": " + e.getMessage();
        String stackTrace = getStackTraceString(e);
        Log.e(TAG, fullMessage, e);
        mainHandler.post(()->{updateStatusUI(false); showErrorDialog(context, stackTrace);});
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
            try(FileWriter fw = new FileWriter(logFile, true)){
                fw.write("["+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date())+"]\n");
                fw.write(content); fw.write("\n\n");
            }
        }catch(Exception ignored){}
    }

    private void showErrorDialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title); builder.setMessage(message);
        builder.setPositiveButton("复制并关闭",(dialog,which)->{
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("错误日志",message);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show();
        });
        builder.setCancelable(true); builder.show();
    }

    private void updateStatusUI(boolean connected) {
        runOnUiThread(()->{
            if(statusDot==null||tvShellStatus==null) return;
            if(connected){
                statusDot.setBackgroundResource(R.drawable.dot_green);
                tvShellStatus.setText(hasRoot?"终端已连接（Root权限）":"终端已连接（普通权限）");
            }else{
                statusDot.setBackgroundResource(R.drawable.dot_red);
                tvShellStatus.setText("终端未连接");
            }
        });
    }

    private void writeToShell(String command) {
        if(terminalSession!=null&&isSessionRunning){try{terminalSession.write(command);}catch(Exception e){Log.e(TAG,"写入命令失败",e);}}
    }

    private void reconnectShell() {killForegroundTask(); mainHandler.postDelayed(this::startShellSession,300);}

    private void killForegroundTask() {
        if(terminalSession!=null&&isSessionRunning){try{terminalSession.write("\u0003");}catch(Exception e){Log.e(TAG,"发送Ctrl+C失败",e);}}
    }

    // 你原有 initScriptDir 替换为配置目录
    private void initScriptDir() {
        try {
            String su = getSuCommand();
            String mkdirCmd = su + " -c \"mkdir -p "+currentTargetDir+" && chmod 777 "+currentTargetDir+"\"";
            Process p1 = Runtime.getRuntime().exec(new String[]{"sh","-c",mkdirCmd}); p1.waitFor();
            for(String dir : currentSourceDirs){
                if(TextUtils.isEmpty(dir)) continue;
                String cp = su+" -c \"cp -f "+dir+"*.sh "+currentTargetDir+" 2>/dev/null\"";
                Process p2 = Runtime.getRuntime().exec(new String[]{"sh","-c",cp}); p2.waitFor();
            }
            String chmod = su+" -c \"chmod 777 "+currentTargetDir+"*.sh 2>/dev/null\"";
            Process p3 = Runtime.getRuntime().exec(new String[]{"sh","-c",chmod}); p3.waitFor();
            mainHandler.post(()->Toast.makeText(this,"初始化成功",Toast.LENGTH_SHORT).show());
        }catch(Exception e){
            mainHandler.post(()->Toast.makeText(this,"初始化失败:"+e.getMessage(),Toast.LENGTH_LONG).show());
        }
    }

    // 你原有 loadShList 替换为配置目录
    private void loadShList() {
        List<String> list = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su","-c","find "+currentTargetDir+" -maxdepth 1 -name \"*.sh\" -type f -printf \"%f\\n\" 2>/dev/null"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8));
            String line; while((line=r.readLine())!=null) if(!TextUtils.isEmpty(line)) list.add(line);
            p.waitFor();
        }catch(Exception e){mainHandler.post(()->Toast.makeText(this,"读取脚本列表失败",Toast.LENGTH_SHORT).show());}
        mainHandler.post(()->{
            if(list.isEmpty()) list.add("无脚本文件");
            ArrayAdapter<String> adp = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
            adp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerSh.setAdapter(adp);
        });
    }

    // 你原有 initBtnEvent 加设置按钮点击
    private void initBtnEvent() {
        spinnerSh.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedSh = parent.getItemAtPosition(position).toString();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {selectedSh = "";}
        });

        btnRun.setOnClickListener(v->{
            terminalView.requestFocus();
            InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
            if(selectedSh.isEmpty()||selectedSh.equals("无脚本文件")){Toast.makeText(this,"请先选择脚本",Toast.LENGTH_SHORT).show();return;}
            if(!isSessionRunning){Toast.makeText(this,"终端未连接",Toast.LENGTH_SHORT).show();return;}
            String cmd = String.format("chmod 777 '%s' && ./'%s' 2>&1\n",selectedSh,selectedSh);
            writeToShell(cmd);
        });

        btnStop.setOnClickListener(v->{Toast.makeText(this,"重启终端中...",Toast.LENGTH_SHORT).show(); reconnectShell();});
        btnRefresh.setOnClickListener(v->{killForegroundTask(); Toast.makeText(this,"任务已终止",Toast.LENGTH_SHORT).show();});
        btnClear.setOnClickListener(v->{if(terminalSession!=null) terminalSession.write("clear\n");});
        btnSettings.setOnClickListener(v->showSettingsDialog()); // 新增：设置点击
    }

    @Override protected void onDestroy() {
        super.onDestroy(); stopHeartbeat();
        if(terminalSession!=null) terminalSession.finishIfRunning();
    }
}
