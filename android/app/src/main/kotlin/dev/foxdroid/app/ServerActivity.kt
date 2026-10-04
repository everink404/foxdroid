package dev.foxdroid.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

class ServerActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val settings by lazy { getSharedPreferences("server-settings",MODE_PRIVATE) }
    private var busy = false
    private var status = ""
    private var generation = 0
    private var progressText: TextView? = null
    private fun enabled() = settings.getBoolean("enabled",false)
    private fun client() = ServerContent(this,settings.getString("address","").orEmpty()) { enabled() && !isDestroyed && !Thread.currentThread().isInterrupted }
    override fun onCreate(state: Bundle?) { super.onCreate(state); show() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    private fun show() {
        val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(40,100,40,60) }
        setContentView(ScrollView(this).apply {
            addView(layout)
            setOnApplyWindowInsetsListener { view,insets ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val bars=insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
                }
                insets
            }
        }); generation++
        layout.addView(TextView(this).apply { text="家庭内容服务器"; textSize=26f })
        if (status.isNotBlank()) layout.addView(TextView(this).apply { text=status; textSize=18f })
        layout.addView(Switch(this).apply {
            text="开启可选服务器"; isChecked=enabled(); isEnabled=!busy
            setOnCheckedChangeListener { _,value -> settings.edit().putBoolean("enabled",value).apply(); status=""; show() }
        })
        if (!enabled()) layout.addView(TextView(this).apply { text="服务已关闭，本地导入和游玩不受影响。" })
        else {
            val address = EditText(this).apply { hint="http://NAS地址:8080"; setText(settings.getString("address","")); isEnabled=!busy; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
            layout.addView(address)
            layout.addView(Button(this).apply { text="保存地址并同步曲库"; isEnabled=!busy; setOnClickListener {
                runCatching { ServerContent.normalize(address.text.toString()) }
                    .onFailure { status=it.message.orEmpty(); show() }.onSuccess {
                        settings.edit().putString("address",it).apply()
                        task { val catalog=client().sync(); "已同步 ${catalog.getJSONArray("songs").length()} 首歌曲" }
                    }
            } })
            val content = runCatching { client() }.getOrNull(); val catalog = content?.cached()
            if (catalog != null) {
                val info=catalog.getJSONObject("server")
                layout.addView(TextView(this).apply { text="${info.optString("name")} · ${info.optString("version")}\n已保存曲库 · ${catalog.getJSONArray("songs").length()} 首\n尚未下载的歌曲需要服务器在线。" })
                val songs=catalog.getJSONArray("songs")
                for (i in 0 until songs.length()) {
                    val song=songs.getJSONObject(i)
                    layout.addView(Button(this).apply { text="${song.getString("title")} · ${song.optString("artist")}"; isEnabled=!busy
                        setOnClickListener { detail(content,catalog,song.getString("id")) } })
                }
                layout.addView(Button(this).apply { text="清除服务器缓存（本地曲包保留）"; isEnabled=!busy; setOnClickListener {
                    android.app.AlertDialog.Builder(this@ServerActivity).setMessage("清除已同步清单和下载资源？")
                        .setNegativeButton("取消",null).setPositiveButton("清除") { _,_ -> task { content.clearCache(); "缓存已清除" } }.show()
                } })
            }
        }
        layout.addView(Button(this).apply { text="返回本地曲库"; setOnClickListener { finish() } })
    }
    private fun showBusy(message: String) {
        generation++
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(40,180,40,80) }
        progressText=TextView(this).apply { text=message; textSize=22f }
        layout.addView(progressText); layout.addView(ProgressBar(this))
        layout.addView(TextView(this).apply { text="资源准备完成后会进入游玩页。" })
        layout.addView(Button(this).apply { text="取消并返回本地曲库"; setOnClickListener { finish() } })
        setContentView(layout)
    }
    private fun updateProgress(message: String) { runOnUiThread { if (!isDestroyed && busy) progressText?.text=message } }
    private fun failure(message: String) {
        status=message; show()
        android.app.AlertDialog.Builder(this).setTitle("准备未完成").setMessage(message)
            .setPositiveButton("知道了",null).show()
    }
    private fun task(work: () -> String) {
        busy=true; showBusy("正在连接服务器并同步曲库…")
        worker.execute {
            val result=runCatching(work).getOrElse { "操作失败：${it.message}" }
            runOnUiThread { if (!isDestroyed) { busy=false; status=result; show() } }
        }
    }
    private fun detail(content: ServerContent,catalog: JSONObject,id: String) {
        busy=true; showBusy("正在获取歌曲难度…"); val expected=generation
        worker.execute {
            val result=runCatching { content.detail(catalog,id) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy=false
                if (expected != generation) { show(); return@runOnUiThread }
                result.onFailure { failure("无法获取歌曲：${it.message}") }.onSuccess { song ->
                    show(); val charts=song.getJSONArray("charts")
                    val supported=(0 until charts.length()).map { charts.getJSONObject(it) }.filter { it.getString("stepType")=="dance-single" }
                    if (supported.isEmpty()) { status="没有四轨谱面"; show(); return@onSuccess }
                    android.app.AlertDialog.Builder(this).setTitle(song.getString("title"))
                        .setItems(supported.map { "${it.getString("difficulty")} · ${it.opt("meter")}" }.toTypedArray()) { _,which ->
                            busy=true; showBusy("正在获取谱面…")
                            worker.execute {
                                val prepared=runCatching { content.prepare(catalog,song,supported[which].getString("id"),::updateProgress) }
                                runOnUiThread { if (!isDestroyed) {
                                    busy=false
                                    prepared.onSuccess {
                                        show()
                                        startActivity(Intent(this,GameActivity::class.java).putExtra("chartFile",it))
                                    }.onFailure { failure("准备失败：${it.message}") }
                                } }
                            }
                        }.setNegativeButton("取消",null).show()
                }
            }
        }
    }
}
