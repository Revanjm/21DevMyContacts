# Диагностика ICE соединения и аудио в WebRTC

## Проблемы, которые были исправлены

### 1. 🔧 **Улучшенное логирование ICE соединения**
**Проблема:** Недостаточно информации для диагностики ICE соединения
**Решение:**
- Добавили подробное логирование состояний ICE соединения
- Добавили логирование ICE gathering процесса
- Добавили логирование отправки ICE кандидатов
- Добавили проверку создания аудио трека

### 2. 🎤 **Исправление создания аудио трека**
**Проблема:** Аудио трек мог быть null при добавлении в PeerConnection
**Решение:**
- Добавили проверку на null для localAudioTrack
- Добавили автоматическое создание трека если его нет
- Улучшили логирование процесса создания трека

### 3. 🧊 **Улучшенная диагностика ICE кандидатов**
**Проблема:** ICE кандидаты не логировались подробно
**Решение:**
- Добавили логирование деталей ICE кандидатов
- Добавили проверку targetUserId и callId перед отправкой
- Улучшили обработку ошибок при отправке кандидатов

## Ключевые логи для диагностики

### ICE соединение
```
🆕 ICE connection: NEW - Starting connection process
🔍 ICE connection: CHECKING - Testing connectivity
✅ AUDIO CONNECTION ESTABLISHED - Can talk now!
🎉 ICE connection: COMPLETED - All candidates tested
```

### ICE gathering
```
🆕 ICE gathering: NEW - Starting to gather candidates
🔍 ICE gathering: GATHERING - Collecting candidates
✅ ICE gathering: COMPLETE - All candidates collected
```

### ICE кандидаты
```
🧊 New ICE candidate: 0 - candidate:1 1 UDP 2113667326 192.168.1.100 54400...
📤 Sending ICE candidate to targetUserId
```

### Аудио трек
```
✅ Audio track added to peer connection
❌ Local audio track is null! Creating new one...
✅ Audio track created and added to peer connection
```

## Возможные причины проблем

### 1. **ICE соединение не устанавливается**
**Причины:**
- Проблемы с NAT/firewall
- Недоступность TURN серверов
- Неправильные ICE серверы
- Проблемы с сетью

**Диагностика:**
```bash
# Проверьте логи на наличие:
🔍 ICE connection: CHECKING - Testing connectivity
❌ ICE connection: FAILED - No connectivity
```

### 2. **Аудио трек не создается**
**Причины:**
- Отсутствие разрешений на микрофон
- Проблемы с AudioManager
- Ошибки в createLocalMediaStream

**Диагностика:**
```bash
# Проверьте логи на наличие:
❌ Local audio track is null! Creating new one...
❌ Failed to create local audio track
```

### 3. **ICE кандидаты не отправляются**
**Причины:**
- WebSocket не подключен
- Отсутствует targetUserId или callId
- Проблемы с emitSafe

**Диагностика:**
```bash
# Проверьте логи на наличие:
⚠️ No target user or call ID for ICE candidate
📤 Sending ICE candidate to targetUserId
```

## Решения проблем

### 1. **Если ICE соединение не устанавливается:**
- Проверьте доступность TURN серверов
- Убедитесь что устройства в одной сети или есть TURN серверы
- Проверьте настройки firewall

### 2. **Если аудио трек не создается:**
- Проверьте разрешения на микрофон
- Убедитесь что AudioManager настроен правильно
- Проверьте логи createLocalMediaStream

### 3. **Если ICE кандидаты не отправляются:**
- Проверьте WebSocket соединение
- Убедитесь что targetUserId и callId установлены
- Проверьте emitSafe функцию

## Мониторинг в реальном времени

Следите за последовательностью логов:
1. `🎯 Creating WebRTC offer...` - создание offer
2. `✅ Offer created successfully` - offer создан
3. `🧊 ICE gathering: GATHERING` - сбор ICE кандидатов
4. `🧊 New ICE candidate` - получение кандидатов
5. `🔍 ICE connection: CHECKING` - тестирование соединения
6. `✅ AUDIO CONNECTION ESTABLISHED` - соединение установлено
