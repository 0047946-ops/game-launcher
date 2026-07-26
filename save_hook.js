(function() {
    'use strict';

    if (window.__IDLE_SAVE_HOOK_LOADED__) return;
    window.__IDLE_SAVE_HOOK_LOADED__ = true;

    console.log("🚀 [SaveHook] 線上雙向存檔腳本注入成功！");

    // 1. 攔截網頁觸發的 Blob / Base64 下載，拋給 Android 原生層
    const originalCreateObjectURL = URL.createObjectURL;
    URL.createObjectURL = function(blob) {
        const url = originalCreateObjectURL.apply(this, arguments);
        if (blob && (blob.type.includes('json') || blob.type.includes('text') || blob.type.includes('octet-stream'))) {
            const reader = new FileReader();
            reader.onloadend = function() {
                const base64data = reader.result;
                if (window.Android && window.Android.saveBase64File) {
                    window.Android.saveBase64File(base64data, blob.type || 'application/json', 'idle_save.json');
                }
            };
            reader.readAsDataURL(blob);
        }
        return url;
    };

    // 2. 提供給 Android 原生層的備援萬用 Dump 機制
    window.__dumpAllLocalStorage = function() {
        let dump = {};
        for (let i = 0; i < localStorage.length; i++) {
            let key = localStorage.key(i);
            dump[key] = localStorage.getItem(key);
        }
        return JSON.stringify(dump);
    };

    if (window.Android && window.Android.log) {
        window.Android.log("SaveHook Ready");
    }
})();
