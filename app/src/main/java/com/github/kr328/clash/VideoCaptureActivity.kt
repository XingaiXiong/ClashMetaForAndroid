package com.github.kr328.clash

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import java.text.DateFormat
import java.util.Date

class VideoCaptureActivity : AppCompatActivity() {
    private val targetPackage = "org.jbbcy.lvdany" // Confirmed on the user's REA-AN00, UID 10200.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store by lazy { getSharedPreferences("video_capture", MODE_PRIVATE) }
    private val coreDir by lazy { File(filesDir, "clash").apply { mkdirs() } }
    private lateinit var list: LinearLayout
    private var pendingDownload: String? = null
    private val saveVideo = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp2t")) { uri ->
        val address = pendingDownload
        pendingDownload = null
        if (uri != null && address != null) {
            toast("开始下载，请保持页面打开")
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { HlsDownloader.download(address, contentResolver.openOutputStream(uri) ?: error("无法写入文件")) }
                }
                toast(result.fold({ "下载完成：$it 个片段" }, { "下载失败：${it.message}" }))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selection = File(coreDir, "video-apps.txt")
        if (!selection.exists()) {
            val installed = runCatching { packageManager.getApplicationInfo(targetPackage, 0) }.isSuccess
            if (installed) selection.writeText("\n$targetPackage\n")
        }
        title = "视频记录与下载"
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
        }
        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
        page.addView(TextView(this).apply {
            text = "仅记录已勾选应用经 TUN 发出的明文 HTTP/1 M3U8 请求首个播放清单。普通 HTTPS 路径不可见；DRM 视频不支持。"
            textSize = 14f
        })
        page.addView(button("选择监控应用") { chooseApps() })
        page.addView(button("手动导入 M3U8") { importUrl() })
        page.addView(button("刷新记录") { refresh() })
        page.addView(button("清空记录") {
            AlertDialog.Builder(this)
                .setMessage("删除所有已记录和手动导入的链接？")
                .setPositiveButton("删除") { _, _ ->
                    File(coreDir, "video-observed.jsonl").delete()
                    store.edit().remove("manual").apply()
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list)
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun chooseApps() {
        val apps = packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
        val current = selectedApps()
        val labels = apps.map {
            "${packageManager.getApplicationLabel(it)}\n${it.packageName} · UID ${it.uid}"
        }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].packageName in current }
        AlertDialog.Builder(this)
            .setTitle("选择监控应用")
            .setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value }
            .setPositiveButton("保存") { _, _ ->
                val selected = apps.indices.filter { checked[it] }.map { apps[it].packageName }
                File(coreDir, "video-apps.txt").writeText("\n" + selected.joinToString("\n") + "\n")
                toast("已选择 ${selected.size} 个应用；请在 Clash 的访问控制中允许它们经过 VPN")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectedApps(): Set<String> =
        File(coreDir, "video-apps.txt").takeIf { it.exists() }
            ?.readLines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun importUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/video.m3u8"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("导入可访问的 M3U8")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val address = input.text.toString().trim()
                if (HlsDownloader.isPlaylistUrl(address)) {
                    val values = manualUrls().toMutableList()
                    if (address !in values) values.add(0, address)
                    store.edit().putString("manual", JSONArray(values).toString()).apply()
                    refresh()
                } else toast("请输入 http(s) M3U8 地址")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun manualUrls(): List<String> = runCatching {
        val array = JSONArray(store.getString("manual", "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun refresh() {
        list.removeAllViews()
        val selected = selectedApps()
        list.addView(TextView(this).apply {
            text = "已选择 ${selected.size} 个应用。只有经过本应用 VPN 的流量才可能被记录。"
            textSize = 14f
        })
        val records = LinkedHashMap<String, Pair<String, Long>>()
        manualUrls().forEach { records[it] = "手动导入" to 0L }
        val observed = File(coreDir, "video-observed.jsonl")
        if (observed.exists()) {
            observed.forEachLine { line ->
                runCatching {
                    val entry = JSONObject(line)
                    val address = entry.getString("url")
                    if (HlsDownloader.isPlaylistUrl(address)) {
                        records[address] = entry.getString("package") to entry.getLong("time")
                    }
                }
            }
        }
        if (records.isEmpty()) {
            list.addView(TextView(this).apply { text = "暂无视频。可先手动导入 M3U8。" })
        }
        records.entries.sortedByDescending { it.value.second }.take(200).forEach { (address, source) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 18.dp, 0, 18.dp)
            }
            card.addView(TextView(this).apply {
                text = "${source.first}${if (source.second > 0) " · ${DateFormat.getDateTimeInstance().format(Date(source.second))}" else ""}\n$address"
                textSize = 14f
                setTextIsSelectable(true)
            })
            val actions = LinearLayout(this)
            actions.addView(button("播放") {
                startActivity(Intent(this, VideoPlayerActivity::class.java).putExtra("url", address))
            })
            actions.addView(button("下载 TS") {
                pendingDownload = address
                saveVideo.launch("video-${System.currentTimeMillis()}.ts")
            })
            card.addView(actions)
            list.addView(card)
        }
    }

    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
