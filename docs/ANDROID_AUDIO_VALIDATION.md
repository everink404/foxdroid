# Android 音频准备优化：第一轮

2026-10-04，0.5.0-audio / versionCode 8。状态：WAV/MP3 首次准备与三种格式缓存复用已在模拟器验证；OGG 首次等待及真机量化验收未完成。0.4.2 最小 MVP 基线保持固定。

## 实现与边界

- 普通 PCM16 RIFF WAV 直接分块转为双声道 float PCM；校验 RIFF 区块、完整帧、采样率、声道和展开大小。其他 WAV 编码继续走平台解码器。
- MediaCodec 在专用准备线程通过异步缓冲回调直接转换并写入最终 PCM，移除 raw 中间文件及第二遍转换。JNI 使用固定缓冲，保持原有 PCM16 数值规则。
- 仅 `c2.android.mp3.decoder` 合并多个完整 MPEG 帧，受实际输入缓冲容量限制；其他组件及 OGG 等逐包格式仍单包输入。依据 [AOSP MP3 组件的完整帧解析及解码循环](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/codec2/components/mp3/C2SoftMp3Dec.cpp)，未扩展到未知组件。
- 内容 SHA-256 和 PCM 版本组成缓存键；退出一局后保留缓存，相同内容可跨路径复用，内容修改后重新准备。提交的 PCM 缓存预算 512 MiB，按最近使用淘汰；正在播放/恢复的文件由引用保护。准备临时文件另占磁盘空间，内存没有整曲 PCM 数组。
- 文件长度/元数据无效时重建；临时 PCM 完成后才提交原子元数据。本轮验证截断恢复，不保证任意同长度字节损坏均被发现。
- 保存局内状态时记录缓存相对路径，重建时复用并保护；准备页显示音频耗时和是否命中缓存。AAudio 实时播放回调、输入时间戳与渲染时钟保持原边界，解码和文件写入只在准备线程。

## 构建和回归

JDK 17、Gradle 8.11.1、SDK 35、NDK 28.1.13356709、CMake 4.1.2。`:app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest` 成功，arm64-v8a/x86_64；lint 0 错误、31 警告。核心判定未修改，本轮未重复其已通过的测试。

API 35 Pixel 7 配置 x86_64 模拟器，只操作 `emulator-5554`，其他连接设备未操作。每种长曲检查约一秒原生播放，验证位置推进且缓存缺帧为零，不是三分钟整曲真机测试。实际 Activity 重建保留 PCM、输入和位置，以暂停状态恢复，原生续播位置继续推进。

原创八秒曲目经真实选曲/开始/结算：首次准备 127 ms，实际进入 Canvas 游玩后结算，8 个 Miss、2 个持续音符 Missed、1 个 AvoidMine；underrun 0、缓存缺帧 0。退出后重新准备显示 20 ms（复用缓存），开始按钮正常显示。模拟器没有可听声音证据，不能证明真机音质、延迟或多指表现。

## 三分钟对照

原创合成正弦素材均为 48000 Hz、双声道、180 秒，WAV PCM16、MP3 128 kbit/s、OGG Vorbis q3。没有使用或上传用户曲包。

每次首次试验使用独立空缓存，再立即测量同一内容命中。计时边界是 `AudioDecoder.decode`，包括内容哈希、提取、解码、转换、写盘与提交，不含导入、NAS 下载或 UI 调度。手机“点击准备到可开始”总耗时仍待测量。旧版为隔离的 0.4.2 单次对照，不是三次均值；主机负载变化明显，不宣称固定提速倍数。

| 格式 | 旧版单次 ms | 新版首次三次 ms | 新版重复三次 ms | 首次 ≤5 秒 |
|---|---:|---|---|---|
| WAV | 9747 | 511 / 325 / 409 | 59 / 49 / 40 | 本模拟器达到 |
| MP3 | 40069 | 3318 / 3312 / 3554 | 22 / 7 / 8 | 本模拟器达到，组件为 c2.android.mp3.decoder |
| OGG | 43222 | 42034 / 30021 / 28025 | 15 / 3 / 2 | 未达到 |

三种格式均核对采样率、8736000 帧（含两秒前导）和 PCM SHA-256，与旧版逐字节一致。10 秒 MP3 VBR/44100 Hz/双声道及 24000 Hz/单声道也保持帧数、采样率和字节一致。

最后一次首次准备阶段统计（ms；包含关系不能直接相加）：

| 格式 | 内容查找 | 提取 | 解码/转换/写盘流水线 | 其中转换 | 其中写盘 |
|---|---:|---:|---:|---:|---:|
| WAV | 41 | 0 | 330 | 57 | 162 |
| MP3 | 7 | 14 | 3446 | 80 | 571 |
| OGG | 3 | 109 | 27854 | 438 | 384 |

OGG 等待主要仍在平台解码流水线；不能通过放宽超时或缓存命中把首次目标标为完成。

缓存检查：三种格式截断后重建与旧版字节一致；WAV 内容变化而大小/修改时间不变时重新准备；容量压力淘汰旧条目而保留已引用缓存。JNI 单声道数值与缓冲越界检查通过。

## 复现

`android/tools/generate-audio-performance-fixtures.ps1 -Ffmpeg <本机 ffmpeg 路径>` 生成五个原创素材到忽略的 `.tools/perf-input`。FFmpeg 仅为主机测试工具，没有加入 APK。安装 app 和 androidTest APK，将素材复制到应用私有 `files/perf-input` 后按格式独立执行，避免构建及其他任务干扰长测试：

```powershell
adb -s emulator-5554 shell am instrument -w -e performance true -e format wav dev.foxdroid.app.test/dev.foxdroid.app.IndexInstrumentation
adb -s emulator-5554 shell am instrument -w -e performance true -e format mp3 dev.foxdroid.app.test/dev.foxdroid.app.IndexInstrumentation
adb -s emulator-5554 shell am instrument -w -e performance true -e format ogg dev.foxdroid.app.test/dev.foxdroid.app.IndexInstrumentation
adb -s emulator-5554 shell am instrument -w -e lifecycle true dev.foxdroid.app.test/dev.foxdroid.app.IndexInstrumentation
```

`BaselineAudioDecoder` 只存在于测试 APK。本机完整结果位于 `.tools/audio-performance-{wav,mp3,ogg}-final.txt` 和 `.tools/audio-lifecycle-final.txt`，未上传模拟器私有数据或用户曲包。

## APK 和未完成项

- `artifacts/android/foxdroid-audio-20261004-debug.apk`，3917294 字节，debug 签名，可覆盖安装 0.4.2。
- SHA-256：`24c607b3b56c04e3345a35fe082a6258c4b7ff5fdd622b8651d480781f227850`。
- 真机请选一首未在新版准备过的本地曲目，记录首次准备页数字；返回曲库再次准备应显示“复用缓存”。复测声音、暂停/后台续播和整局结算，无需重新导入曲库。
- 未完成：OGG 首次 ≤5 秒、其他厂商 MP3 组件提速、两台目标机每种三次量化及完整长曲缺帧/内存记录。继续评估减少逐包开销和短前导缓冲启动，不能在数据未完整时错误提前结算。
- 部分音频格式不支持仍单独后置，本轮没有扩大 MIME/扩展名范围。NAS 默认关闭与本地离线闭环保持不变。
