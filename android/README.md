# Android 分区

本分区包含 Android 客户端与 Gradle 构建配置。

- `app/`：Android 应用入口。
- `content-model/`：内容数据模型。
- `game-core/`：谱面与游戏核心逻辑及共享向量测试。

在本目录下使用 JDK 17、Gradle 8.11.1 和 Android SDK 35 构建：

```shell
gradle --no-daemon :game-core:test :app:assembleDebug :app:lintDebug
```

开发规划见 [Android 开发规划](../docs/ANDROID_DEVELOPMENT_PLAN.md)。
两端共用资料位于 [`shared/`](../shared/README.md)，内容服务位于 [`docker/`](../docker/README.md)。
