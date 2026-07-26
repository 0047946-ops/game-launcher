package com.idle.lineage.launcher;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private final static int FILE_CHOOSER_RESULT_CODE = 10001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        checkAllFilesAccessPermission();

        webView = new WebView(this);
        setContentView(webView);

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

        // 允許背景播放音訊（防止 Android WebView 自動凍結 AudioContext 離線背景流）
        settings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // 監聽原生下載（處理標準 Blob 與一般存檔下載）
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                if (url.startsWith("blob:")) {
                    triggerBlobDownload(url);
                } else {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

                    String fileName = guessFileName(contentDisposition, url);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    dm.enqueue(request);
                    Toast.makeText(this, "📥 已開始下載存檔：" + fileName, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "❌ 下載失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        // 接管檔案選擇器，支援讀取本地存檔匯入
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                              FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    triggerBlobDownload(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Toast.makeText(MainActivity.this, "⚠️ 網址連線失敗 (404)，請確認網路狀態", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                
                if (url != null && url.startsWith("http")) {
                    // 1. 自動注入【10 大外掛模組】(含主外掛 + 9個書籤模組)
                    String totalPluginJs = "(function () {" +
                            "'use strict';" +
                            "if(window.__all_plugins_loaded) return;" +
                            "window.__all_plugins_loaded = true;" +
                            
                            // 先載入原本的主外掛
                            "var s0 = document.createElement('script');" +
                            "s0.src = 'https://cdn.jsdelivr.net/gh/qcc781192000/idle-lineage-plugin@main/main.user.js?v=' + Date.now();" +
                            "document.body.appendChild(s0);" +

                            // 接著依序載入 9 個書籤模組
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

                    // 2. 自動注入【TMEngine v106.0 全域極致整合防斷線引擎】
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

                    // 3. 核心檔案讀取修復：確保匯入不同來源/外掛格式的存檔時能自動解析相容
                    String fixImportJs =
                        "(function(){" +
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
        });

        loadNativeLauncherHtml();
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

    private String guessFileName(String contentDisposition, String url) {
        String fileName = "fable5_save_" + System.currentTimeMillis() + ".json";
        try {
            if (contentDisposition != null && contentDisposition.contains("filename=")) {
                fileName = contentDisposition.split("filename=")[1].replace("\"", "").trim();
            } else if (url != null && url.contains("/")) {
                String last = url.substring(url.lastIndexOf('/') + 1);
                if (last.length() > 0 && last.length() < 100) fileName = last;
            }
        } catch (Exception ignored) {}
        return fileName;
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
