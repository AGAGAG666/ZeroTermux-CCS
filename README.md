# ZeroTermux-CCS

ZeroTermux-CCS 是 ZeroTermux 的非官方 Android 定制版本，把 CC Switch 的 Web 页面、provider 管理、本地路由、Skills 和 Termux 集成在同一个 APK 中。本项目与 ZeroTermux 上游、CC Switch 上游和 OpenAI Codex 均无从属关系，也未获得其背书。

## 当前正式版本

- 应用版本：`0.118.3.63`
- ZeroTermux 目标上游：`fd6d21418843bafa2c5c16885f13c796969387a2`
- 当前正式 Release 目标提交：`226a1f44dc0461376aebca5b38be57728f2a8c0c`
- CC Switch 原版上游：`v3.20.0`
- CC Switch Android 资产：`ccs-android-f3291ac6`
- ZeroTermux-CCS Release：`ccs-f3291ac6`
- APK：`ZeroTermux-0.118.3.63-release_arm64-v8a.apk`
- 当前 CCS sidecar 架构：`arm64-v8a`

正式 APK 从本仓库 [Releases](https://github.com/AGAGAG666/ZeroTermux-CCS/releases) 获取。

## 使用 CC Switch

从 ZeroTermux 主菜单打开 **CC Switch**。宿主会启动 sidecar 前台服务，并在 WebView 中加载 CC Switch 页面。

### 本地端口

- Web 页面默认端口：`17132`，可在 ZeroTermux 设置中修改；浏览器可访问 `http://localhost:17132/`。
- Codex/OpenCode 路由代理端口：`15721`，本地客户端通常访问 `http://127.0.0.1:15721/v1`。

`17132` 是 Web 端口，不是路由端口。

### `ccswitch://v1/import`

当前版本支持从浏览器或其他应用打开 `ccswitch://v1/import`。Android 入口只负责校验和转发，CC Switch 仍使用原有解析与确认弹窗；用户确认后才写入 provider、prompt、MCP 或 Skill。

导入链接可能包含 `apiKey`。不要把完整 URI 放进日志、截图、Issue 或文档；已公开的 key 应立即轮换。

### Provider 与路由

CC Switch 使用 Termux 中已有的配置目录：

| 路径 | 用途 |
| --- | --- |
| `~/.cc-switch/` | CC Switch 数据库、provider 和运行设置 |
| `~/.codex/config.toml` | Codex 配置 |
| `~/.codex/auth.json` | Codex 认证信息 |
| `~/.claude/` | Claude Code 配置 |

供应商上游使用 OpenAI Chat Completions 或 Anthropic Messages 时，可由本地路由转换为 Codex Responses；上游本身支持 Responses 时可直接转发。协议选择以 provider 的 `apiFormat` 为准，不根据模型名称或 URL 猜测。

### Skills

Skills 管理页沿用 CC Switch 页面。Android 构建只增加窄屏和触控适配：顶部操作允许换行，Skill 名称/描述与应用开关分层显示，不另写原生 Skills UI。

### Codex 终端

Codex 继续在普通 Termux 会话中运行：

```bash
codex
```

当前项目不维护独立 Codex 专属终端或自定义 Codex 历史列表。会话恢复和切换使用 Codex CLI 自身功能。

## 架构

```text
ZeroTermux APK
├─ Termux 终端
├─ CcsDeepLinkActivity        # 公开、仅接收 ccswitch://v1/import
├─ CcsSwitchActivity          # 私有 WebView 宿主
├─ CcsSidecarService          # 前台服务
├─ lib/arm64-v8a/libccsidecar.so
└─ assets/ccs-web.zip
         ↓
CC Switch sidecar
├─ Web / RPC / SSE
├─ 本地路由代理
├─ Responses / Chat / Anthropic 转换
└─ SQLite 数据库 ~/.cc-switch/cc-switch.db
```

`CcsSwitchActivity` 保持 `exported=false`。外部 deep link 先进入 `CcsDeepLinkActivity`，校验后通过一次性 bridge 交给前端；完整 URI 不进入 Log 或 JavaScript 字符串。

APK 中的 `libccsidecar.so` 和 `ccs-web.zip` 由 `app/build.gradle` 按 Release Tag、文件大小和 SHA-256 下载校验。Web 包哈希还用于隔离覆盖安装后的旧 Web 缓存。

## 当前资产

| 文件 | 大小 | SHA-256 |
| --- | ---: | --- |
| `ccs-web.zip` | 2,080,953 bytes | `5b446fa6eda7ab209b7ffa5dabb731b363a8dfd55fc82f56ba9b459d362b021b` |
| `libccsidecar.so` | 17,133,944 bytes | `668330a171b699704c380ae979d146d9ae9d99ecb630fca05fa17d922af040f4` |
| `ZeroTermux-0.118.3.63-release_arm64-v8a.apk` | 92,855,139 bytes | `92fba34afe7df43fe6735a6cfc82250096e4d59b2f6da4a33519220fcb497df3` |

APK 内两个 CCS 资产已与 CC Switch Release 和 `app/build.gradle` 三方比对一致。

## 主要源码位置

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/com/termux/zerocore/ccs/CcsSidecar.java` | sidecar 生命周期、前端解包和握手 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsSidecarService.java` | 前台服务 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsSwitchActivity.java` | 私有 WebView 宿主和 deep-link 队列 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsDeepLinkActivity.java` | 自定义 scheme 入口和转发 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsDeepLinkValidator.java` | URI 范围校验 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsHostBridge.java` | JavaScript 与 Android bridge |
| `app/src/main/java/com/termux/zerocore/ccs/CcsDirectoryPicker.java` | Termux 私有目录选择器 |
| `app/build.gradle` | CCS 资产锁定、bootstrap、APK 集成 |

## 构建与发布

Android APK 统一使用 GitHub Actions。当前仓库和分支：

```bash
gh workflow run build.yml \
  --repo AGAGAG666/ZeroTermux-CCS \
  --ref feature/ccs-full-port \
  -f arch=arm64 \
  -f build_debug=false \
  -f build_release=true
```

正式发布前必须检查：

- Workflow 最终结论；
- APK 包名、版本和 ABI；
- Manifest 中的 `ccswitch://v1/import`；
- APK 内 `ccs-web.zip` 和 `libccsidecar.so`；
- Release、Gradle、APK 内文件三方大小和 SHA-256；
- 手机上的 deep link 和相关 UI。

`downloadBootstraps` 必须先于 `configureNdkBuild*`、`buildNdkBuild*`。否则并行 Gradle 在冷缓存时可能先进入 NDK，并因缺少 `bootstrap-ARCH.zip` 失败。

## 已验证基线

- Android Frontend Build：`37899238675`，成功；
- Android Sidecar Build：`37899238650`，成功；
- Build APK：`37912713632`，成功；
- 真机：`ccswitch://v1/import` 可用；
- 真机：Skills 管理页窄屏显示正常。

本轮没有重新覆盖与改动无关的所有 provider、路由、文件提供器和错误码场景；修改相应区域时应重新取得对应证据。

## 常见问题

| 现象 | 先检查什么 |
| --- | --- |
| Web 页面打不开 | 当前 Web 端口、sidecar 前台服务和 `/health` |
| `ccswitch://` 无法拉起 | 安装版本是否含 `CcsDeepLinkActivity`，URI 是否精确匹配 `/v1/import` |
| 能拉起但没有确认框 | APK 中 Web 包是否含 deep-link bridge，sidecar 是否注册 `parse_deeplink` |
| 请求没有走本地代理 | 客户端是否指向 `127.0.0.1:15721/v1`，路由开关和 provider 是否启用 |
| 上游返回 400/401/403 | `apiFormat`、模型映射、转换后请求和上游响应 `cause` |
| 文件选择失败 | 路径是否位于 Termux 可访问的真实文件系统范围 |

## 文档范围

本仓库的 README/AGENTS 记录源码仓库当前实现与开发规则。外部项目架构、跨仓库 SOP 和交接状态位于 `~/工作区/codex/Termux/`。收到笼统的“更新文档”请求时，先确认更新仓库文档还是外部交接文档。

## 许可证

本项目整体使用 GPLv3-only，见 [LICENSE.md](LICENSE.md)。第三方代码和许可证见 [CREDITS.md](CREDITS.md)。上游 README 保留在 [README.upstream.md](README.upstream.md)。
