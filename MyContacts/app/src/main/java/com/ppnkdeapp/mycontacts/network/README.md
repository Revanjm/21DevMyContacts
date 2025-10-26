# Call Server

Сервер для обработки звонков с WebSocket поддержкой.

## Установка

### Вариант 1: С WebPush (полная версия)
```bash
# Установите Node.js с https://nodejs.org/
npm install
npm start
```

### Вариант 2: Упрощенная версия (только WebSocket)
```bash
# Установите Node.js с https://nodejs.org/
# Скопируйте package-simple.json в package.json
cp package-simple.json package.json
npm install
node server-simple.js
```

## API Endpoints

### Устройства
- `POST /api/devices/register` - Регистрация устройства
- `POST /api/devices/unregister` - Отмена регистрации устройства  
- `GET /api/devices` - Получение списка устройств

### Звонки
- `POST /api/calls` - Создание звонка
- `GET /api/calls/:callId` - Получение информации о звонке
- `PUT /api/calls/:callId` - Обновление статуса звонка
- `DELETE /api/calls/:callId` - Удаление звонка
- `GET /api/users/:userId/calls` - Получение звонков пользователя

### Статус
- `GET /status` - Статус сервера

## WebSocket Events

### Клиент → Сервер
- `register` - Регистрация пользователя
- `webrtc_signal` - WebRTC сигналы

### Сервер → Клиент  
- `push_notification` - Push уведомления
- `device_list_update` - Обновление списка устройств
- `webrtc_offer` - WebRTC offer
- `webrtc_answer` - WebRTC answer
- `webrtc_ice_candidate` - ICE кандидаты

## Примеры использования

### Регистрация устройства
```bash
curl -X POST http://localhost:3000/api/devices/register \
  -H "Content-Type: application/json" \
  -d '{"deviceId": "device123"}'
```

### Создание звонка
```bash
curl -X POST http://localhost:3000/api/calls \
  -H "Content-Type: application/json" \
  -d '{
    "callId": "call123",
    "callerId": "user1", 
    "recipientId": "user2",
    "status": "offer",
    "createdAt": 1234567890
  }'
```

### Получение статуса
```bash
curl http://localhost:3000/status
```

## Требования

- Node.js 14+
- Express.js
- Socket.IO
- CORS
- Body-parser

## Порты

По умолчанию сервер запускается на порту 3000.
Можно изменить через переменную окружения PORT:

```bash
PORT=8080 node server-simple.js
```

