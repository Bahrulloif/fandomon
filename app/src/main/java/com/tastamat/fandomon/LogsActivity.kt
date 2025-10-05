package com.tastamat.fandomon

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.Event
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LogsActivity : AppCompatActivity() {

    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var eventsRecyclerView: RecyclerView
    private lateinit var eventsAdapter: EventsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        initializeComponents()
        setupViews()
        loadEvents()
    }

    private fun initializeComponents() {
        databaseHelper = DatabaseHelper(this)
        eventsAdapter = EventsAdapter()
    }

    private fun setupViews() {
        eventsRecyclerView = findViewById(R.id.eventsRecyclerView)
        val layoutManager = LinearLayoutManager(this)
        eventsRecyclerView.layoutManager = layoutManager
        eventsRecyclerView.adapter = eventsAdapter

        Log.d("LogsActivity", "RecyclerView настроен: LayoutManager = $layoutManager, Adapter = $eventsAdapter")

        // Принудительно делаем RecyclerView видимым
        eventsRecyclerView.visibility = android.view.View.VISIBLE
        eventsRecyclerView.setBackgroundColor(android.graphics.Color.LTGRAY)

        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            loadEvents()
        }

        findViewById<Button>(R.id.clearLogsButton).setOnClickListener {
            clearLogs()
        }

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }
    }

    private fun loadEvents() {
        lifecycleScope.launch {
            try {
                // Получаем все события за последние 30 дней
                val events = databaseHelper.getEventsBetween(
                    System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L, // 30 дней назад
                    System.currentTimeMillis()
                )

                Log.d("LogsActivity", "Загружено событий: ${events.size}")
                events.forEach { event ->
                    Log.d("LogsActivity", "Событие: ${event.type.description} - ${event.details}")
                }

                runOnUiThread {
                    eventsAdapter.updateEvents(events)

                    // Проверяем размеры RecyclerView
                    eventsRecyclerView.post {
                        Log.d("LogsActivity", "RecyclerView размеры: width=${eventsRecyclerView.width}, height=${eventsRecyclerView.height}")
                        Log.d("LogsActivity", "RecyclerView visibility: ${eventsRecyclerView.visibility}")
                        Log.d("LogsActivity", "RecyclerView childCount: ${eventsRecyclerView.childCount}")
                    }

                    // Прокручиваем к началу списка
                    if (events.isNotEmpty()) {
                        eventsRecyclerView.scrollToPosition(0)
                    }

                    // Обновляем заголовок с количеством событий
                    val eventsCountText = findViewById<TextView>(R.id.eventsCountText)
                    if (eventsCountText != null) {
                        eventsCountText.text = "Всего событий: ${events.size}"
                        Log.d("LogsActivity", "Счетчик событий обновлен: ${events.size}")
                    } else {
                        Log.e("LogsActivity", "eventsCountText не найден!")
                    }
                }

            } catch (e: Exception) {
                Log.e("LogsActivity", "Ошибка загрузки событий: ${e.message}", e)
                android.widget.Toast.makeText(this@LogsActivity, "Ошибка загрузки событий", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearLogs() {
        lifecycleScope.launch {
            try {
                databaseHelper.clearAllData()
                loadEvents()
            } catch (e: Exception) {
                // Обработка ошибки
            }
        }
    }
}

class EventsAdapter : RecyclerView.Adapter<EventsAdapter.EventViewHolder>() {

    private var events = listOf<Event>()

    fun updateEvents(newEvents: List<Event>) {
        Log.d("EventsAdapter", "Обновление событий: ${newEvents.size} элементов")
        events = newEvents
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): EventViewHolder {
        Log.d("EventsAdapter", "Создание ViewHolder")
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        Log.d("EventsAdapter", "Binding событие в позиции $position: ${events[position].type.description}")
        holder.bind(events[position])
    }

    override fun getItemCount(): Int = events.size

    class EventViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val typeText: TextView = itemView.findViewById(R.id.eventTypeText)
        private val detailsText: TextView = itemView.findViewById(R.id.eventDetailsText)
        private val timestampText: TextView = itemView.findViewById(R.id.eventTimestampText)
        private val severityIndicator: android.view.View = itemView.findViewById(R.id.severityIndicator)

        fun bind(event: Event) {
            typeText.text = event.type.description
            detailsText.text = event.details

            val dateFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
            timestampText.text = dateFormat.format(Date(event.timestamp))

            // Цвет индикатора серьезности
            val color = when (event.severity) {
                com.tastamat.fandomon.data.EventSeverity.CRITICAL -> android.graphics.Color.RED
                com.tastamat.fandomon.data.EventSeverity.ERROR -> android.graphics.Color.parseColor("#FF5722")
                com.tastamat.fandomon.data.EventSeverity.WARNING -> android.graphics.Color.parseColor("#FF9800")
                com.tastamat.fandomon.data.EventSeverity.INFO -> android.graphics.Color.parseColor("#4CAF50")
                com.tastamat.fandomon.data.EventSeverity.DEBUG -> android.graphics.Color.GRAY
            }
            severityIndicator.setBackgroundColor(color)
        }
    }
}

// Простой тестовый адаптер для диагностики
class SimpleTestAdapter(private val items: List<String>) : RecyclerView.Adapter<SimpleTestAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        Log.d("SimpleTestAdapter", "Создание ViewHolder")
        val textView = TextView(parent.context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                200 // фиксированная высота 200dp
            )
            setPadding(16, 16, 16, 16)
            setBackgroundColor(android.graphics.Color.WHITE)
            textSize = 16f
        }
        return ViewHolder(textView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        Log.d("SimpleTestAdapter", "Binding позиция $position: ${items[position]}")
        (holder.itemView as TextView).text = "${position + 1}. ${items[position]}"
    }

    override fun getItemCount(): Int {
        Log.d("SimpleTestAdapter", "getItemCount: ${items.size}")
        return items.size
    }

    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView)
}