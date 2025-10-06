# Migration Guide: v1.0 → v2.0

## Обзор изменений

Version 2.0 вводит значительные архитектурные изменения для оптимизации производительности.

### Ключевые изменения
- ✅ AlarmManager заменяет ScheduledExecutorService
- ✅ Новые компоненты: AlarmScheduler, MonitoringAlarmReceiver
- ✅ Облегченный FandomonMonitoringService
- ✅ Исправлены memory leaks и blocking calls
- ✅ Новые разрешения для Android 12+

## Для пользователей

### Требуемые действия

#### 1. Обновление приложения

```bash
# Остановите текущий сервис мониторинга
adb shell am stopservice com.tastamat.fandomon/.FandomonMonitoringService

# Установите новую версию
adb install -r app-release.apk

# Запустите приложение
adb shell am start -n com.tastamat.fandomon/.MainActivity
```

#### 2. Предоставьте новые разрешения (Android 12+)

```bash
# Разрешение для точных алярмов
adb shell pm grant com.tastamat.fandomon android.permission.SCHEDULE_EXACT_ALARM
```

Или через настройки:
- Settings → Apps → Fandomon → Alarms & reminders → Allow

#### 3. Проверка работы

```bash
# Проверьте, что сервис запущен
adb shell dumpsys activity services | grep FandomonMonitoringService

# Проверьте алярмы
adb shell dumpsys alarm | grep fandomon

# Просмотрите логи
adb logcat -s FandomonService MonitoringAlarmReceiver AlarmScheduler
```

### Ожидаемые изменения

**Уведомление:**
- Старое: "Fandomon Мониторинг - Активен с HH:mm"
- Новое: "Fandomon Мониторинг (Режим энергосбережения) - AlarmManager активен"

**Интервалы (без изменений):**
- Проверка Fandomat: 30 секунд
- Отправка статуса: 5 минут
- Проверка логов: 1 минута

**Производительность:**
- CPU: 15-20% → <1% (режим ожидания)
- Battery: ~10% за 8 часов → ~3% за 8 часов

### Откат на v1.0 (если необходимо)

```bash
# Удалите v2.0
adb uninstall com.tastamat.fandomon

# Установите v1.0
adb install app-v1.0.apk

# Предоставьте разрешения
adb shell appops set com.tastamat.fandomon GET_USAGE_STATS allow
```

## Для разработчиков

### API Changes

#### FandomatChecker

**Было:**
```kotlin
fun startFandomat(): Boolean {
    // ...
    Thread.sleep(3000)
    return isFandomatRunning()
}
```

**Стало:**
```kotlin
suspend fun startFandomat(): Boolean {
    // ...
    delay(3000)
    return isFandomatRunning()
}
```

**Миграция:**
Все вызовы `startFandomat()` и `restartFandomat()` должны быть в suspend контексте:

```kotlin
// Старый код
fandomatChecker.startFandomat()

// Новый код
scope.launch {
    fandomatChecker.startFandomat()
}
```

#### LogAnalyzer

**Было:**
```kotlin
private val regexCache = ConcurrentHashMap<String, MatchResult?>()

private fun parseLogLine(line: String): LogEntry {
    val match = regexCache.computeIfAbsent(line) {
        pattern.find(it)
    }
}
```

**Стало:**
```kotlin
private val logPattern = Regex("""...""")

private fun parseLogLine(line: String): LogEntry {
    val match = logPattern.find(line)
}
```

**Миграция:**
Нет изменений в публичном API. Внутренняя оптимизация.

#### FandomonMonitoringService

**Было:**
```kotlin
private lateinit var scheduler: ScheduledExecutorService

scheduler.scheduleWithFixedDelay({
    checkFandomatStatus()
}, 0, 30, TimeUnit.SECONDS)
```

**Стало:**
```kotlin
private lateinit var alarmScheduler: AlarmScheduler

alarmScheduler.scheduleAllMonitoring()
```

**Миграция:**
Нет прямого использования из внешнего кода. Сервис остается прозрачным.

### Новые компоненты

#### 1. AlarmScheduler

```kotlin
val scheduler = AlarmScheduler(context)

// Запланировать все задачи
scheduler.scheduleAllMonitoring()

// Или отдельно
scheduler.scheduleMonitoring()
scheduler.scheduleStatusSending()
scheduler.scheduleLogChecking()

// Отменить все
scheduler.cancelAllMonitoring()

// Проверить разрешение (Android 12+)
if (scheduler.canScheduleExactAlarms()) {
    // Точные алярмы доступны
}
```

#### 2. MonitoringAlarmReceiver

Автоматически обрабатывает алярмы. Не требует прямого вызова.

```kotlin
// Actions
AlarmScheduler.ACTION_MONITOR_FANDOMAT
AlarmScheduler.ACTION_SEND_STATUS
AlarmScheduler.ACTION_CHECK_LOGS
```

### Изменения в Manifest

```xml
<!-- Новые разрешения -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />

<!-- Новый receiver -->
<receiver android:name=".MonitoringAlarmReceiver">
    <intent-filter>
        <action android:name="com.tastamat.fandomon.ACTION_MONITOR_FANDOMAT" />
        <action android:name="com.tastamat.fandomon.ACTION_SEND_STATUS" />
        <action android:name="com.tastamat.fandomon.ACTION_CHECK_LOGS" />
    </intent-filter>
</receiver>
```

### Изменения в зависимостях

Без изменений. Все зависимости остались прежними.

### Testing Changes

#### Новые тесты

```kotlin
@Test
fun testAlarmScheduling() {
    val scheduler = AlarmScheduler(context)
    scheduler.scheduleMonitoring()

    // Проверить, что PendingIntent создан
    val intent = Intent(context, MonitoringAlarmReceiver::class.java)
    val pendingIntent = PendingIntent.getBroadcast(...)
    assertNotNull(pendingIntent)
}

@Test
fun testAlarmReceiver() {
    val receiver = MonitoringAlarmReceiver()
    val intent = Intent(AlarmScheduler.ACTION_MONITOR_FANDOMAT)

    receiver.onReceive(context, intent)

    // Проверить выполнение задачи
}
```

#### Измененные тесты

```kotlin
@Test
fun testFandomatStart() = runTest {
    val checker = FandomatChecker(context)

    // Теперь suspend функция
    val result = checker.startFandomat()

    assertTrue(result)
}
```

### Performance Profiling

#### До миграции (v1.0)
```
CPU: 15-20% (3 ScheduledExecutor threads)
Memory: ~80-100 MB (regex cache leak)
Battery: ~10% за 8h
```

#### После миграции (v2.0)
```
CPU: <1% idle, 2-3% на алярм
Memory: ~40-60 MB (stable)
Battery: ~3% за 8h
```

### Debugging

#### Логирование алярмов

```bash
# Все алярмы Fandomon
adb shell dumpsys alarm | grep -A 20 fandomon

# Детали конкретного алярма
adb shell dumpsys alarm | grep "ACTION_MONITOR_FANDOMAT"
```

#### Проверка WakeLocks

```bash
adb shell dumpsys power | grep Fandomon
```

#### Проверка broadcast receivers

```bash
adb shell dumpsys package com.tastamat.fandomon | grep Receiver
```

## Потенциальные проблемы

### 1. Алярмы не срабатывают

**Симптомы:**
- Fandomat не проверяется
- События не отправляются
- Нет логов от MonitoringAlarmReceiver

**Решение:**
```bash
# Проверьте разрешение
adb shell dumpsys package com.tastamat.fandomon | grep SCHEDULE_EXACT_ALARM

# Предоставьте разрешение
adb shell pm grant com.tastamat.fandomon android.permission.SCHEDULE_EXACT_ALARM

# Перезапустите сервис
adb shell am stopservice com.tastamat.fandomon/.FandomonMonitoringService
adb shell am startservice com.tastamat.fandomon/.FandomonMonitoringService
```

### 2. Doze Mode блокирует алярмы

**Симптомы:**
- Алярмы не срабатывают когда экран выключен
- Большие задержки между проверками

**Решение:**
```bash
# Добавьте в whitelist оптимизации батареи
adb shell dumpsys deviceidle whitelist +com.tastamat.fandomon

# Или через настройки
Settings → Battery → Battery optimization → Fandomon → Don't optimize
```

### 3. Высокое потребление батареи

**Симптомы:**
- Battery drain хуже чем в v1.0
- WakeLock держится долго

**Решение:**
- Проверьте, что используется AlarmManager (логи должны показывать "AlarmManager режим")
- Убедитесь, что WakeLock timeout установлен (60 секунд)
- Проверьте логи на бесконечные циклы

```bash
adb logcat | grep -E "WakeLock|AlarmManager"
```

### 4. startFandomat() compilation error

**Симптомы:**
```
Error: suspend function 'startFandomat' should be called only from a coroutine or another suspend function
```

**Решение:**
```kotlin
// Оберните в корутину
GlobalScope.launch {
    fandomatChecker.startFandomat()
}

// Или используйте suspend caller
suspend fun myFunction() {
    fandomatChecker.startFandomat()
}
```

## Rollback Plan

Если после миграции возникли критические проблемы:

### Шаг 1: Создайте бэкап БД

```bash
adb pull /data/data/com.tastamat.fandomon/databases/fandomon.db ./backup/
```

### Шаг 2: Откатитесь на v1.0

```bash
adb uninstall com.tastamat.fandomon
adb install app-v1.0.apk
```

### Шаг 3: Восстановите БД (опционально)

```bash
adb push ./backup/fandomon.db /data/data/com.tastamat.fandomon/databases/
adb shell chmod 660 /data/data/com.tastamat.fandomon/databases/fandomon.db
```

### Шаг 4: Сообщите о проблеме

Создайте Issue на GitHub с:
- Описанием проблемы
- Логами (`adb logcat`)
- Информацией об устройстве
- Шагами для воспроизведения

## Поддержка

Вопросы по миграции? Создайте Issue:
https://github.com/Bahrulloif/fandomon/issues

---

**Дата последнего обновления:** 2025-10-06
**Версия документа:** 1.0
