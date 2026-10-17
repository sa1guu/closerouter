# CloseRouter

![CloseRouter — 把手机变成大模型 API 路由器](docs/banner.png)

**把手机变成一台大模型 API 路由器。** 手机上预设好各家大模型提供方，USB 一插（或同一局域网），电脑上的 Codex / ZCode / 任意 OpenAI 兼容工具就能直接用——API 流量全部走手机网络，电脑不需要任何外网能力，也不需要配置任何密钥。

*Turn your phone into an LLM API router. Preset providers on the phone, plug in USB (or share LAN), and any OpenAI/Anthropic-compatible tool on the PC gets working endpoints — all traffic goes through the phone's network.*

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Version](https://img.shields.io/badge/version-1.0.20-E8741C)](../../releases)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

## 特性

- **三种端点满血透传**：`/v1/chat/completions`、`/v1/responses`、`/v1/messages`，原始请求体 + SSE 流式逐块转发——客户端填**任意密钥、任意模型名**，路由器自动注入真实凭据
- **双通道**：USB（adb forward → 电脑 `127.0.0.1`）/ 局域网（同网设备直连手机 IP），**端口自定义**
- **多提供方预设**：名称 / Base URL / Key / 模型 / 协议类型，主界面下拉秒切
- **按端点测速**：一键测速会按所选提供方的协议分别请求 Chat、Responses 或 Messages 端点，显示 TTFT、tok/s 与总耗时
- **实时统计**：tok/s 吞吐、首字延迟（TTFT）、今日 token、今日请求数
- **可交互机柜**：七个功能单元与下方功能页双向联动，可左右滑动切换；点击底座抬升机柜查看 PCB，按电源启动后风扇加速、内部组件点亮，断电或抬升机柜时 AI 屏收拢熄灭；相邻点亮单元用直线直连，跨暗点或单元弹出时改用弧线，流光点密度随之调整
- **防后台杀死**：前台服务 + 常驻通知 + 唤醒锁 + 一键跳电池优化白名单
- **零命令电脑端**：APK 内置 `CloseRouter_WebUI.exe`，导出到电脑双击打开，选择 USB 或局域网模式即可连接
- 黑白极简 UI，无对话功能，只做转发

## 界面

<img src="docs/screenshot-export.jpg" width="320" alt="CloseRouter 实机界面">

机柜中的七层对应七个功能页：电源、提供方、端点、统计、测速、导出、保活。机柜与功能页共用选中状态，点击机柜单元或左右滑动页面都会同步切换。

## 使用

```
手机 CloseRouter App                         电脑
 ├─ 预设提供方 A/B/C（随时切换）               ├─ CloseRouter_WebUI.exe
 ├─ 启动转发服务  ◀── USB / LAN ──▶           │   选择 USB / 局域网并连接
 └─ 统计面板（tok/s·TTFT·今日 token）         └─ Codex/ZCode 填：
                                                   base_url = http://127.0.0.1:8788/v1
                                                   api_key  = 任意
                                                   model    = 任意
```

1. 手机安装 APK（[Releases](../../releases)），开发者选项打开 **USB 调试**
2. App 里新增提供方（协议类型：OpenAI 兼容 / Responses / Anthropic Messages），点启动
3. App 内【导出电脑助手】→ 把 `CloseRouter_WebUI.exe` 拷到电脑双击打开
4. 选择 USB 或局域网模式，连接成功后即可在任意工具里使用；局域网设备直接访问 `http://<手机IP>:<端口>/v1`

> 电脑端 WebUI 与 Android APK 内置版本保持一致。若使用旧版独立 USB 助手，仍可使用 `pc/closerouter.py` 自行构建。

## 统计口径

- token 优先取上游响应的 `usage`（三种协议形态都解析：chat 末 chunk / responses `response.completed` / messages `message_delta`）；无 usage 时按字符估算（英文 ≈4 字符/token，中文 ≈1.5）
- tok/s = 输出 tokens ÷（总耗时 − TTFT）；今日计数跨天自动清零

## 安全

- 真实 API Key 只存在手机里，电脑端永不接触；客户端密钥不校验（仅内网/本机使用场景）
- USB 模式电脑侧仅回环可达；局域网模式请只在可信网络开启
- 管理界面默认只监听本机回环地址；请勿在公网暴露转发端口

## 自行构建

```bash
# Android（JDK 17 + Android SDK，AGP 8.7 / Kotlin 2.2）
cd android && ./gradlew assembleDebug testDebugUnitTest   # 63 项 JVM 单测

# 电脑端 WebUI（Python 3.12 + PyInstaller，内嵌 adb）
# 构建脚本位于 closerouter_webui 项目，输出 CloseRouter_WebUI.exe
```

构建产物默认位于 `android/app/build/outputs/apk/debug/app-debug.apk`。`.apk` / `.exe` 不走 Git，发布时上传到 GitHub Releases。

## License

MIT。内嵌 adb 来自 Android SDK Platform Tools（Apache 2.0）。仅供学习交流。
