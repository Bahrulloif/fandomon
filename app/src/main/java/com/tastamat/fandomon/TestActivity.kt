package com.tastamat.fandomon

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("TestActivity", "Создание TestActivity")

        // Создаем простейший layout программно
        val recyclerView = RecyclerView(this).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.LTGRAY)
        }

        setContentView(recyclerView)

        // Настраиваем RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Создаем тестовые данные
        val testData = listOf(
            "Первое событие",
            "Второе событие",
            "Третье событие",
            "Четвертое событие",
            "Пятое событие"
        )

        Log.d("TestActivity", "Устанавливаем адаптер с ${testData.size} элементами")

        // Устанавливаем адаптер
        recyclerView.adapter = TestSimpleAdapter(testData)

        Log.d("TestActivity", "TestActivity создана успешно")
    }
}

class TestSimpleAdapter(private val items: List<String>) : RecyclerView.Adapter<TestSimpleAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        Log.d("TestSimpleAdapter", "onCreateViewHolder вызван")

        val textView = TextView(parent.context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                300 // 300dp высота
            )
            setPadding(32, 32, 32, 32)
            setBackgroundColor(android.graphics.Color.WHITE)
            textSize = 18f
            setTextColor(android.graphics.Color.BLACK)
        }

        return ViewHolder(textView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        Log.d("TestSimpleAdapter", "onBindViewHolder позиция $position: ${items[position]}")
        (holder.itemView as TextView).text = "[$position] ${items[position]}"
    }

    override fun getItemCount(): Int {
        Log.d("TestSimpleAdapter", "getItemCount: ${items.size}")
        return items.size
    }

    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView)
}