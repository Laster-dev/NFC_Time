package com.xingye.crafttime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xingye.crafttime.api.BackendApiClient
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var apiClient: BackendApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("nfc_neo_prefs", Context.MODE_PRIVATE)
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)

        if (isLoggedIn) {
            // Already logged in, go straight to MainActivity
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)
        apiClient = BackendApiClient(this)

        val etServerUrl = findViewById<EditText>(R.id.etLoginServerUrl)
        val btnTest = findViewById<Button>(R.id.btnLoginTestServer)
        val tvTestStatus = findViewById<TextView>(R.id.tvLoginServerTestStatus)
        val etPassword = findViewById<EditText>(R.id.etLoginPassword)
        val btnLogin = findViewById<Button>(R.id.btnLoginSubmit)

        etServerUrl.setText(apiClient.getServerUrl())

        btnTest.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            if (url.isEmpty()) {
                tvTestStatus.text = "请输入服务器地址"
                tvTestStatus.setTextColor(0xFFFF5252.toInt())
                return@setOnClickListener
            }
            tvTestStatus.text = "⏳ 正在测试连接..."
            tvTestStatus.setTextColor(0xFF2AABEE.toInt())
            lifecycleScope.launch {
                val res = apiClient.testConnection(url)
                tvTestStatus.text = res.second
                tvTestStatus.setTextColor(if (res.first) 0xFF45B880.toInt() else 0xFFFF5252.toInt())
            }
        }

        btnLogin.setOnClickListener {
            val serverUrl = etServerUrl.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                Toast.makeText(this, "请输入管理员密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            apiClient.updateServerUrl(serverUrl)

            lifecycleScope.launch {
                val ok = apiClient.verifyAdminPassword(password)
                if (ok) {
                    // Persist login state
                    prefs.edit().putBoolean("is_logged_in", true).apply()
                    Toast.makeText(this@LoginActivity, "🎉 登录成功，欢迎使用星野手作计时系统！", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Toast.makeText(this@LoginActivity, "❌ 密码错误，请重试 (默认 888888)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
