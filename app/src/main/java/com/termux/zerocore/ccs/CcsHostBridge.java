package com.termux.zerocore.ccs;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * 注入 WebView 的宿主能力面，对应桌面 cc-switch 里由 Tauri 插件提供的那部分。
 *
 * <p>映射关系：
 * <table>
 *   <tr><td>{@code tauri-plugin-opener}</td><td>{@link #openUrl}/{@link #openPath}</td></tr>
 *   <tr><td>{@code tauri-plugin-dialog} 目录选择</td><td>{@link #pickFolder}（异步回调）</td></tr>
 *   <tr><td>{@code tauri-plugin-process} exit/restart</td><td>{@link #exit}/{@link #restart}</td></tr>
 *   <tr><td>窗口最小化/关闭</td><td>{@link #minimize}/{@link #close}</td></tr>
 * </table>
 *
 * <p>注入名为 {@code __CCS_NATIVE__}；前端 {@code android-bridge/runtime.ts} 会把它
 * 包装成 {@code window.__CCS_HOST__}（补上 Promise 语义）。之所以不直接注入
 * {@code __CCS_HOST__}：{@code @JavascriptInterface} 方法不能返回 Promise，
 * 而 {@code pickFolder} 必须是异步的，需要一层 JS 侧适配。
 */
public final class CcsHostBridge {
    private static final String TAG = "CcsHostBridge";

    /** 前端发起的目录选择请求 id → 由 Activity 完成后回调 JS。 */
    public interface Host {
        void requestFolderPick(int requestId);
        @Nullable String takePendingDeepLink();
        void requestClose();
        void requestMinimize();
        void requestRestartSidecar();
    }

    private final Activity activity;
    private final Host host;

    CcsHostBridge(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    @JavascriptInterface
    public void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            Log.w(TAG, "无法解析 URL: " + url, e);
            return;
        }
        String scheme = uri.getScheme();
        // 只放行 http/https/mailto：sidecar 转发过来的 URL 最终来自供应商配置，
        // 属可被用户数据影响的输入，不能拿它去起任意 scheme 的 Intent。
        if (scheme == null
            || !(scheme.equalsIgnoreCase("http")
                 || scheme.equalsIgnoreCase("https")
                 || scheme.equalsIgnoreCase("mailto"))) {
            Log.w(TAG, "拒绝打开非 http/https/mailto 链接: " + url);
            toast("已拦截不受支持的链接");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast("没有可以打开链接的应用");
        }
    }

    @JavascriptInterface
    public void openPath(String path) {
        if (path == null || path.isEmpty()) return;
        // Termux 私有目录对外部 App 不可见，用 ACTION_VIEW 打开注定失败。
        // 这里改为把路径复制到剪贴板并提示，比弹一个必然失败的选择器有用。
        File file = new File(path);
        android.content.ClipboardManager cm =
            (android.content.ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("path", file.getAbsolutePath()));
        }
        toast("路径已复制：" + file.getAbsolutePath());
    }

    @JavascriptInterface
    public void pickFolder(int requestId) {
        host.requestFolderPick(requestId);
    }

    @JavascriptInterface
    public String homeDir() {
        return com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    @Nullable
    @JavascriptInterface
    public String takePendingDeepLink() {
        return host.takePendingDeepLink();
    }

    @JavascriptInterface
    public void restart() {
        host.requestRestartSidecar();
    }

    @JavascriptInterface
    public void exit(int code) {
        Log.i(TAG, "前端请求退出 code=" + code);
        host.requestClose();
    }

    @JavascriptInterface
    public void minimize() {
        host.requestMinimize();
    }

    @JavascriptInterface
    public void close() {
        host.requestClose();
    }

    @JavascriptInterface
    public void toast(@Nullable String text) {
        if (text == null || text.isEmpty()) return;
        activity.runOnUiThread(() ->
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
    }
}
