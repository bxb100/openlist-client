# OpenList for Android

一个面向自托管 OpenList 的原生 Android 客户端。项目使用 Kotlin、Jetpack Compose 和 Material Design 3，从 OpenList v4.2.5 的服务端路由源码建立接口契约，而不是依赖不完整的 Apifox 导出。

## 目标能力

- 动态配置任意 OpenList 实例（包括反向代理子路径），支持 OTP、多账户添加/编辑/切换与裸 `Authorization` token。
- 浏览、搜索和管理文件；受密码保护目录可在应用内解锁，密码只保留在进程内存。
- 音频、视频与图片按同目录同类型自动形成播放队列或 Gallery；音视频统一使用增强的 Media3 播放链路，支持 HLS、MKV、WMA、WMV、同目录字幕、视频全屏、后台播放、媒体通知、锁屏与耳机控制。
- OpenList v4.2.5 multipart 会话上传，可查询已接收分片并从缺块继续；旧服务自动回退流式上传。后台任务使用不可变的应用私有暂存副本，重试不会混入文件提供方后来修改的内容。
- 通过 Android 文档选择器下载到用户指定的本地位置，后台显示进度并将任务绑定到发起时的账户会话。
- 统一可配置磁盘缓存：最大空间、滑动过期时间和最大条目数。
- 强类型核心 API、参数化任务 API、动态管理员 API，以及精确覆盖 199 个 v4.2.5 `/api` 路径的路由目录和常用非 REST 传输入口。

## 构建

需要 JDK 17 或更新版本、Android SDK Platform 37。仓库包含 Gradle Wrapper：

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleRelease
```

构建会按原生 ABI 分别输出 APK，避免把两套 FFmpeg 库同时交付给一台设备：

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk`
- `app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`
- `app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk`

现代 64 位 ARM 手机使用 `arm64-v8a` 产物；release APK 需要使用发行证书签名后安装。

## 兼容基线

- OpenList：v4.2.5（路由基线提交 `cc87e88f038a5a27c8782afc7b66a3c1a3cdcb77`）
- Android：minSdk 24，targetSdk 37；FongMi FFmpeg 原生库当前仅提供 `arm64-v8a` 与 `armeabi-v7a`
- AGP 9.3.1 / Gradle 9.5 / Kotlin 2.4.10 / Compose BOM 2026.08 / Media3 1.11（固定 FongMi `release-1.11.0-fongmi` 提交，含 ASF 与 FFmpeg 音视频 renderer）/ Coil 3.6

增强 Media3 以本地 Maven 仓库形式固定在 `third_party/media3-fongmi`。其中 FFmpeg
二进制为 GPLv3+ 构建；对外分发前必须完成 GPLv3、第三方 BOM、许可证和对应源码合规。

OpenList 通常用 HTTP 200 包装业务错误，客户端始终同时检查 HTTP 状态和 JSON `code`。REST 鉴权头是原始 token，不添加 `Bearer`。下载直链可能属于第三方域名，API token 不会被全局注入这些请求。

新连接默认使用 HTTP 和 OpenList 的 5244 端口；明文 HTTP 只有在账户配置明确允许时才会使用。公网部署强烈推荐 HTTPS，因为 HTTP 会以明文传输账号、密码和令牌。

## 上传边界

multipart 会话由 OpenList 服务进程保存在内存中，默认不活跃 30 分钟后回收，服务重启也会丢失。客户端会持久化本地 checkpoint 并查询服务端 `received` 区间；会话丢失时重新初始化并安全重传。任务绑定发起时的服务器、账号、安全策略和 token，登出或切换身份会阻止后续分片继续发送。旧版本的 `/api/fs/put` 没有协议级续传能力，回退时会明确显示为整文件重传。

更多设计说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 和 [docs/API_COVERAGE.md](docs/API_COVERAGE.md)。
