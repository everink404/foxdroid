package dev.foxdroid.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.foxdroid.game.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Prototype runtime: immutable PCM, monotonic input events, display-only frame loop. */
class GameActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val audio = NativeAudio()
    private var handle = 0L
    private var decoded: DecodedAudio? = null
    private var notes = emptyList<Note>()
    private val events = mutableListOf<GameInput>()
    private val touchState = TouchState()
    private var resumeFrame = 0L
    private var paused = true
    private var completed = false
    private var lastTime = -2.0
    private var title = ""
    private var audioStats = ""
    private var streamOpenedAt = 0L
    private var audioOffsetMs = 0
    private var inputOffsetMs = 0
    private var visualOffsetMs = 0
    private var lastInputNanos = 0L
    private lateinit var surface: View
    private lateinit var focus: AudioFocusRequest
    private val manager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
        ) { if (!paused) pauseGame() else finish() }
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) pauseGame() }.build()
        setContentView(TextView(this).apply { text = "正在预解码本地音频…"; textSize = 24f; setPadding(40,100,40,40) })
        val chartFile = File(cacheDir, intent.getStringExtra("chartFile") ?: "missing-chart.json").canonicalFile
        worker.execute {
            val result = runCatching {
                require(chartFile.parentFile == cacheDir.canonicalFile)
                val chart = JSONObject(chartFile.readText())
                title = chart.getString("title")
                val timing = chart.getJSONObject("timing")
                notes = parseChartNotes(timing.keys().asSequence().associateWith { timing.getString(it) }, chart.getString("notes"))
                require(notes.isNotEmpty() && notes.size <= 2000) { "原型最多支持 2000 音符" }
                require(notes.minOf { it.time } >= -2) { "谱面前导时间超过原型上限" }
                val music = File(chart.getString("audio")).canonicalFile
                require(music.toPath().startsWith(File(filesDir, "library").canonicalFile.toPath()))
                AudioDecoder.decode(music.path, cacheDir)
            }
            runOnUiThread {
                if (isDestroyed) { result.getOrNull()?.file?.delete(); return@runOnUiThread }
                result.onSuccess { decoded = it; showPaused("已准备，点击开始") }
                    .onFailure { showPaused("准备失败：${it.message}") }
            }
        }
    }

    private fun showPaused(message: String) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,100,40,40) }
        layout.addView(TextView(this).apply { text = "$title\n$message\n$audioStats"; textSize = 22f })
        if (decoded != null && !completed) layout.addView(Button(this).apply { text = "开始 / 继续"; setOnClickListener { startGame() } })
        layout.addView(Button(this).apply { text = "返回曲库"; setOnClickListener { finish() } })
        setContentView(layout)
    }

    private fun startGame() {
        val pcm = decoded ?: return
        val calibration = getSharedPreferences("game-settings",MODE_PRIVATE)
        audioOffsetMs = calibration.getInt("audioOffsetMs",0)
        inputOffsetMs = calibration.getInt("inputOffsetMs",0)
        visualOffsetMs = calibration.getInt("visualOffsetMs",0)
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { showPaused("无法获取音频焦点"); return }
        handle = audio.open(pcm.file.path, pcm.rate, resumeFrame)
        if (handle == 0L) { manager.abandonAudioFocusRequest(focus); showPaused("无法打开 AAudio 输出，请检查音频设备"); return }
        paused = false
        streamOpenedAt = System.nanoTime()
        surface = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                if (paused || handle == 0L) return
                val stats = audio.stats(handle)
                if (stats[4] != 0) { post { pauseGame(); showPaused("音频流中断：${stats[4]}，缓存缺帧 ${stats[6]}") }; return }
                val time = audio.position(handle, System.nanoTime())
                canvas.drawColor(Color.rgb(20,20,30))
                paint.color = Color.WHITE; paint.textSize = 38f
                if (!time.isFinite()) {
                    canvas.drawText("等待音频硬件时间戳…", 30f, 140f, paint)
                    if (System.nanoTime()-streamOpenedAt > 5_000_000_000L) post {
                        pauseGame(); showPaused("音频硬件时间戳不可用，未使用绘制时钟代替")
                    }
                } else {
                    lastTime = time
                    val visualTime = time + visualOffsetMs/1000.0
                    val receptor = height*.28f
                    for (lane in 0..3) {
                        paint.color = if (lane in touchState.pointers.values) Color.rgb(50,100,150) else Color.rgb(35,35,50)
                        canvas.drawRect(lane*width/4f, 200f, (lane+1)*width/4f-3, height.toFloat(), paint)
                    }
                    for (note in notes) {
                        val y = receptor + ((note.time-visualTime)*height*.45).toFloat()
                        if (y in -100f..height.toFloat()) {
                            val x = (note.lane+.5f)*width/4
                            paint.color = if (note.type == "mine") Color.RED else Color.CYAN
                            val endTime = note.endTime
                            if (endTime != null) {
                                val tail = receptor + ((endTime-visualTime)*height*.45).toFloat()
                                canvas.drawRect(x-15,y,x+15,tail.coerceAtMost(height.toFloat()),paint)
                            }
                            canvas.drawCircle(x,y,28f,paint)
                        }
                    }
                    paint.color = Color.WHITE
                    canvas.drawLine(0f,receptor,width.toFloat(),receptor,paint)
                    canvas.drawText("暂停 · 时间 %.2f · 输入 %d".format(time,events.size),30f,130f,paint)
                    canvas.drawText("${stats[0]} Hz · burst ${stats[1]} · underrun ${stats[3]}",30f,175f,paint)
                    canvas.drawText("输入单调时间戳 $lastInputNanos ns",30f,220f,paint)
                    val end = maxOf(notes.maxOf { it.endTime ?: it.time }+1, pcm.frames.toDouble()/pcm.rate-2)
                    if (time > end) { post { settle() }; return }
                }
                postInvalidateOnAnimation()
            }
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (paused) return true
                if (event.actionMasked == MotionEvent.ACTION_DOWN && event.y < 200) { pauseGame(); return true }
                val time = if (handle != 0L) audio.position(handle,event.eventTime*1_000_000) else Double.NaN
                if (!time.isFinite()) return true
                lastInputNanos = event.eventTime*1_000_000
                val pointers = touchState.pointers.toMutableMap()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val i = event.actionIndex; pointers[event.getPointerId(i)] = (event.getX(i)/width*4).toInt().coerceIn(0,3)
                    }
                    MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                        if (event.getPointerId(i) in pointers) pointers[event.getPointerId(i)] = (event.getX(i)/width*4).toInt().coerceIn(0,3)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointers.remove(event.getPointerId(event.actionIndex))
                    MotionEvent.ACTION_CANCEL -> pointers.clear()
                }
                val adjusted = time + (inputOffsetMs+audioOffsetMs)/1000.0
                touchState.update(pointers).forEach { events += GameInput(it.lane,if(it.down) "down" else "up",adjusted) }
                return true
            }
        }
        setContentView(surface)
    }

    private fun pauseGame() {
        if (paused || completed) return
        val time = audio.position(handle,System.nanoTime()).takeIf { it.isFinite() } ?: lastTime
        touchState.update(emptyMap()).forEach { events += GameInput(it.lane,"up",time+(inputOffsetMs+audioOffsetMs)/1000.0) }
        resumeFrame = ((time+2)*checkNotNull(decoded).rate).toLong().coerceAtLeast(0)
        audioStats = audio.stats(handle).let { "AAudio ${it[0]} Hz · buffer ${it[2]} · underrun ${it[3]} · mode ${it[5]} · 缓存缺帧 ${it[6]}\n输入事件 ${events.size} 条" }
        audio.close(handle); handle = 0; paused = true
        manager.abandonAudioFocusRequest(focus)
        showPaused("已暂停，继续时重新建立音频时钟")
    }

    private fun settle() {
        if (completed) return
        pauseGame(); completed = true
        val results = evaluateInputs(notes,events)
        val judgments = results.filter { it.type != "mine" }.map { it.judgment ?: it.headJudgment ?: "Miss" }
        val points = judgments.map { when(it) { "Perfect" -> 1000; "Great" -> 700; "Good" -> 300; else -> 0 } }.sum()
        var combo = 0; var maximum = 0
        judgments.forEach { if (it == "Miss") combo = 0 else { combo++; maximum = maxOf(maximum,combo) } }
        val accuracy = if (judgments.isEmpty()) 0.0 else judgments.sumOf { when(it) { "Perfect" -> 1.0; "Great" -> .8; "Good" -> .5; else -> 0.0 } }/judgments.size*100
        val summary = "完成 · 分数 $points · 最大连击 $maximum\n准确率 %.1f%%\n%s\n持续音符 %s\n地雷 %s".format(
            accuracy,judgments.groupingBy { it }.eachCount(),results.mapNotNull { it.bodyJudgment }.groupingBy { it }.eachCount(),
            results.filter { it.type == "mine" }.map { it.judgment }.groupingBy { it }.eachCount())
        getSharedPreferences("scores",MODE_PRIVATE).edit().putString("last-result", "$title\n$summary").apply()
        showPaused(summary)
    }

    @Deprecated("Prototype back navigation")
    override fun onBackPressed() { if (!paused) pauseGame() else super.onBackPressed() }
    override fun onStop() { pauseGame(); super.onStop() }
    override fun onDestroy() { if (handle != 0L) audio.close(handle); handle = 0; worker.shutdownNow(); decoded?.file?.delete(); super.onDestroy() }
}
