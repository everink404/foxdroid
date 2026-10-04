package dev.foxdroid.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.media.AudioManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A0 diagnostic shell. Content service remains disabled and no network permission is declared. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showLibrary()
    }

    private fun page(title: String): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.addView(TextView(this).apply { text = title; textSize = 28f; setPadding(0, 0, 0, 32) })
        return layout
    }

    private fun showLibrary() {
        page("FoxDroid · 本地曲库").apply {
            addView(TextView(this@MainActivity).apply {
                text = "曲库为空\n\n本地导入正在开发中。\n家庭内容服务器默认关闭。"
                textSize = 18f
            })
            addView(Button(this@MainActivity).apply { text = "设备诊断"; setOnClickListener { showDiagnostics() } })
        }
    }

    @Suppress("DEPRECATION")
    private fun showDiagnostics() {
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
