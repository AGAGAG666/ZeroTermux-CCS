# ZeroTermux-CCS

ZeroTermux-CCS 是基于 [ZeroTermux](https://github.com/hanxinhao000/ZeroTermux) 定制的 Android 终端应用，集成 [CC Switch](https://github.com/farion1231/cc-switch)，方便在手机上管理 AI 编程工具的供应商、模型和本地代理。

## 项目特点

- **终端与配置管理结合**：在 Termux 终端中运行命令行工具，通过 CC Switch 界面管理相关配置。
- **统一管理供应商**：集中维护不同 AI 工具使用的接口和模型配置。
- **本地路由代理**：为 Codex 等工具提供请求转发与协议转换。
- **手机界面适配**：针对触控和竖屏调整 CC Switch 的页面布局。

## 使用入口

从 ZeroTermux 主菜单打开 **CC Switch**，添加供应商并配置模型、路由等选项。命令行工具仍在普通终端会话中使用。

## 下载

安装包见本仓库的 [Releases](https://github.com/AGAGAG666/ZeroTermux-CCS/releases)。

## 上游项目

- [ZeroTermux](https://github.com/hanxinhao000/ZeroTermux)：本项目的 Android 终端基础。
- [CC Switch](https://github.com/farion1231/cc-switch)：AI 工具配置管理与本地代理。

本项目为非官方定制版本，与上述上游项目及 OpenAI Codex 无从属关系，亦未获得其背书。

## 许可证

本项目使用 GPLv3-only，见 [LICENSE.md](LICENSE.md)。第三方信息见 [CREDITS.md](CREDITS.md)，上游介绍见 [README.upstream.md](README.upstream.md)。
