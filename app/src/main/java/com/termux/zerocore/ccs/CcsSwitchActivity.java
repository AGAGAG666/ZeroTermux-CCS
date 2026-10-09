package com.termux.zerocore.ccs;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * cc-switch 完整前端的宿主页面。
 *
 * <p>与被它取代的 {@code CodexProviderActivity}（原生 Java 重写的精简版，14 文件
 * 2081 行）的区别：这里跑的是 cc-switch 上游 React 前端本体（84k 行 TS/TSX），
 * 由原生 sidecar 同源托管，所有功能——供应商管理、路由代理、模型映射、使用统计、
 * 连通性测试、S3/WebDAV 同步、技能管理——都是原版实现，不存在功能子集问题。
 *
 * <p>启动序列：
 * <ol>
 *   <li>工作线程 {@link CcsSidecar#ensureStarted()}：解包前端产物 → 起原生进程
 *       → 读 stdout 握手行拿 {@code port}/{@code token}</li>
 *   <li>主线程 {@code loadUrl("http://127.0.0.1:<port>/")}</li>
 *   <li>sidecar 在 index.html 的 {@code <head>} 里注入
 *       {@code window.__CCS_SIDECAR__ = {port, token}}，前端 shim 同步命中，
 *       无需 Java 侧 evaluateJavascript 补握手</li>
 * </ol>
 */
public class CcsSwitchActivity extends AppCompatActivity
        implements CcsHostBridge.Host, CcsSidecar.Listener {
    private static final String TAG = "CcsSwitchActivity";
    static final String EXTRA_DEEP_LINK = "com.termux.extra.CCS_DEEP_LINK";
    /** 前端请求重启时，判定「刚重启过可直接复用」的时间窗。 */
    private static final long RESTART_REUSE_WINDOW_MS = 5_000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object deepLinkLock = new Object();

    @Nullable private String pendingDeepLink;
    @Nullable private WebView web;
    @Nullable private CcsHostBridge bridge;
    @Nullable private ProgressBar spinner;
    @Nullable private TextView status;
    @Nullable private String origin;
    /**
     * 是否处于「正在重启 sidecar」的遮罩状态，用于避免自愈通知重复切 UI。
     * 只在主线程读写（{@code requestRestartSidecar} 由 WebView 的 binder 线程调用，
     * 故所有变更都经 {@code main.post} 归拢到主线程，无需额外同步）。
     */
    private boolean restarting;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        acceptDeepLinkIntent(getIntent());

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF111827);

        WebView view = new WebView(this);
        view.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.setVisibility(View.INVISIBLE);
        web = view;

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // 前端由 sidecar 通过 http://127.0.0.1 提供，与 /rpc 同源，
        // 因此不需要放开 file 源的跨源访问（那是明确的安全风险）。
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // 保持 WebView 的 CSS 像素布局和上游 CCS 页面样式，不注入额外 UI 覆盖层。
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        bridge = new CcsHostBridge(this, this);
        view.addJavascriptInterface(bridge, "__CCS_NATIVE__");
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String url = req.getUrl() != null ? req.getUrl().toString() : "";
                // 只允许在 WebView 内导航到 sidecar 自己的源；外链交给系统浏览器，
                // 避免第三方页面进到持有 __CCS_NATIVE__ 的这个 WebView 里。
                if (origin != null && url.startsWith(origin)) return false;
                if (bridge != null) bridge.openUrl(url);
                return true;
            }

            @Override public void onPageFinished(WebView v, String url) {
                v.setVisibility(View.VISIBLE);
                if (spinner != null) spinner.setVisibility(View.GONE);
                if (status != null) status.setVisibility(View.GONE);
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.i(TAG, "web[" + msg.messageLevel() + "] " + msg.message()
                    + " @" + msg.sourceId() + ":" + msg.lineNumber());
                return true;
            }
        });

        ProgressBar bar = new ProgressBar(this);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.gravity = android.view.Gravity.CENTER;
        bar.setLayoutParams(barParams);
        spinner = bar;

        TextView text = new TextView(this);
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM;
        textParams.bottomMargin = 120;
        text.setLayoutParams(textParams);
        text.setTextColor(0xFFE5E7EB);
        text.setTextSize(14f);
        text.setPadding(48, 0, 48, 0);
        text.setText("正在启动 CC Switch 服务…");
        status = text;

        root.addView(view);
        root.addView(bar);
        root.addView(text);
        setContentView(root);

        // 注册要早于启动：首启期间若 sidecar 立刻按 51 退出，自愈后的通知也不该漏掉。
        CcsSidecar.get(this).addListener(this);
        startSidecar();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptDeepLinkIntent(intent);
    }

    private void acceptDeepLinkIntent(@Nullable Intent intent) {
        if (intent == null) return;
        String url = intent.getStringExtra(EXTRA_DEEP_LINK);
        intent.removeExtra(EXTRA_DEEP_LINK);
        if (!CcsDeepLinkValidator.isSupported(url)) return;

        synchronized (deepLinkLock) {
            // 确认弹窗同一时间只能展示一条；连续点击时保留最新链接，避免旧链接滞留。
            pendingDeepLink = url;
        }
        notifyPendingDeepLink();
    }

    /**
     * 不把 URL 拼入 JavaScript，避免 query 中的 apiKey 进入控制台或脚本错误；
     * 前端只收到一个“现在可以拉取”的通知，实际值经 JavascriptInterface 一次性读取。
     */
    private void notifyPendingDeepLink() {
        main.post(() -> {
            if (web == null) return;
            web.evaluateJavascript(
                "window.__ccsPullPendingDeepLink&&window.__ccsPullPendingDeepLink();",
                null);
        });
    }

    private void startSidecar() {
        // 先挂前台服务：sidecar 同时承载路由代理，必须能在本页面关闭后继续存活。
        // 两条路径都调同一个单例的 ensureStarted()，由其内部锁串行化，先到者启动、
        // 后到者复用，不会起两个进程。
        CcsSidecarService.start(this);
        worker.execute(() -> {
            try {
                CcsSidecar.Handshake h = CcsSidecar.get(this).ensureStarted();
                main.post(() -> {
                    if (status != null) status.setText("正在加载界面…");
                    load(h);
                });
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "sidecar 启动失败", e);
                main.post(() -> showFailure(e));
            }
        });
    }

    private void showFailure(Exception e) {
        if (spinner != null) spinner.setVisibility(View.GONE);
        if (status != null) {
            status.setText("CC Switch 启动失败：\n" + e.getMessage());
        }
    }

    /*
     * 注：曾经在 onPageFinished 里 evaluateJavascript 一段 JS，把同步的
     * __CCS_NATIVE__ 包成前端要的 window.__CCS_HOST__。现已移除，改由前端
     * android-bridge/runtime.ts 的 resolveHost() 自行合成，理由有两条：
     *
     * 1) 时序：onPageFinished 在 load 之后，而 __CCS_NATIVE__ 从脚本执行首行就
     *    可用；由前端合成可彻底摆脱注入时点，WebView 重载也不会出现空窗。
     * 2) 单一所有权：目录选择的 requestId -> resolver 表只能有一份。两边各装一次
     *    window.__ccsResolveFolderPick，后装的会顶掉前装的，导致前端已挂起的
     *    pickFolder Promise 永远不 resolve。
     *
     * 宿主这边只负责：注入 __CCS_NATIVE__（见 addJavascriptInterface），以及在
     * 目录选择完成后调 window.__ccsResolveFolderPick(requestId, path)。
     */

    // ── CcsHostBridge.Host ──────────────────────────────────────

    @Override public void requestFolderPick(int requestId) {
        // 不走 SAF：它返回 content:// 树 Uri，而 cc-switch 要的是能写进配置、
        // 被 Termux 里 CLI 直接使用的真实路径，且目标目录都在 Termux 私有目录内，
        // 外部选择器看不见。改用 CcsDirectoryPicker（基于 File 的浏览器）。
        main.post(() -> {
            if (isFinishing() || isDestroyed()) {
                resolveFolderPick(requestId, null);
                return;
            }
            CcsDirectoryPicker.show(this, null,
                path -> resolveFolderPick(requestId, path));
        });
    }

    /** 把选择结果回送给前端挂起的 Promise。{@code null} 等价用户取消。 */
    private void resolveFolderPick(int requestId, @Nullable String path) {
        main.post(() -> {
            if (web == null) return;
            String arg = path == null ? "null" : "\"" + jsEscape(path) + "\"";
            web.evaluateJavascript(
                "window.__ccsResolveFolderPick&&window.__ccsResolveFolderPick("
                    + requestId + "," + arg + ");", null);
        });
    }

    /** 路径进 JS 字符串字面量前的转义。Termux 路径不含引号，但不能假定。 */
    private static String jsEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"':  b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\u2028': b.append("\\u2028"); break;
                case '\u2029': b.append("\\u2029"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    @Nullable
    @Override public String takePendingDeepLink() {
        synchronized (deepLinkLock) {
            String url = pendingDeepLink;
            pendingDeepLink = null;
            return url;
        }
    }

    @Override public void requestClose() {
        main.post(this::finish);
    }

    @Override public void requestMinimize() {
        main.post(() -> moveTaskToBack(true));
    }

    @Override public void requestRestartSidecar() {
        showRestarting("正在重启 CC Switch 服务…");
        worker.execute(() -> {
            try {
                // 走 restartOrReuse 而不是 shutdown()+ensureStarted()：后者两步之间有
                // 空窗，会与守护线程的自愈重启撞车起出两个进程；带新鲜度窗口则能吸收
                // 「sidecar 已按 51 退出并被守护线程拉起，SSE 通知随后才到」这一重复动作。
                // 新端口统一由 onSidecarReady 送达（重启与复用两条分支都会回调），
                // 这里不再自行 loadUrl，避免两次 loadUrl 互相打断。
                CcsSidecar.get(this).restartOrReuse(RESTART_REUSE_WINDOW_MS);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "sidecar 重启失败", e);
                main.post(() -> showFailure(e));
            }
        });
    }

    // ── CcsSidecar.Listener ─────────────────────────────────────

    /**
     * sidecar 换端口重生后重载页面。
     *
     * <p>触发来源有两种：前端设置页点重启（{@code restart_app} → 以 51 退出），
     * 以及 sidecar 意外崩溃后守护线程自愈。两者对 WebView 的影响相同——旧端口失效，
     * 已建立的 SSE 断开，必须整页重载才能拿到新的 {@code window.__CCS_SIDECAR__}。
     */
    @Override public void onSidecarReady(CcsSidecar.Handshake handshake) {
        main.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (!restarting) showRestarting("CC Switch 服务已重启，正在重新加载…");
            load(handshake);
        });
    }

    @Override public void onSidecarLost(String message) {
        main.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            restarting = false;
            if (spinner != null) spinner.setVisibility(View.GONE);
            if (web != null) web.setVisibility(View.INVISIBLE);
            if (status != null) {
                status.setVisibility(View.VISIBLE);
                status.setText(message);
            }
        });
    }

    /** 主线程：把页面切到遮罩态。 */
    private void showRestarting(String text) {
        main.post(() -> {
            restarting = true;
            if (status != null) {
                status.setVisibility(View.VISIBLE);
                status.setText(text);
            }
            if (spinner != null) spinner.setVisibility(View.VISIBLE);
            if (web != null) web.setVisibility(View.INVISIBLE);
        });
    }

    /** 主线程：按握手信息切换源并加载。 */
    private void load(CcsSidecar.Handshake handshake) {
        origin = "http://127.0.0.1:" + handshake.port;
        restarting = false;
        if (web != null) web.loadUrl(handshake.url);
    }

    // ── 生命周期 ────────────────────────────────────────────────

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        CcsSidecar.get(this).removeListener(this);
        // sidecar 故意不随页面关闭而停止：它同时承载路由代理（codex/opencode 的
        // 上游转换），终端里的 CLI 仍在用。停止由 CcsSidecarService 统一负责。
        if (web != null) {
            web.setWebChromeClient(null);
            web.destroy();
            web = null;
        }
        worker.shutdownNow();
        super.onDestroy();
    }
}
