package com.github.kr328.clash

import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class VideoPlayerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "播放视频"
        val address = intent.getStringExtra("url") ?: run { finish(); return }
        val player = VideoView(this)
        setContentView(player)
        player.setMediaController(MediaController(this).apply { setAnchorView(player) })
        player.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "无法播放：链接可能过期、需登录或设备不支持此格式", Toast.LENGTH_LONG).show()
            true
        }
        player.setVideoPath(address)
        player.start()
    }
}
