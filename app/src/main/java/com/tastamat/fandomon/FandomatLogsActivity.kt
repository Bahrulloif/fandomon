package com.tastamat.fandomon

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tastamat.fandomon.utils.LogMonitor
import com.tastamat.fandomon.utils.FileLogger
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class FandomatLogsActivity : AppCompatActivity() {

    private lateinit var logMonitor: LogMonitor
    private lateinit var fileLogger: FileLogger
    private lateinit var logsRecyclerView: RecyclerView
    private lateinit var logsAdapter: FandomatLogsAdapter
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fandomat_logs)

        initializeComponents()
        setupViews()
        loadLogs()
    }

    private fun initializeComponents() {
        logMonitor = LogMonitor(this)
        fileLogger = FileLogger(this)
        logsAdapter = FandomatLogsAdapter()
    }

    private fun setupViews() {
        logsRecyclerView = findViewById(R.id.fandomatLogsRecyclerView)
        statusText = findViewById(R.id.logsStatusText)

        logsRecyclerView.layoutManager = LinearLayoutManager(this)
        logsRecyclerView.adapter = logsAdapter

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.refreshLogsButton).setOnClickListener {
            loadLogs()
        }

        findViewById<Button>(R.id.clearLogsButton).setOnClickListener {
            clearLogs()
        }
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            try {
                statusText.text = "🔄 Загрузка логов Fandomat..."
                Log.d("FandomatLogsActivity", "Начало загрузки логов")

                // Добавляем информацию о файле логов в статус
                val logFilePath = fileLogger.getLogFilePath()
                val logFileExists = fileLogger.logFileExists()
                val logFileSize = fileLogger.getLogFileSize()

                Log.d("FandomatLogsActivity", "Путь к файлу логов: $logFilePath")
                Log.d("FandomatLogsActivity", "Файл существует: $logFileExists")
                Log.d("FandomatLogsActivity", "Размер файла: $logFileSize байт")

                // Сначала пытаемся получить логи из файла
                val fileLogs = loadLogsFromFile()

                // Если файловых логов нет, пытаемся получить системные логи
                val systemLogs = if (fileLogs.isEmpty()) {
                    Log.d("FandomatLogsActivity", "Файловых логов нет, пытаемся получить системные логи")
                    logMonitor.getFandomatLogs()
                } else {
                    emptyList()
                }

                val allLogs = fileLogs + systemLogs
                Log.d("FandomatLogsActivity", "Получено логов: файловых=${fileLogs.size}, системных=${systemLogs.size}")

                if (allLogs.isNotEmpty()) {
                    logsAdapter.updateLogs(allLogs)
                    statusText.text = "📋 Найдено ${allLogs.size} записей логов"
                } else {
                    logsAdapter.updateLogs(emptyList())
                    statusText.text = "ℹ️ Логи Fandomat не найдены"
                }

            } catch (e: Exception) {
                Log.e("FandomatLogsActivity", "Ошибка загрузки логов", e)
                statusText.text = "❌ Ошибка загрузки логов: ${e.message}"
            }
        }
    }

    private fun loadLogsFromFile(): List<LogMonitor.LogEntry> {
        return try {
            val logFilePath = fileLogger.getLogFilePath()
            Log.d("FandomatLogsActivity", "Проверка файла логов по пути: $logFilePath")

            if (!fileLogger.logFileExists()) {
                Log.d("FandomatLogsActivity", "Файл логов не существует по пути: $logFilePath")

                // Проверим, есть ли файл физически
                val file = java.io.File(logFilePath)
                Log.d("FandomatLogsActivity", "Файл существует физически: ${file.exists()}")
                Log.d("FandomatLogsActivity", "Файл доступен для чтения: ${file.canRead()}")
                Log.d("FandomatLogsActivity", "Размер файла: ${file.length()} байт")

                return emptyList()
            }

            val fileLines = fileLogger.readLastLogs(100) // Читаем последние 100 строк
            Log.d("FandomatLogsActivity", "Прочитано строк из файла: ${fileLines.size}")

            // Выведем первые несколько строк для отладки
            fileLines.take(5).forEachIndexed { index, line ->
                Log.d("FandomatLogsActivity", "Строка $index: $line")
            }

            val parsedLogs = fileLines.mapNotNull { line ->
                parseFileLogLine(line)
            }

            Log.d("FandomatLogsActivity", "Успешно распарсено записей: ${parsedLogs.size}")

            parsedLogs

        } catch (e: Exception) {
            Log.e("FandomatLogsActivity", "Ошибка чтения файла логов", e)
            emptyList()
        }
    }

    private fun parseFileLogLine(line: String): LogMonitor.LogEntry? {
        return try {
            // Формат: "yyyy-MM-dd HH:mm:ss.SSS [LEVEL] TAG: MESSAGE"
            val pattern = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[(\w+)\] ([^:]+): (.*)""")
            val match = pattern.find(line)

            if (match != null) {
                val timestampStr = match.groupValues[1]
                val level = match.groupValues[2]
                val tag = match.groupValues[3]
                val message = match.groupValues[4]

                val timestamp = try {
                    val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                    format.parse(timestampStr)?.time ?: System.currentTimeMillis()
                } catch (e: Exception) {
                    System.currentTimeMillis()
                }

                LogMonitor.LogEntry(
                    timestamp = timestamp,
                    level = level,
                    tag = tag,
                    message = message,
                    rawLine = line
                )
            } else {
                // Если не удалось распарсить, создаем простую запись
                LogMonitor.LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = "FILE",
                    tag = "Unparsed",
                    message = line,
                    rawLine = line
                )
            }
        } catch (e: Exception) {
            Log.w("FandomatLogsActivity", "Ошибка парсинга строки: $line", e)
            null
        }
    }

    private fun clearLogs() {
        logsAdapter.updateLogs(emptyList())
        statusText.text = "🗑️ Логи очищены из отображения"
    }

}

class FandomatLogsAdapter : RecyclerView.Adapter<FandomatLogsAdapter.LogViewHolder>() {

    private var logs = listOf<LogMonitor.LogEntry>()

    fun updateLogs(newLogs: List<LogMonitor.LogEntry>) {
        Log.d("FandomatLogsAdapter", "Обновление логов: ${newLogs.size} элементов")
        logs = newLogs
        Log.d("FandomatLogsAdapter", "Вызываем notifyDataSetChanged(), itemCount теперь: ${logs.size}")
        notifyDataSetChanged()
        Log.d("FandomatLogsAdapter", "notifyDataSetChanged() завершен")
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): LogViewHolder {
        Log.d("FandomatLogsAdapter", "Создание ViewHolder")
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fandomat_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        Log.d("FandomatLogsAdapter", "Привязка ViewHolder для позиции $position из ${logs.size}")
        if (position < logs.size) {
            holder.bind(logs[position])
        }
    }

    override fun getItemCount(): Int = logs.size

    class LogViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val timestampText: TextView = itemView.findViewById(R.id.logTimestampText)
        private val levelText: TextView = itemView.findViewById(R.id.logLevelText)
        private val messageText: TextView = itemView.findViewById(R.id.logMessageText)
        private val tagText: TextView = itemView.findViewById(R.id.logTagText)
        private val levelIndicator: android.view.View = itemView.findViewById(R.id.levelIndicator)

        fun bind(logEntry: LogMonitor.LogEntry) {
            val dateFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
            timestampText.text = dateFormat.format(Date(logEntry.timestamp))

            levelText.text = logEntry.level
            messageText.text = logEntry.message
            tagText.text = logEntry.tag

            // Цвет индикатора уровня лога
            val color = when (logEntry.level.uppercase()) {
                "E", "ERROR" -> android.graphics.Color.RED
                "W", "WARN" -> android.graphics.Color.parseColor("#FF9800")
                "I", "INFO" -> android.graphics.Color.parseColor("#4CAF50")
                "D", "DEBUG" -> android.graphics.Color.parseColor("#2196F3")
                "V", "VERBOSE" -> android.graphics.Color.GRAY
                else -> android.graphics.Color.BLACK
            }
            levelIndicator.setBackgroundColor(color)
        }
    }
}