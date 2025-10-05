package com.tastamat.fandomon

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tastamat.fandomon.utils.LogMonitor
import kotlinx.coroutines.launch

class LogPermissionActivity : AppCompatActivity() {

    private lateinit var logMonitor: LogMonitor
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_permission)

        logMonitor = LogMonitor(this)
        setupViews()
        checkPermissions()
    }

    private fun setupViews() {
        statusText = findViewById(R.id.permissionStatusText)

        findViewById<Button>(R.id.checkPermissionsButton).setOnClickListener {
            checkPermissions()
        }

        findViewById<Button>(R.id.requestPermissionsButton).setOnClickListener {
            requestLogPermissions()
        }

        findViewById<Button>(R.id.testLogcatButton).setOnClickListener {
            testLogcatAccess()
        }

        findViewById<Button>(R.id.testFandomatLogsButton).setOnClickListener {
            testFandomatLogs()
        }

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }
    }

    private fun checkPermissions() {
        val sb = StringBuilder()
        sb.append("🔍 Проверка разрешений:\n\n")

        // Проверяем разрешение READ_LOGS
        val hasReadLogs = checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        sb.append("📋 READ_LOGS: ${if (hasReadLogs) "✅ Разрешено" else "❌ Запрещено"}\n")

        // Проверяем доступность logcat
        val logcatAvailable = logMonitor.isLogcatAvailable()
        sb.append("🖥️ Logcat доступен: ${if (logcatAvailable) "✅ Да" else "❌ Нет"}\n")

        // Проверяем, запущен ли Fandomat
        val fandomatRunning = isFandomatRunning()
        sb.append("📱 Fandomat запущен: ${if (fandomatRunning) "✅ Да" else "❌ Нет"}\n")

        sb.append("\n💡 Статус: ")
        when {
            !hasReadLogs -> sb.append("❌ Нужны разрешения")
            !logcatAvailable -> sb.append("⚠️ Logcat недоступен")
            !fandomatRunning -> sb.append("⚠️ Fandomat не запущен")
            else -> sb.append("✅ Все готово для чтения логов")
        }

        statusText.text = sb.toString()
    }

    private fun requestLogPermissions() {
        // Показываем диалог с инструкциями
        AlertDialog.Builder(this)
            .setTitle("📋 Разрешения для логов")
            .setMessage("""
                Для получения логов Fandomat необходимо:

                1. Включить режим разработчика:
                   • Настройки → О телефоне
                   • Нажать 7 раз на "Номер сборки"

                2. Включить отладку по USB:
                   • Настройки → Для разработчиков
                   • Включить "Отладка по USB"

                3. Предоставить разрешения через ADB:
                   adb shell pm grant com.tastamat.fandomon android.permission.READ_LOGS

                Открыть настройки разработчика?
            """.trimIndent())
            .setPositiveButton("Открыть настройки") { _, _ ->
                openDeveloperSettings()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openDeveloperSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // Если настройки разработчика недоступны, открываем общие настройки
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Найдите раздел 'Для разработчиков' в настройках", Toast.LENGTH_LONG).show()
        }
    }

    private fun testLogcatAccess() {
        lifecycleScope.launch {
            try {
                statusText.text = "🔄 Тестирование доступа к logcat..."

                val available = logMonitor.isLogcatAvailable()
                if (available) {
                    statusText.text = "✅ Logcat доступен!\n\nТестирование получения логов..."

                    // Получаем последние логи
                    val logs = logMonitor.getLastNLogs("com.tastamat.fandomon", 10)

                    if (logs.isNotEmpty()) {
                        statusText.text = "✅ Успешно получено ${logs.size} строк логов!\n\nПоследние логи:\n" +
                            logs.take(3).joinToString("\n") { it.take(100) + "..." }
                    } else {
                        statusText.text = "⚠️ Logcat доступен, но логи Fandomon не найдены.\nВозможно, приложение не запущено."
                    }
                } else {
                    statusText.text = "❌ Logcat недоступен.\nВозможно, нужны дополнительные разрешения."
                }

            } catch (e: Exception) {
                statusText.text = "❌ Ошибка тестирования: ${e.message}"
                Log.e("LogPermissionActivity", "Ошибка тестирования logcat", e)
            }
        }
    }

    private fun testFandomatLogs() {
        lifecycleScope.launch {
            try {
                statusText.text = "🔄 Получение логов Fandomat..."

                val logs = logMonitor.getFandomatLogs()

                if (logs.isNotEmpty()) {
                    val sb = StringBuilder()
                    sb.append("✅ Получено ${logs.size} записей логов Fandomat!\n\n")
                    sb.append("Последние 5 записей:\n")

                    logs.take(5).forEach { log ->
                        sb.append("${log.level} ${log.tag}: ${log.message.take(50)}...\n")
                    }

                    statusText.text = sb.toString()
                } else {
                    statusText.text = "⚠️ Логи Fandomat не найдены.\n\nВозможные причины:\n" +
                        "• Приложение Fandomat не запущено\n" +
                        "• Недостаточно разрешений\n" +
                        "• Логи очищены"
                }

            } catch (e: Exception) {
                statusText.text = "❌ Ошибка получения логов Fandomat: ${e.message}"
                Log.e("LogPermissionActivity", "Ошибка получения логов Fandomat", e)
            }
        }
    }

    private fun isFandomatRunning(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("pidof com.tastamat.fandomat")
            val result = process.waitFor()
            process.destroy()
            result == 0
        } catch (e: Exception) {
            false
        }
    }
}