# Fandomon - Система мониторинга для Fandomat

Fandomon - это Android приложение для мониторинга и управления приложением Fandomat на планшетах.

## Основные функции

### 1. Мониторинг приложения Fandomat
- ✅ Отслеживание статуса запуска/остановки
- ✅ Мониторинг логов приложения
- ✅ Автоматический перезапуск при сбоях
- ✅ Контроль версии приложения

### 2. Системный мониторинг
- ✅ Отслеживание интернет-соединения
- ✅ Мониторинг состояния питания и батареи
- ✅ Контроль температуры устройства
- ✅ Отслеживание перезагрузок системы

### 3. Журналирование событий
- ✅ SQLite база данных для хранения событий
- ✅ Классификация событий по типам и серьезности
- ✅ Автоматическая очистка старых логов
- ✅ Экспорт событий

### 4. Удаленная отправка данных
- ✅ Отправка через REST API
- ✅ Поддержка MQTT протокола
- ✅ Очередь неотправленных событий
- ✅ Автоматические повторные попытки

### 5. Удаленное управление (опционально)
- ✅ Команды перезапуска Fandomat
- ✅ Проверка статуса системы
- ✅ Настройка параметров мониторинга

## Архитектура

### Основные компоненты

1. **FandomonMonitoringService** - Основной сервис мониторинга
2. **DatabaseHelper** - Управление SQLite базой данных
3. **NetworkSender** - Отправка данных на сервер
4. **FandomatChecker** - Проверка статуса Fandomat
5. **LogMonitor** - Мониторинг логов
6. **BroadcastReceiver'ы** - Обработка системных событий

### Структура базы данных

#### Таблица events
- `id` - Уникальный идентификатор
- `type` - Тип события
- `details` - Детали события
- `timestamp` - Время события
- `is_sent` - Статус отправки
- `severity` - Уровень серьезности
- `additional_data` - Дополнительные данные в JSON

#### Таблица power_status
- `id` - Уникальный идентификатор
- `timestamp` - Время записи
- `is_charging` - Статус зарядки
- `battery_level` - Уровень батареи
- `temperature` - Температура

#### Таблица settings
- `key` - Ключ настройки
- `value` - Значение настройки
- `updated_timestamp` - Время обновления

## Типы событий

### События Fandomat
- `FANDOMAT_CRASHED` - Падение приложения
- `FANDOMAT_RESTARTED` - Перезапуск приложения
- `FANDOMAT_START_FAILED` - Неудачный запуск
- `FANDOMAT_RESTORED` - Восстановление работы
- `FANDOMAT_ERROR` - Ошибка в логах

### Сетевые события
- `NETWORK_DISCONNECTED` - Отключение от сети
- `NETWORK_RESTORED` - Восстановление сети
- `NETWORK_SLOW` - Медленная сеть

### События питания
- `POWER_DISCONNECTED` - Отключение питания
- `POWER_CONNECTED` - Подключение питания
- `POWER_LOW` - Низкий заряд батареи
- `BATTERY_CRITICAL` - Критически низкий заряд

### События Fandomon
- `FANDOMON_STARTED` - Запуск мониторинга
- `FANDOMON_STOPPED` - Остановка мониторинга
- `FANDOMON_ERROR` - Ошибка мониторинга

### Системные события
- `SYSTEM_REBOOT` - Перезагрузка системы
- `LOGS_MISSING` - Отсутствие логов

## Настройки

### Основные параметры
- `monitoring_interval` - Интервал мониторинга (сек, по умолчанию: 30)
- `status_send_interval` - Интервал отправки статуса (сек, по умолчанию: 300)
- `auto_restart_fandomat` - Автоперезапуск Fandomat (true/false)
- `log_retention_days` - Срок хранения логов (дни, по умолчанию: 7)
- `server_url` - URL REST API сервера
- `mqtt_broker` - URL MQTT брокера

### Сетевые настройки
- `max_events_per_batch` - Максимум событий в пакете (по умолчанию: 100)
- `retry_attempts` - Количество повторных попыток (по умолчанию: 3)
- `retry_delay` - Задержка между попытками (мс, по умолчанию: 5000)

## Установка и настройка

### Требования
- Android 11 (API 30) или выше
- Разрешения:
  - `INTERNET` - Для отправки данных
  - `ACCESS_NETWORK_STATE` - Для мониторинга сети
  - `PACKAGE_USAGE_STATS` - Для мониторинга приложений
  - `FOREGROUND_SERVICE` - Для фонового сервиса
  - `RECEIVE_BOOT_COMPLETED` - Для автозапуска
  - `READ_LOGS` - Для чтения логов (требует root)

### Первоначальная настройка

1. **Установите приложение** на планшет
2. **Предоставьте разрешения**:
   - Usage Access (Settings → Special access → Usage access)
   - Ignore battery optimizations
   - Autostart permission
3. **Настройте параметры** в приложении:
   - URL сервера для отправки данных
   - MQTT брокер (опционально)
   - Интервалы мониторинга
4. **Запустите сервис мониторинга**

### API сервера

#### Endpoint для событий
```
POST /api/events
Content-Type: application/json

{
  "device_id": "unique-device-id",
  "event_id": 123,
  "type": "fandomat_crashed",
  "details": "Application stopped unexpectedly",
  "timestamp": 1640995200000,
  "severity": "error",
  "additional_data": {...},
  "app_version": "1.0.0",
  "device_model": "Samsung Galaxy Tab",
  "android_version": "11"
}
```

#### Endpoint для статуса
```
POST /api/status
Content-Type: application/json

{
  "device_id": "unique-device-id",
  "timestamp": 1640995200000,
  "fandomat_running": true,
  "network_connected": true,
  "fandomon_version": "1.0.0",
  "battery_level": 85,
  "device_model": "Samsung Galaxy Tab",
  "android_version": "11"
}
```

## Разработка

### Сборка проекта
```bash
./gradlew assembleDebug
```

### Тестирование
```bash
./gradlew test
./gradlew connectedAndroidTest
```

### Генерация APK
```bash
./gradlew assembleRelease
```

## Troubleshooting

### Сервис не запускается
1. Проверьте разрешения Usage Access
2. Убедитесь, что приложение исключено из оптимизации батареи
3. Проверьте логи в Android Studio

### Не отправляются события
1. Проверьте интернет-соединение
2. Убедитесь в правильности URL сервера
3. Проверьте логи сетевых запросов

### Fandomat не перезапускается
1. Убедитесь, что приложение Fandomat установлено
2. Проверьте разрешение на запуск других приложений
3. Включите автоперезапуск в настройках

## Версионирование

Версия: 1.0.0
- Первый релиз с основной функциональностью мониторинга

## Лицензия

Внутренний проект компании Tastamat.

## Поддержка

Для получения поддержки обращайтесь к команде разработки.