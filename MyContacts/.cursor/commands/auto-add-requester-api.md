# API с автоматическим добавлением запрашивающего устройства

## Обновленные API endpoints

### 1. GET /online-devices?userId=USER_ID
**Описание:** Получает список всех онлайн устройств и автоматически добавляет запрашивающее устройство в список.

**Параметры:**
- `userId` (query, опциональный) - ID устройства, которое запрашивает список

**Ответ:**
```json
{
  "status": "Success",
  "onlineDevices": [...],
  "total": 5,
  "requesterAdded": true,
  "timestamp": "2025-01-25T11:12:49.998Z"
}
```

### 2. GET /status?userId=USER_ID
**Описание:** Получает общий статус сервера и автоматически добавляет запрашивающее устройство в список онлайн устройств.

**Параметры:**
- `userId` (query, опциональный) - ID устройства, которое запрашивает статус

**Ответ:**
```json
{
  "connectedUsers": [...],
  "totalConnected": 3,
  "onlineDevices": [...],
  "totalOnlineDevices": 5,
  "requesterAdded": true,
  "callStats": {...},
  "offlineCallStats": {...},
  "chatStats": {...},
  "fcmStats": {...},
  "serverTime": "2025-01-25T11:12:49.998Z",
  "uptime": 3600
}
```

### 3. POST /heartbeat
**Описание:** Отправляет heartbeat и автоматически добавляет устройство в список онлайн устройств.

**Тело запроса:**
```json
{
  "userId": "user123",
  "deviceInfo": {
    "platform": "android",
    "version": "1.0.0"
  }
}
```

**Ответ:**
```json
{
  "status": "Success",
  "message": "Heartbeat received",
  "userId": "user123",
  "onlineDevices": [...],
  "total": 5,
  "timestamp": "2025-01-25T11:12:49.998Z"
}
```

## Особенности

1. **Автоматическое добавление:** Если передан `userId`, устройство автоматически добавляется в список онлайн устройств
2. **Метки источника:** Добавленные устройства помечаются как `requester: true` или `heartbeat: true`
3. **Временные метки:** Добавляется информация о времени добавления (`addedAt`, `lastHeartbeat`)
4. **Обратная совместимость:** API работает как с `userId`, так и без него

## Примеры использования

```bash
# Получить список онлайн устройств (без добавления себя)
curl "http://localhost:3000/online-devices"

# Получить список онлайн устройств (с добавлением себя)
curl "http://localhost:3000/online-devices?userId=my-device-id"

# Получить статус сервера (с добавлением себя)
curl "http://localhost:3000/status?userId=my-device-id"

# Отправить heartbeat
curl -X POST "http://localhost:3000/heartbeat" \
  -H "Content-Type: application/json" \
  -d '{"userId": "my-device-id", "deviceInfo": {"platform": "android"}}'
```
