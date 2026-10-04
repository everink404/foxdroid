# FoxDroid

FoxDroid 是一个面向家庭局域网的 StepMania 兼容节奏游戏项目。当前优先级是 Android 手机端、桌面 Web 端和可选的 NAS 内容服务；Android TV 与跳舞毯支持延后。

目前已进入内容服务开发阶段。服务端可以扫描 `.sm`/标准 `.ssc` 曲库，将目录转换为 SQLite 索引，并通过 HTTP 向内置 Web 页面和未来的 Android 客户端提供清单、解析后的谱面与媒体资源。

## 仓库分区

- [`docker/`](docker/README.md)：NAS 内容服务、桌面 Web 客户端和 Docker 部署；服务端源码位于 `docker/server/`。
- [`android/`](android/README.md)：Android 客户端，包含应用、内容模型和游戏核心模块。
- `shared/`：两端共用的 API 契约、测试向量和黄金曲库。
- `docs/`：项目需求、规划和验收资料。

从仓库根目录启动 Docker 服务：`docker compose -f docker/docker-compose.yml up --build`。

## 项目资料

- [当前进展与后续规划](docs/PROJECT_STATUS.md)
- [验收清单](docs/ACCEPTANCE_CHECKLIST.md)
- [产品需求](docs/PRD.md)
- [技术路线](docs/TECHNICAL_ROUTE.md)
- [开发规划](docs/DEVELOPMENT_PLAN.md)
- [服务端说明](docker/server/README.md)

## 当前实现

- 只读曲库扫描与单曲错误隔离
- SQLite 内容索引和稳定资源 ID
- 版本化清单、条件缓存、歌曲、谱面、分段素材及扫描接口
- Web 曲库搜索、选歌、谱面选择和资源预加载
- 基于 Web Audio 时钟的四轨 Tap Note 游玩、键盘判定和结算
- 浏览器本地判定/视觉偏移校准与高精度键盘事件时间戳
- 共享谱面测试向量和类型化 OpenAPI v1 草案快照
- 扫描互斥、耗时状态，以及失焦/音频中断后的安全终止
- Docker Compose 部署定义

当前 Web 纵向切片可游玩 Tap、Hold、Roll 和 Mine，并覆盖 BPM 变化、Stop、Delay、Warp 与基础校准。持续音符和 Mine 已接入轨道显示、实时键盘状态与结算，但正式计分和更完整的 StepMania 主题规则仍待实现，因此不能把当前结果视为完整 StepMania 兼容计分。
