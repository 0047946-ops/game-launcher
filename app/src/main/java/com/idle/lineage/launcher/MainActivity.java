package com.idle.lineage.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final String TAG = "IdleLineageApp";
    private static final String PREFS_NAME = "GamePrefs";
    private static final String KEY_CURRENT_VERSION = "current_version";
    private static final String GITHUB_RELEASE_API = "https://api.github.com/repos/pp771007/idle-lineage-class/releases/latest";

    private WebView webView;
    private RelativeLayout layoutLoading;
    private ProgressBar progressBar;
    private TextView tvLoadingStatus;

    private File gameDir;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<Intent> createDocumentLauncher;

    private byte[] pendingSaveBytes = null;
    private String pendingSaveFileName = null;

    private static final String SAVE_NAME_PREFIX = "";
    private String saveHookJs = null;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;

    @Keep
    public class WebAppInterface {
        @JavascriptInterface
        @Keep
        public void saveBase64File(String dataUrlOrBase64, String mimeType, String fileName) {
            Log.d(TAG, "🎯 [JS 觸發導出] 檔名: " + fileName + " | 長度: " + (dataUrlOrBase64 != null ? dataUrlOrBase64.length() : 0));
            runOnUiThread(() -> processAndSaveFile(dataUrlOrBase64, mimeType, fileName));
        }

        @JavascriptInterface
        @Keep
        public void pickSaveSlot(String slotsJson) {
            runOnUiThread(() -> showSlotChooser(slotsJson));
        }

        @JavascriptInterface
        @Keep
        public void log(String message) {
            Log.d(TAG, "🌐 [SaveHook] " + message);
        }

        @JavascriptInterface
        @Keep
        public void toast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        checkAllFilesAccessPermission();
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        layoutLoading = findViewById(R.id.layoutLoading);
        progressBar = findViewById(R.id.progressBar);
        tvLoadingStatus = findViewById(R.id.tvLoadingStatus);

        gameDir = new File(getFilesDir(), "game");
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        initFileChooserLauncher();
        initCreateDocumentLauncher();
        setupWebView();

        // 預設先載入內建高階啟動器導航介面（使用者可自行於介面中切換線上伺服器與外掛）
        loadNativeLauncherHtml();
    }

    private void showLoadingUI(String statusText) {
        mainHandler.post(() -> {
            if (layoutLoading != null) {
                layoutLoading.setVisibility(View.VISIBLE);
                tvLoadingStatus.setText(statusText);
                progressBar.setIndeterminate(true);
            }
        });
    }

    private void updateProgressUI(String statusText, int progress) {
        mainHandler.post(() -> {
            if (layoutLoading != null) {
                layoutLoading.setVisibility(View.VISIBLE);
                tvLoadingStatus.setText(statusText);
                if (progress >= 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(progress);
                } else {
                    progressBar.setIndeterminate(true);
                }
            }
        });
    }

    private void hideLoadingUI() {
        mainHandler.post(() -> {
            if (layoutLoading != null) {
                layoutLoading.setVisibility(View.GONE);
            }
        });
    }

    private void initFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (filePathCallback == null) return;
                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent dataIntent = result.getData();
                        if (dataIntent.getData() != null) {
                            results = new Uri[]{dataIntent.getData()};
                        } else if (dataIntent.getClipData() != null) {
                            int count = dataIntent.getClipData().getItemCount();
                            results = new Uri[count];
                            for (int i = 0; i < count; i++) {
                                results[i] = dataIntent.getClipData().getItemAt(i).getUri();
                            }
                        }
                    }
                    filePathCallback.onReceiveValue(results);
                    filePathCallback = null;
                }
        );
    }

    private void initCreateDocumentLauncher() {
        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                        Uri uri = result.getData().getData();
                        if (pendingSaveBytes != null) {
                            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                                if (os != null) {
                                    os.write(pendingSaveBytes);
                                    os.flush();
                                    Toast.makeText(MainActivity.this, "✅ 檔案已成功儲存！", Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception e) {
                                showDebugDialog("❌ SAF 寫入失敗", e.getMessage());
                            } finally {
                                pendingSaveBytes = null;
                                pendingSaveFileName = null;
                            }
                        }
                    } else {
                        pendingSaveBytes = null;
                        pendingSaveFileName = null;
                    }
                }
        );
    }

    private void setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // 註冊 A 檔強大完整的 JavaScript 橋接介面
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidDownloader");
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.w(TAG, "🌐 [JS Console] " + consoleMessage.message());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(getApplicationContext(), "無法開啟檔案選擇器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                String contentToCheck = (defaultValue != null && !defaultValue.isEmpty()) ? defaultValue : message;
                if (contentToCheck != null && contentToCheck.contains("SIG1:")) {
                    processAndSaveFile(contentToCheck, "application/json", null);
                    result.confirm();
                    return true;
                }
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (message != null && message.contains("SIG1:")) {
                    processAndSaveFile(message, "application/json", null);
                    result.confirm();
                    return true;
                }
                return super.onJsAlert(view, url, message, result);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            private void injectDownloadHook(WebView view) {
                String js = loadSaveHookJs();
                if (js != null && !js.isEmpty()) {
                    view.evaluateJavascript(js, null);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectDownloadHook(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectDownloadHook(view);
                hideLoadingUI();

                // 當載入遠端線上遊戲伺服器時，自動注入 B 檔強大的【10大外掛模組】與【TMEngine v106.0 防斷線引擎】以及【檔案讀取匯入修復】
                if (url != null && url.startsWith("http")) {
                    String totalPluginJs = "(function () {" +
                            "'use strict';" +
                            "if(window.__all_plugins_loaded) return;" +
                            "window.__all_plugins_loaded = true;" +
                            "var s0 = document.createElement('script');" +
                            "s0.src = 'https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js?v=' + Date.now();" +
                            "document.body.appendChild(s0);" +
                            "const b = 'https://kid0924.github.io/idle-lineage-class/';" +
                            "const t = window.location.hostname.includes('pp771007');" +
                            "const c = ['klh_initial.js','klh_GMShop.js','klh_mobile-perf.js','klh_perf-monitor.js','klh_Backpack.js','klh_pk.js','klh_Pandora.js'].map(x => b + x);" +
                            "const n = t ? [...[b+'klh_remove-banner.js'],...c] : [...['https://pp771007.github.io/idle-lineage-class/afk-lzcache.js', 'https://pp771007.github.io/idle-lineage-class/afk-offline.js'],...c];" +
                            "function s(e, t) {" +
                            "    const node = document.createElement('div');" +
                            "    node.textContent = e;" +
                            "    node.style.cssText = 'position:fixed;top:20px;right:20px;background:' + (t ? '#2ecc71' : '#e74c3c') + ';color:white;padding:12px 24px;border-radius:8px;z-index:99999;font-family:sans-serif;box-shadow:0 4px 12px rgba(0,0,0,0.15);transition:opacity 0.5s';" +
                            "    document.body.appendChild(node);" +
                            "    setTimeout(() => {" +
                            "        node.style.opacity = '0';" +
                            "        setTimeout(() => node.remove(), 500);" +
                            "    }, 2500);" +
                            "}" +
                            "function l(e) {" +
                            "    return new Promise((resolve, reject) => {" +
                            "        const o = document.createElement('script');" +
                            "        o.src = e + '?v=' + Date.now();" +
                            "        o.onload = (() => { resolve(); });" +
                            "        o.onerror = (() => { reject(e); });" +
                            "        document.body.appendChild(o);" +
                            "    });" +
                            "}" +
                            "n.reduce((e, t) => e.then(() => l(t)), Promise.resolve())" +
                            "    .then(() => { s('🎉 【10大外掛模組】全部注入成功！', !0); })" +
                            "    .catch(r => {" +
                            "        const f = (r && typeof r === 'string') ? r.split('/').pop().split('?')[0] : '';" +
                            "        s('❌ 載入失敗' + (f ? '：' + f : '！'), !1);" +
                            "    });" +
                            "})();";
                    view.evaluateJavascript(totalPluginJs, null);

                    String tmEngineJs = "(function() {" +
                            "'use strict';" +
                            "if(window.__tm_engine_loaded) return;" +
                            "window.__tm_engine_loaded = true;" +
                            "const PerformanceCore = {" +
                            "    initTuning: () => {" +
                            "        if (typeof window.requestIdleCallback !== 'undefined') {" +
                            "            window.requestIdleCallback(() => {" +
                            "                if (window.gc) window.gc();" +
                            "                console.log('【TMEngine】低功耗模式與記憶體最佳化執行完畢。');" +
                            "            }, { timeout: 500 });" +
                            "        }" +
                            "    }," +
                            "    getJitter: (base, variance) => base + Math.floor(Math.random() * variance)" +
                            "};" +
                            "PerformanceCore.initTuning();" +
                            "const originalSetInterval = window.setInterval;" +
                            "window.setInterval = function(callback, delay, ...args) {" +
                            "    const optimizedDelay = delay < 150 ? 150 : delay;" +
                            "    return originalSetInterval(callback, optimizedDelay, ...args);" +
                            "};" +
                            "const NetworkOptimizer = {" +
                            "    _isMobile: false," +
                            "    detectEnvironment: async () => {" +
                            "        const conn = navigator.connection || {};" +
                            "        NetworkOptimizer._isMobile = conn.type === 'cellular' || /Android|webOS|iPhone|iPad/i.test(navigator.userAgent);" +
                            "        try {" +
                            "            const start = Date.now();" +
                            "            await fetch(window.location.href, { method: 'HEAD', cache: 'no-cache' });" +
                            "            const rtt = Date.now() - start;" +
                            "            if (rtt > 150) NetworkOptimizer._isMobile = true;" +
                            "        } catch (e) {}" +
                            "    }," +
                            "    getJitterParams: () => {" +
                            "        return NetworkOptimizer._isMobile ? { base: 500, variance: 700 } : { base: 120, variance: 250 };" +
                            "    }" +
                            "};" +
                            "const DOMWatcher = {" +
                            "    waitForEl: (selector, success) => {" +
                            "        const el = document.querySelector(selector);" +
                            "        if (el) { success(el); return; }" +
                            "        const obs = new MutationObserver((mutations, obs) => {" +
                            "            const target = document.querySelector(selector);" +
                            "            if (target) { obs.disconnect(); success(target); }" +
                            "        });" +
                            "        if (document.body) {" +
                            "            obs.observe(document.body, { childList: true, subtree: true });" +
                            "        } else {" +
                            "            document.addEventListener('DOMContentLoaded', () => {" +
                            "                obs.observe(document.body, { childList: true, subtree: true });" +
                            "            });" +
                            "        }" +
                            "    }" +
                            "};" +
                            "const GuildInterfaceOptimizer = {" +
                            "    isGuildActive: () => {" +
                            "        const guildPanel = document.querySelector('.guild-interface, .blood-pledge-panel, [data-view=\"guild\"]');" +
                            "        return guildPanel !== null && guildPanel.offsetParent !== null;" +
                            "    }," +
                            "    executeGuildLogic: () => {" +
                            "        if (!GuildInterfaceOptimizer.isGuildActive()) return;" +
                            "        const checkInBtn = document.querySelector('.guild-checkin-btn:not(.completed)');" +
                            "        if (checkInBtn) {" +
                            "            setTimeout(() => checkInBtn.click(), PerformanceCore.getJitter(500, 1000));" +
                            "        }" +
                            "        const donateBtn = document.querySelector('.guild-donate-confirm');" +
                            "        if (donateBtn && Math.random() > 0.95) {" +
                            "            setTimeout(() => donateBtn.click(), PerformanceCore.getJitter(800, 1500));" +
                            "        }" +
                            "    }" +
                            "};" +
                            "window.executeLogic = function() {" +
                            "    if (GuildInterfaceOptimizer.isGuildActive()) {" +
                            "        GuildInterfaceOptimizer.executeGuildLogic();" +
                            "        return;" +
                            "    }" +
                            "    const hpText = document.querySelector('.hp-text')?.innerText;" +
                            "    if (hpText) {" +
                            "        const [cur, max] = hpText.split('/').map(Number);" +
                            "        if (cur / max < 0.75) {" +
                            "            const potionBtn = document.querySelector('#btn-use-potion') || document.querySelector('.potion-btn');" +
                            "            if (potionBtn) potionBtn.click();" +
                            "        }" +
                            "    }" +
                            "    const attackBtn = document.querySelector('.attack-btn');" +
                            "    if (attackBtn && !attackBtn.classList.contains('cooldown')) {" +
                            "        const { base, variance } = NetworkOptimizer.getJitterParams();" +
                            "        setTimeout(() => attackBtn.click(), PerformanceCore.getJitter(base, variance));" +
                            "    }" +
                            "    const buffs = [" +
                            "        { selector: '.status-haste', btn: '#btn-use-haste-potion' }," +
                            "        { selector: '.status-shield', btn: '#btn-use-shield' }," +
                            "        { selector: '.status-holy-weapon', btn: '#btn-use-holy-weapon' }," +
                            "        { selector: '.status-berserk', btn: '#btn-use-berserk' }" +
                            "    ];" +
                            "    buffs.forEach(buff => {" +
                            "        if (document.querySelector(buff.selector) === null) {" +
                            "            const targetBtn = document.querySelector(buff.btn);" +
                            "            if (targetBtn && Math.random() > 0.8) {" +
                            "                setTimeout(() => targetBtn.click(), PerformanceCore.getJitter(400, 800));" +
                            "            }" +
                            "        }" +
                            "    });" +
                            "    if (Math.random() > 0.995) {" +
                            "        const sellBtn = document.querySelector('#btn-sell-all-waste');" +
                            "        if (sellBtn && sellBtn.offsetParent !== null) sellBtn.click();" +
                            "    }" +
                            "};" +
                            "const PageVisibilityModule = {" +
                            "    init: () => {" +
                            "        document.addEventListener('visibilitychange', () => {" +
                            "            if (!document.hidden && typeof window.executeLogic === 'function') {" +
                            "                window.executeLogic();" +
                            "            }" +
                            "        });" +
                            "    }" +
                            "};" +
                            "const HeartbeatModule = {" +
                            "    sendKeepAliveSignal: () => {" +
                            "        if (window.socket && window.socket.readyState === WebSocket.OPEN) {" +
                            "            window.socket.send(JSON.stringify({ type: 'heartbeat', timestamp: Date.now() }));" +
                            "        } else {" +
                            "            fetch(window.location.href, { method: 'HEAD', cache: 'no-cache', keepalive: true }).catch(() => {});" +
                            "        }" +
                            "    }" +
                            "};" +
                            "const WebWorkerModule = {" +
                            "    init: () => {" +
                            "        if (!window.Worker) return;" +
                            "        const workerCode = `let intervalId = null;" +
                            "        self.onmessage = function(e) {" +
                            "            if (e.data === 'start') {" +
                            "                if (intervalId) clearInterval(intervalId);" +
                            "                intervalId = setInterval(() => { self.postMessage('ping'); }, 1000);" +
                            "            } else if (e.data === 'stop') {" +
                            "                if (intervalId) clearInterval(intervalId);" +
                            "            }" +
                            "        };`;" +
                            "        const blob = new Blob([workerCode], { type: 'application/javascript' });" +
                            "        const workerUrl = URL.createObjectURL(blob);" +
                            "        const worker = new Worker(workerUrl);" +
                            "        worker.postMessage('start');" +
                            "        worker.onmessage = function(e) {" +
                            "            if (e.data === 'ping') HeartbeatModule.sendKeepAliveSignal();" +
                            "        };" +
                            "    }" +
                            "};" +
                            "const AudioKeepAliveModule = {" +
                            "    silentAudioCtx: null," +
                            "    init: () => {" +
                            "        try {" +
                            "            const AudioContext = window.AudioContext || window.webkitAudioContext;" +
                            "            if (!AudioContext) return;" +
                            "            AudioKeepAliveModule.silentAudioCtx = new AudioContext();" +
                            "            const oscillator = AudioKeepAliveModule.silentAudioCtx.createOscillator();" +
                            "            const gainNode = AudioKeepAliveModule.silentAudioCtx.createGain();" +
                            "            gainNode.gain.value = 0.0001;" +
                            "            oscillator.connect(gainNode);" +
                            "            gainNode.connect(AudioKeepAliveModule.silentAudioCtx.destination);" +
                            "            oscillator.start();" +
                            "            document.addEventListener('visibilitychange', () => {" +
                            "                if (AudioKeepAliveModule.silentAudioCtx && AudioKeepAliveModule.silentAudioCtx.state === 'suspended') {" +
                            "                    AudioKeepAliveModule.silentAudioCtx.resume();" +
                            "                }" +
                            "            });" +
                            "        } catch (e) {}" +
                            "    }" +
                            "};" +
                            "const initSystem = async () => {" +
                            "    await NetworkOptimizer.detectEnvironment();" +
                            "    PageVisibilityModule.init();" +
                            "    WebWorkerModule.init();" +
                            "    AudioKeepAliveModule.init();" +
                            "    const div = document.createElement('div');" +
                            "    div.style = 'position:fixed; top:10px; left:10px; background:rgba(0,0,0,0.85); color:#0f0; padding:10px; z-index:2147483647; border-radius:8px; font-size:11px; border:1px solid #0f0; pointer-events:none;';" +
                            "    div.innerHTML = `<div style=\"font-weight:bold;\">【TMEngine v106.0】全域極致整合防斷線版</div><div>● 背景抗凍結：四大模組運行中</div><div>● 模式：${NetworkOptimizer._isMobile ? '手機動態適配' : 'WIFI高速運行'}</div>`;" +
                            "    const attachUI = () => {" +
                            "        if (document.body) document.body.appendChild(div);" +
                            "        else setTimeout(attachUI, 100);" +
                            "    };" +
                            "    attachUI();" +
                            "    DOMWatcher.waitForEl('.attack-btn', () => {" +
                            "        setInterval(window.executeLogic, 250);" +
                            "    });" +
                            "};" +
                            "initSystem();" +
                            "})();";
                    view.evaluateJavascript(tmEngineJs, null);

                    String fixImportJs = "(function(){" +
                            "if(window.__fix_import_active) return;" +
                            "window.__fix_import_active = true;" +
                            "var originalReadAsText = FileReader.prototype.readAsText;" +
                            "FileReader.prototype.readAsText = function(file, encoding){" +
                            "  var self = this;" +
                            "  var originalOnload = self.onload;" +
                            "  self.onload = function(e){" +
                            "    try {" +
                            "      var rawText = e.target.result;" +
                            "      var parsed = JSON.parse(rawText);" +
                            "      if(parsed && parsed.data) rawText = typeof parsed.data === 'string' ? parsed.data : JSON.stringify(parsed.data);" +
                            "      if(parsed && parsed.save) rawText = typeof parsed.save === 'string' ? parsed.save : JSON.stringify(parsed.save);" +
                            "      Object.defineProperty(e.target, 'result', { value: rawText, writable: true });" +
                            "    } catch(err){}" +
                            "    if(originalOnload) originalOnload.call(self, e);" +
                            "  };" +
                            "  return originalReadAsText.apply(this, arguments);" +
                            "};" +
                            "})()";
                    view.evaluateJavascript(fixImportJs, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("data:")) {
                    processAndSaveFile(url, "application/json", null);
                    return true;
                }
                if (url.startsWith("blob:")) {
                    triggerBlobDownload(url);
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, request);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url != null && url.startsWith("blob:")) {
                triggerBlobDownload(url);
            } else {
                String suggested = null;
                try {
                    suggested = URLUtil.guessFileName(url, contentDisposition, mimetype);
                } catch (Exception ignored) {
                }
                final String hint = suggested;
                Log.d(TAG, "DownloadListener 觸發，建議檔名: " + hint);
                runOnUiThread(() -> processAndSaveFile(url, "application/json", hint));
            }
        });
    }

    private void processAndSaveFile(String dataUrlOrBase64, String mimeType, String fileName) {
        if (dataUrlOrBase64 == null || dataUrlOrBase64.isEmpty()) {
            return;
        }

        if (dataUrlOrBase64.startsWith("blob:")) {
            triggerBlobDownload(dataUrlOrBase64);
            return;
        }

        try {
            byte[] bytes;

            if (dataUrlOrBase64.contains("SIG1:")) {
                String sigData = dataUrlOrBase64.substring(dataUrlOrBase64.indexOf("SIG1:")).trim();
                bytes = sigData.getBytes(StandardCharsets.UTF_8);
            } else if (dataUrlOrBase64.trim().startsWith("{") || dataUrlOrBase64.trim().startsWith("[")) {
                bytes = dataUrlOrBase64.trim().getBytes(StandardCharsets.UTF_8);
            } else if (dataUrlOrBase64.startsWith("data:")) {
                int commaIndex = dataUrlOrBase64.indexOf(",");
                if (commaIndex != -1) {
                    String header = dataUrlOrBase64.substring(0, commaIndex);
                    String content = dataUrlOrBase64.substring(commaIndex + 1);

                    if (header.contains(";base64")) {
                        bytes = Base64.decode(content, Base64.DEFAULT);
                    } else {
                        String decodedText = URLDecoder.decode(content, "UTF-8");
                        bytes = decodedText.getBytes(StandardCharsets.UTF_8);
                    }
                } else {
                    bytes = dataUrlOrBase64.getBytes(StandardCharsets.UTF_8);
                }
            } else if (dataUrlOrBase64.matches("[A-Za-z0-9+/=\\r\\n]{16,}")) {
                try {
                    bytes = Base64.decode(dataUrlOrBase64, Base64.DEFAULT);
                } catch (Exception e) {
                    bytes = dataUrlOrBase64.getBytes(StandardCharsets.UTF_8);
                }
            } else {
                bytes = dataUrlOrBase64.getBytes(StandardCharsets.UTF_8);
            }

            fileName = buildSaveFileName(fileName, bytes);
            Log.d(TAG, "最終檔名: " + fileName);

            if (writeToDownloads(bytes, fileName, "application/json")) {
                Toast.makeText(MainActivity.this, "✅ 已匯出：" + fileName, Toast.LENGTH_LONG).show();
                notifyJsExported();
            } else {
                saveViaSAF(bytes, fileName);
            }

        } catch (Exception e) {
            showDebugDialog("❌ 資料解析異常", e.toString());
        }
    }

    private boolean writeToDownloads(byte[] bytes, String fileName, String mimeType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    boolean ok = false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(bytes);
                            os.flush();
                            ok = true;
                        }
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    return ok;
                }
            } catch (Exception e) {
                Log.e(TAG, "MediaStore 寫入失敗: " + e.getMessage());
            }
            return false;
        }

        try {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(downloadsDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
                fos.flush();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Direct Write 寫入失敗: " + e.getMessage());
            return false;
        }
    }

    private void notifyJsExported() {
        mainHandler.post(() -> {
            try {
                webView.evaluateJavascript("window.__markExported && window.__markExported();", null);
            } catch (Exception ignored) {
            }
        });
    }

    private void dumpStorageDiagnostics() {
        webView.evaluateJavascript("window.__dumpStorage ? window.__dumpStorage() : 'no hook'", value -> {
            String text;
            try {
                Object parsed = new org.json.JSONTokener(value).nextValue();
                text = String.valueOf(parsed);
            } catch (Exception e) {
                text = value;
            }

            String name = "存檔診斷_" + timestamp() + ".txt";
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            boolean ok = writeToDownloads(bytes, name, "text/plain");

            Log.d(TAG, "===== 存檔診斷 =====\n" + text);
            String head = text.length() > 1500 ? text.substring(0, 1500) + "\n…（完整內容看下載的檔案）" : text;
            showDebugDialog(ok ? "診斷已存成 " + name : "診斷（存檔失敗，內容如下）", head);
        });
    }

    private void saveViaSAF(byte[] bytes, String fileName) {
        this.pendingSaveBytes = bytes;
        this.pendingSaveFileName = fileName;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);

        try {
            createDocumentLauncher.launch(intent);
        } catch (Exception e) {
            shareSaveFile(bytes, fileName);
        }
    }

    private void shareSaveFile(byte[] data, String fileName) {
        try {
            File cacheFile = new File(getCacheDir(), fileName);
            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(data);
                fos.flush();
            }

            Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", cacheFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/json");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "儲存遊戲存檔: " + fileName));
        } catch (Exception e) {
            showDebugDialog("❌ Share 分享選單失敗", e.getMessage());
        }
    }

    private String buildSaveFileName(String rawName, byte[] bytes) {
        String base = rawName == null ? "" : rawName.trim();
        base = base.replaceAll("(?i)\\.(json|txt|sav|dat|bin)$", "").trim();

        if (base.matches("(?i)(idle[_-]?lineage[_-]?save|save|savefile|download|downloadfile|export|progress|存檔|下載|進度|未命名)?")) {
            base = "";
        }
        if (base.isEmpty()) {
            base = extractCharInfo(bytes);
        }
        if (base.isEmpty()) {
            base = "存檔_" + timestamp();
        }
        if (!SAVE_NAME_PREFIX.isEmpty() && !base.startsWith(SAVE_NAME_PREFIX)) {
            base = SAVE_NAME_PREFIX + "_" + base;
        }
        return sanitizeFileName(base) + ".json";
    }

    private String mapClass(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String v = raw.trim();

        if (v.matches(".*[\u4e00-\u9fff].*")) {
            return v.length() > 6 ? v.substring(0, 6) : v;
        }

        String[] order = {"王子", "騎士", "法師", "妖精", "黑暗妖精", "幻術士", "龍騎士", "戰士"};
        if (v.matches("\\d{1,2}")) {
            int i = Integer.parseInt(v);
            if (i == 0) return order[0];
            return (i <= order.length) ? order[i - 1] : "";
        }

        String k = v.toLowerCase().replaceAll("[\\s_\\-]", "");
        switch (k) {
            case "prince": case "royal": case "king": case "royalty": return "王子";
            case "knight": case "kn": return "騎士";
            case "mage": case "wizard": case "wiz": return "法師";
            case "elf": return "妖精";
            case "darkelf": case "de": return "黑暗妖精";
            case "illusionist": case "illusion": case "il": return "幻術士";
            case "dragonknight": case "dk": return "龍騎士";
            case "warrior": case "fighter": case "wa": return "戰士";
            default: return "";
        }
    }

    private String extractCharInfo(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            String probe = text;

            int i = text.indexOf("SIG1:");
            if (i >= 0) {
                String body = text.substring(i + 5).trim();
                int colon = body.indexOf(':');
                if (colon >= 0) body = body.substring(colon + 1).trim();

                if (body.startsWith("{") || body.startsWith("[")) {
                    probe = body;
                } else {
                    String b64 = body.split("[.|,;\\s]")[0];
                    try {
                        String decoded = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
                        if (decoded.contains("{")) probe = decoded;
                    } catch (Exception ignored) {
                    }
                }
            }

            if (probe.startsWith("LZ1:")) {
                Log.d(TAG, "內容是 LZ1 壓縮格式，Java 端不解析");
                return "";
            }

            String scope = probe;
            Matcher pm = Pattern.compile("\"p\"\\s*:\\s*\\{").matcher(probe);
            if (pm.find()) {
                int from = pm.start();
                scope = probe.substring(from, Math.min(from + 3000, probe.length()));
            }

            String level = firstNumber(scope, new String[]{"charLevel", "level", "lv", "lvl"});
            if (level.isEmpty()) level = firstNumber(probe, new String[]{"charLevel", "level", "lv", "lvl"});

            String rawClass = firstMatch(scope, new String[]{"cls", "class", "charClass", "className", "job", "career"});
            if (rawClass.isEmpty()) rawClass = firstNumber(scope, new String[]{"cls", "class", "classId", "job"});
            String cls = mapClass(rawClass);
            if (cls.isEmpty()) {
                String avatar = firstMatch(scope, new String[]{"avatar"});
                if (!avatar.isEmpty()) {
                    cls = avatar.replaceAll("^[男女]", "");
                    if (cls.length() > 6) cls = cls.substring(0, 6);
                }
            }

            String out = "";
            if (!level.isEmpty()) out += level + "等";
            if (!cls.isEmpty()) out += cls;
            if (!out.isEmpty()) return out;

            return firstMatch(scope, new String[]{
                    "charName", "characterName", "playerName", "nickName", "nickname", "cname", "charname", "name"});
        } catch (Exception e) {
            Log.w(TAG, "解析角色資訊失敗: " + e.getMessage());
            return "";
        }
    }

    private String firstMatch(String text, String[] keys) {
        for (String key : keys) {
            try {
                Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"\\\\]{1,24})\"").matcher(text);
                if (m.find()) {
                    String v = m.group(1).trim();
                    if (!v.isEmpty()) return v;
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private String firstNumber(String text, String[] keys) {
        for (String key : keys) {
            try {
                Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d{1,3})").matcher(text);
                if (m.find()) return m.group(1);
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private String sanitizeFileName(String name) {
        String out = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t\\x00-\\x1f]", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^[._]+", "")
                .trim();
        if (out.length() > 80) out = out.substring(0, 80);
        return out.isEmpty() ? "存檔" : out;
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.TAIWAN).format(new Date());
    }

    private void showSlotChooser(String slotsJson) {
        try {
            JSONArray arr = new JSONArray(slotsJson);
            if (arr.length() == 0) {
                new AlertDialog.Builder(this)
                        .setTitle("找不到任何存檔")
                        .setMessage("網頁端沒有回報任何存檔欄位。按「診斷」可以把實際的存放內容倒出來檢查。")
                        .setPositiveButton("關閉", null)
                        .setNeutralButton("🔍 診斷", (d, w) -> dumpStorageDiagnostics())
                        .show();
                return;
            }

            final String[] keys = new String[arr.length()];
            final String[] labels = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                keys[i] = o.optString("key");
                String label = o.optString("label");
                if (label.isEmpty()) label = keys[i];
                labels[i] = label;
            }

            new AlertDialog.Builder(this)
                    .setTitle("要匯出哪一個角色？")
                    .setItems(labels, (dialog, which) -> {
                        String js = "window.__exportSlotByKey && window.__exportSlotByKey("
                                + JSONObject.quote(keys[which]) + ");";
                        webView.evaluateJavascript(js, null);
                    })
                    .setNegativeButton("取消", null)
                    .setNeutralButton("🔍 診斷", (d, w) -> dumpStorageDiagnostics())
                    .show();
        } catch (Exception e) {
            showDebugDialog("❌ 讀取存檔清單失敗", e.toString());
        }
    }

    private String loadSaveHookJs() {
        if (saveHookJs != null) return saveHookJs;
        try (InputStream is = getAssets().open("save_hook.js");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            saveHookJs = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "讀取 assets/save_hook.js 失敗", e);
            saveHookJs = "";
        }
        return saveHookJs;
    }

    private void showDebugDialog(String title, String message) {
        runOnUiThread(() -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("確定", null)
                    .setCancelable(false)
                    .show();
        });
    }

    private void loadNativeLauncherHtml() {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>" +
                "body{background:#121212;color:#fff;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;padding:20px;display:flex;justify-content:center;align-items:center;min-height:90vh;margin:0;}" +
                ".card{background:#1e1e1e;border-radius:16px;padding:24px;width:100%;max-width:380px;box-shadow:0 8px 24px rgba(0,0,0,0.5);text-align:center;box-sizing:border-box;}" +
                "h2{font-size:20px;margin-bottom:4px;display:flex;align-items:center;justify-content:center;gap:8px;}" +
                ".subtitle{color:#8e8e93;font-size:13px;margin-bottom:24px;}" +
                ".label{color:#4cd964;font-size:14px;text-align:left;margin-bottom:8px;font-weight:bold;}" +
                "select{width:100%;padding:12px;background:#2c2c2e;color:#fff;border:1px solid #3a3a3c;border-radius:8px;font-size:15px;margin-bottom:24px;outline:none;}" +
                ".btn-start{width:100%;padding:14px;background:#28a745;color:#fff;border:none;border-radius:8px;font-size:16px;font-weight:bold;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:8px;box-shadow:0 4px 12px rgba(40,167,69,0.3);}" +
                ".btn-start:active{background:#218838;}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "  <h2>🎮 放置天堂 旗艦版啟動器</h2>" +
                "  <div class='subtitle'>10大外掛模組 + TMEngine 防斷線引擎</div>" +
                "  <div class='label'>選擇遊戲伺服器：</div>" +
                "  <select id='serverSelect'>" +
                "    <option value='https://pp771007.github.io/idle-lineage-class/'>伺服器一 (pp771007)</option>" +
                "    <option value='https://shines871.github.io/idle-lineage-class/'>伺服器二 (shines871)</option>" +
                "  </select>" +
                "  <button class='btn-start' onclick='launchGame()'>🚀 啟動遊戲與防斷線外掛</button>" +
                "</div>" +
                "<script>" +
                "function launchGame(){" +
                "  var url = document.getElementById('serverSelect').value;" +
                "  location.href = url;" +
                "}" +
                "</script>" +
                "</body></html>";

        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    private void triggerBlobDownload(String blobUrl) {
        String js = "javascript:(function(){" +
                "var xhr=new XMLHttpRequest();" +
                "xhr.open('GET','" + blobUrl + "',true);" +
                "xhr.responseType='blob';" +
                "xhr.onload=function(){" +
                "  var reader=new FileReader();" +
                "  reader.onloadend=function(){" +
                "    var base64=reader.result.split(',')[1];" +
                "    AndroidBridge.saveBase64File(base64,'fable5_save_" + System.currentTimeMillis() + ".json');" +
                "  };" +
                "  reader.readAsDataURL(xhr.response);" +
                "};" +
                "xhr.send();" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    private void checkAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void saveBase64File(String base64Data, String fileName) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File outFile = new File(dir, fileName);
                    FileOutputStream fos = new FileOutputStream(outFile);
                    fos.write(bytes);
                    fos.close();
                    Toast.makeText(MainActivity.this,
                            "✅ 角色存檔已成功匯出至 Download 資料夾：\n" + fileName, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "❌ 匯出失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) return;
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
