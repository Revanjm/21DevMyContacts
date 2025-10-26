# Исправления проблем с WebRTC аудио и ICE соединением

## Проблемы, которые были исправлены

### 1. 🔧 **Постоянные отключения WebSocket**
**Проблема:** WebSocket отключался сразу после подключения во время звонков
**Решение:**
- Изменили `forceNew = true` для создания стабильных соединений
- Уменьшили задержки переподключения (1-5 секунд)
- Добавили настройки `upgrade = true` и `rememberUpgrade = true`
- Улучшили обработку ошибок подключения

### 2. 🧊 **Отсутствие ICE соединения**
**Проблема:** ICE кандидаты не обрабатывались, аудио не устанавливалось
**Решение:**
- Улучшили обработку ICE кандидатов в `handleIceCandidateFromServer`
- Добавили кэширование ожидающих ICE кандидатов
- Улучшили настройки PeerConnection для лучшего NAT traversal
- Добавили больше TURN серверов

### 3. 🎤 **Проблемы с микрофоном**
**Проблема:** Микрофон не работал или работал нестабильно
**Решение:**
- Улучшили настройки аудио констрейнтов
- Добавили принудительное включение трека после создания
- Улучшили настройки AudioManager
- Добавили поддержку Bluetooth SCO

## Технические детали исправлений

### WebSocket настройки
```kotlin
val opts = IO.Options().apply {
    forceNew = true // Стабильные соединения
    reconnectionDelay = 1000 // Быстрое переподключение
    reconnectionDelayMax = 5000 // Максимум 5 секунд
    timeout = 20000 // 20 секунд таймаут
    upgrade = true // Апгрейд с polling на websocket
    rememberUpgrade = true // Запоминаем успешный апгрейд
}
```

### Улучшенные аудио настройки
```kotlin
val audioConstraints = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
    mandatory.add(MediaConstraints.KeyValuePair("googDAEchoCancellation", "true"))
}
```

### ICE серверы
```kotlin
private val iceServers = listOf(
    // Google STUN серверы
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302"),
    // Дополнительные STUN серверы
    PeerConnection.IceServer.builder("stun:stun.voipbuster.com:3478"),
    // TURN серверы для NAT traversal
    PeerConnection.IceServer.builder("turn:global.relay.metered.ca:80")
        .setUsername("...").setPassword("..."),
    // TCP TURN серверы
    PeerConnection.IceServer.builder("turn:global.relay.metered.ca:443?transport=tcp")
)
```

### PeerConnection настройки
```kotlin
val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
    iceCandidatePoolSize = 10 // Больше кандидатов
    iceConnectionReceivingTimeout = 30000 // 30 секунд
    iceBackupCandidatePairPingInterval = 25000 // 25 секунд
    enableDscp = true // Quality of Service
    surfaceIceCandidatesOnIceTransportTypeChanged = true
}
```

## Ожидаемые результаты

1. **Стабильное WebSocket соединение** - меньше отключений во время звонков
2. **Успешное установление аудио** - ICE кандидаты обрабатываются корректно
3. **Работающий микрофон** - улучшенные настройки аудио
4. **Лучший NAT traversal** - больше TURN серверов для сложных сетей

## Мониторинг

Для отслеживания улучшений следите за логами:
- `✅ Socket connected` - успешное подключение WebSocket
- `🧊 Processing X pending ICE candidates` - обработка кандидатов
- `✅ Added remote ICE candidate` - успешное добавление кандидатов
- `✅ AUDIO CONNECTION ESTABLISHED` - установление аудио соединения
- `🎵 Audio setup for call completed` - настройка аудио завершена
