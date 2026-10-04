# Android 分区

本分区包含 Android 客户端与 Gradle 构建配置。

- `app/`：Android 应用入口。
- `content-model/`：内容数据模型。
- `game-core/`：谱面与游戏核心逻辑及共享向量测试。
- `content-local/`：安全导入辅助、本地解析及来源准备接口；app 中的 LibraryIndex 管理 SQLite 元数据缓存。

在本目录下使用 JDK 17、Gradle 8.11.1 和 Android SDK 35 构建：

```shell
./gradlew --no-daemon :content-model:test :game-core:test :app:assembleDebug :app:lintDebug
```

开发规划见 [Android 开发规划](../docs/ANDROID_DEVELOPMENT_PLAN.md)。
Windows 使用 `gradlew.bat`。设置 `JAVA_HOME` 指向 JDK 17，并设置
`ANDROID_HOME` 指向本机 SDK。Wrapper 固定 Gradle 8.11.1 并校验官方 SHA-256。
Primary 的本地工具位于仓库根目录 `.tools/`（已忽略，不提交）。
 A2 原生音频构建还需要 NDK 28.1.13356709 与 CMake 4.1.2。测试命令补充 `:content-local:test`；游玩原型的范围和限制见 [A2 验证](../docs/ANDROID_A2_VALIDATION.md)。
构建验证记录见 [A0 验证](../docs/ANDROID_A0_VALIDATION.md)。
两端共用资料位于 [`shared/`](../shared/README.md)，内容服务位于 [`docker/`](../docker/README.md)。
