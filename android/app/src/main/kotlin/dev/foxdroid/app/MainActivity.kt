package dev.foxdroid.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.media.MediaExtractor
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.media.AudioManager
import android.view.View
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import dev.foxdroid.local.LocalLibrary
import dev.foxdroid.local.LocalContentSource
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** A0 diagnostic shell. Content service remains disabled and no network permission is declared. */
class MainActivity : Activity() {
    private var diagnosticsVisible = false
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private var importStatus = ""
    private var pageGeneration = 0
    private val libraryRoot get() = File(filesDir, "library").apply { mkdirs() }
    private val index by lazy { LibraryIndex(applicationContext) }
    private val contentSource by lazy { LocalContentSource(libraryRoot, index::refresh) }

    override fun onDestroy() {
        worker.execute { index.close() }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { navigateBack() }
        }
        if (savedInstanceState?.getBoolean("diagnosticsVisible") == true) showDiagnostics()
        else showLibrary()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("diagnosticsVisible", diagnosticsVisible)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Legacy navigation for Android 10–12")
    override fun onBackPressed() { navigateBack() }

    private fun navigateBack() {
        if (diagnosticsVisible) showLibrary() else finish()
    }

    private fun page(title: String): LinearLayout {
        pageGeneration++
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply {
            addView(layout)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        })
        layout.addView(TextView(this).apply { text = title; textSize = 28f; setPadding(0, 0, 0, 32) })
        return layout
    }

    private fun showLibrary() {
        diagnosticsVisible = false
        page("FoxDroid · 本地曲库").apply {
            addView(TextView(this@MainActivity).apply {
                text = "家庭内容服务器默认关闭。\n$importStatus"
                textSize = 18f
            })
            addView(Button(this@MainActivity).apply { text = "设备诊断"; setOnClickListener { showDiagnostics() } })
            addView(Button(this@MainActivity).apply {
                text = "导入 ZIP 曲包"; isEnabled = !busy
                setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE)
                }, 101) }
            })
            addView(Button(this@MainActivity).apply {
                text = "导入歌曲目录"; isEnabled = !busy
                setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 102) }
            })
            val list = this
            val generation = pageGeneration
            worker.execute {
                val scan = try { contentSource.refresh() } catch (e: Exception) {
                    dev.foxdroid.local.LibraryScan(emptyList(), listOf("索引读取失败，原曲包保留：${e.message}"))
                }
                val covers = scan.songs.associateWith { song ->
                    runCatching {
                        if (song.banner.isBlank()) null else {
                            val cover = LocalLibrary.safeFile(checkNotNull(song.file.parentFile), song.banner)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(cover.path, bounds)
                            var sample = 1
                            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                            BitmapFactory.decodeFile(cover.path, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }.getOrNull()
                }
                runOnUiThread {
                    if (isDestroyed || diagnosticsVisible || generation != pageGeneration) return@runOnUiThread
                    list.addView(TextView(this@MainActivity).apply {
                        text = if (scan.songs.isEmpty()) "曲库为空" else "${scan.songs.size} 首本地歌曲"
                    })
                    scan.errors.forEach { error -> list.addView(TextView(this@MainActivity).apply { text = error }) }
                    scan.songs.forEach { song ->
                        list.addView(TextView(this@MainActivity).apply { text = "${song.title} · ${song.artist}"; textSize = 20f })
                        if (song.banner.isNotBlank()) {
                            val bitmap = covers[song]
                            if (bitmap != null) list.addView(ImageView(this@MainActivity).apply {
                                setImageBitmap(bitmap); adjustViewBounds = true; maxHeight = 250
                            })
                        }
                        song.charts.forEachIndexed { index, chart ->
                            list.addView(Button(this@MainActivity).apply {
                                text = "${chart.difficulty} · ${chart.meter ?: "?"} · 准备谱面"
                                isEnabled = !busy
                                setOnClickListener {
                                    worker.execute {
                                        val result = runCatching {
                                            val prepared = contentSource.prepare(song, index)
                                            val audioFile = prepared.audioFile
                                            val extractor = MediaExtractor()
                                            try {
                                                extractor.setDataSource(audioFile.path)
                                                require((0 until extractor.trackCount).any {
                                                    extractor.getTrackFormat(it).getString("mime")?.startsWith("audio/") == true
                                                }) { "音频无法识别" }
                                            } finally { extractor.release() }
                                            "${song.title}：谱面和音频已准备；游玩将在 A2 开放。"
                                        }.getOrElse { "准备失败：${it.message}" }
                                        runOnUiThread { if (!isDestroyed) { importStatus = result; showLibrary() } }
                                    }
                                }
                            })
                        }
                        list.addView(Button(this@MainActivity).apply {
                            text = "移除此曲包（保留原文件）"; isEnabled = !busy
                            setOnClickListener {
                                android.app.AlertDialog.Builder(this@MainActivity)
                                    .setMessage("移除应用内的整个曲包？外部原文件保留。")
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("移除") { _, _ ->
                                        worker.execute {
                                            val pack = song.file.toPath().let { path ->
                                                libraryRoot.toPath().relativize(path).getName(0).toString()
                                            }
                                            val target = LocalLibrary.safeFile(libraryRoot, pack)
                                            val removed = target.deleteRecursively()
                                            runOnUiThread { if (!isDestroyed) {
                                                importStatus = if (removed) "曲包已移除。" else "移除失败。"
                                                showLibrary()
                                            } }
                                        }
                                    }.show()
                            }
                        })
                    }
                }
            }
        }
    }

    @Deprecated("Platform file-picker result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || requestCode !in setOf(101, 102)) return
        val uri = data?.data ?: return
        busy = true; importStatus = "正在复制并解析曲包…"; showLibrary()
        worker.execute {
            val stage = File(cacheDir, "import-${System.nanoTime()}").apply { mkdirs() }
            val result = runCatching {
                if (requestCode == 101) LocalLibrary.unzip(checkNotNull(contentResolver.openInputStream(uri)), stage)
                else copyTree(uri, stage)
                val scan = LocalLibrary.scan(stage)
                require(scan.songs.isNotEmpty()) { "没有可导入歌曲：${scan.errors.firstOrNull().orEmpty()}" }
                val digest = MessageDigest.getInstance("SHA-256")
                stage.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(stage).path }.forEach { file ->
                    digest.update(file.relativeTo(stage).path.toByteArray())
                    file.inputStream().use { input ->
                        val buffer = ByteArray(32768)
                        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                    }
                }
                val id = digest.digest().joinToString("") { "%02x".format(it) }
                val target = File(libraryRoot, id)
                if (target.exists()) "曲包已存在，未重复导入。"
                else {
                    require(stage.renameTo(target)) { "无法保存曲包，请检查空间" }
                    "已导入 ${scan.songs.size} 首歌曲；${scan.errors.size} 个谱面错误。"
                }
            }.getOrElse { "导入失败：${it.message}" }
            stage.deleteRecursively()
            runOnUiThread { if (!isDestroyed) { busy = false; importStatus = result; showLibrary() } }
        }
    }

    private fun copyTree(tree: Uri, destination: File) {
        var count = 0
        var total = 0L
        fun copy(documentId: String, folder: File, depth: Int) {
            require(depth <= 32) { "目录层级过深" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            checkNotNull(contentResolver.query(children, columns, null, null, null)).use { cursor ->
                while (cursor.moveToNext()) {
                    require(++count <= LocalLibrary.MAX_FILES) { "文件过多" }
                    val id = cursor.getString(0); val name = cursor.getString(1)
                    require(!name.contains('/') && !name.contains('\\')) { "不安全的文件名" }
                    val file = LocalLibrary.safeFile(folder, name)
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        file.mkdirs(); copy(id, file, depth + 1)
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        checkNotNull(contentResolver.openInputStream(uri)).use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(32768)
                                while (true) {
                                    val size = input.read(buffer); if (size < 0) break
                                    total += size; require(total <= LocalLibrary.MAX_BYTES) { "目录超过 512 MiB" }
                                    output.write(buffer, 0, size)
                                }
                            }
                        }
                    }
                }
            }
        }
        copy(DocumentsContract.getTreeDocumentId(tree), destination, 0)
    }

    @Suppress("DEPRECATION")
    private fun showDiagnostics() {
        diagnosticsVisible = true
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { "类型 ${it.type}" }
        page("设备诊断").apply {
            addView(TextView(this@MainActivity).apply {
                textSize = 16f
                text = "设备：${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
                    "屏幕：${windowManager.defaultDisplay.refreshRate} Hz\n" +
                    "建议采样率：${audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "未知"}\n" +
                    "建议缓冲帧数：${audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "未知"}\n" +
                    "可用输出：$outputs\n\n音频时钟、underrun 与触控时间戳：等待 A2 音频实验。"
            })
            addView(Button(this@MainActivity).apply { text = "返回曲库"; setOnClickListener { showLibrary() } })
        }
    }
}
