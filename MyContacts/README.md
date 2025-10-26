# MyContacts Server

Модульный сервер для MyContacts с поддержкой звонков и сообщений.

## 🚀 Запуск в Replit

1. **Автоматический запуск**: Replit автоматически запустит сервер при загрузке проекта
2. **Ручной запуск**: Используйте команду `npm start` в консоли
3. **Разработка**: Используйте `npm run dev` для автоматической перезагрузки

## 📁 Структура проекта

```
├── app/src/main/java/com/ppnkdeapp/mycontacts/network/
│   ├── server.js          # Основной сервер (509 строк)
│   ├── message.js         # Модуль сообщений (187 строк)
│   └── call.js           # Модуль звонков (727 строк)
├── package.json          # Зависимости Node.js
└── .replit              # Конфигурация Replit
```

## 🔧 Модульная архитектура

- **server.js**: Express сервер + Socket.IO + API endpoints
- **message.js**: Логика сообщений и чатов
- **call.js**: Логика звонков и WebRTC

## 📡 API Endpoints

- `GET /` - Главная страница
- `GET /status` - Статус сервера
- `POST /messages/send` - Отправка сообщения
- `GET /messages/:chat_id` - Получение сообщений
- `POST /register-fcm-token` - Регистрация FCM токена
- `GET /call-history/:userId` - История звонков

## 🌐 WebSocket Events

- `register` - Регистрация пользователя
- `send_message` - Отправка сообщения
- `initiate_call` - Инициация звонка
- `accept_call` - Принятие звонка
- `reject_call` - Отклонение звонка
- `webrtc_signal` - WebRTC сигналинг

## 🛠️ Установка зависимостей

```bash
npm install
```

## 🚀 Запуск

```bash
npm start
```

Сервер будет доступен на порту 3000.
