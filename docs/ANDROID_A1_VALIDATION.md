# A1 本地导入原型（2026-10-04）

已实现系统 ZIP 和 SAF 目录选择、复制到应用私有目录、离线 `.sm/.ssc`
元数据与 dance-single 谱面解析、SSC 优先及 chart timing 覆盖、难度/等级展示、
封面限尺寸后台读取、音频准备检查、重复曲包识别、重启后曲库扫描和应用内曲包移除。
原始文件不被修改，服务器仍关闭，无网络权限，无 WebView。

导入上限为 10000 条目、512 MiB、目录递归 32 层、单谱面 4 MiB。
ZIP 路径越界、损坏 ZIP、缺失音乐和坏谱面以可恢复错误报告。
导入在私有临时目录完成后按内容摘要保存，失败清理本次临时目录。

## 本机验证

- 首轮 `:content-model:test :content-local:test :game-core:test :app:assembleDebug
  :app:lintDebug` 全部通过，1m20s；共 6 个测试方法，其中谱面测试覆盖 5 组共享向量。
- 新增 3 个本地测试：SSC chart timing、ZIP 路径穿越与损坏输入、坏歌隔离及资源准备。
- 最终 UI 修复后再次构建与 lint 通过，1m；0 错误、23 条 lint 警告，未抑制。
- API 35 模拟器：系统 ZIP 选择→导入一首原创测试歌→准备谱面和 WAV 音轨成功。
- 强制停止再启动后，曲库仍有一首歌。
- 系统目录选择→同一曲包识别为重复，未增加第二条。
- 最终 APK 安装、启动、准备再次成功；移除确认后曲库为空，外部 song.sm 仍存在。
- AndroidRuntime 错误日志为空。截图：`android-evidence/a1-prepared.png`。

## APK 与原创样例

`artifacts/android/foxdroid-a1-20261004-debug.apk`：0.2.0-a1，versionCode 2。
SHA-256：`4a5f9c1746cbc43b941b3bf79c6060b0800ca0eac7bc187bec32c31f1bee28a9`。
`artifacts/android/original-tone-test.zip` 为本次自动生成的 440 Hz WAV 与原创
单 Tap 谱面，不含用户曲包或第三方录音，仅用于导入/准备验证。

## 尚未完成与边界

A1 为可验收原型，未宣称全部退出条件完成：真机 ZIP/目录提供者、飞行模式、
存储不足与导入中旋转/进程回收尚未验证。当前以应用管理文件目录重扫代替数据库索引；
统一 ContentSource、持久化索引/迁移、大曲库性能、完整黄金库兼容测试仍待补齐。
内容更新暂作为新摘要曲包导入，可移除旧曲包；没有自动替换外部来源更新。
导入资源限制不等于完整 ZIP 安全审计。

音频准备仅验证存在、可读及 MediaExtractor 可识别音轨，未进行完整解码或播放。
A2 需建立 PreparedSong 运行时交接、原生音频和判定；当前 APK 不能游玩。
系统旋转提示按钮偏好仍记录在 A0 文档，尚未改变系统方向策略。
