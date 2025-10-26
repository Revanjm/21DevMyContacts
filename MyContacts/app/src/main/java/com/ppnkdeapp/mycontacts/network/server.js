const express = require("express");
const http = require("http");
const socketIo = require("socket.io");
const cors = require("cors");
const bodyParser = require("body-parser");
const webpush = require("web-push");
const path = require("path");

class CallServer {
    constructor() {
        this.app = express();
        this.server = http.createServer(this.app);
        this.io = socketIo(this.server, {
            cors: {
                origin: "*",
                methods: ["GET", "POST"],
            },
        });

        // Хранилище звонков
        this.activeCalls = new Map();
        // 🔥 Хранилище подключенных пользователей (было connectedDevices)
        this.connectedUsers = new Map();
        // 🔥 СПИСОК ПОДКЛЮЧЕННЫХ ПОЛЬЗОВАТЕЛЕЙ (было connectedDeviceIds)
        this.connectedUserIds = new Set();
        // 🔥 НОВОЕ: Хранилище подключенных устройств
        this.connectedDevices = new Map();

        // 🔥 WEBPUSH: Хранилище push подписок
        this.pushSubscriptions = new Map();

        // 🔥 WEBPUSH: Инициализация VAPID ключей
        this.initializeWebPush();

        this.setupMiddleware();
        this.setupRoutes();
        this.setupSocketHandlers();
    }

    // 🔥 WEBPUSH: Инициализация VAPID ключей
    initializeWebPush() {
        // Генерируем VAPID keys если их нет
        if (!process.env.VAPID_PUBLIC_KEY || !process.env.VAPID_PRIVATE_KEY) {
            console.log("🔑 Generating new VAPID keys...");
            const vapidKeys = webpush.generateVAPIDKeys();

            process.env.VAPID_PUBLIC_KEY = vapidKeys.publicKey;
            process.env.VAPID_PRIVATE_KEY = vapidKeys.privateKey;

            console.log("✅ VAPID Keys generated:");
            console.log("   Public Key:", vapidKeys.publicKey);
            console.log(
                "   Private Key:",
                vapidKeys.privateKey.substring(0, 20) + "...",
            );
        }

        webpush.setVapidDetails(
            "mailto:your-app@example.com",
            process.env.VAPID_PUBLIC_KEY,
            process.env.VAPID_PRIVATE_KEY,
        );

        console.log("✅ WebPush initialized");
    }

    setupMiddleware() {
        this.app.use(cors());
        this.app.use(bodyParser.json());
        this.app.use("/sw.js", express.static(path.join(__dirname, "sw.js")));
    }

    setupRoutes() {
        // 🔥 WEBPUSH: Получение VAPID публичного ключа
        this.app.get("/api/push/vapid-key", (req, res) => {
            res.json({
                success: true,
                publicKey: process.env.VAPID_PUBLIC_KEY,
            });
        });

        // 🔥 API ДЛЯ СТАТУСА СЕРВЕРА
        this.app.get("/api/status", (req, res) => {
            try {
                const status = this.getServerStatus();
                res.json({
                    success: true,
                    ...status,
                    serverTime: new Date().toISOString(),
                    uptime: process.uptime(),
                });
            } catch (error) {
                console.error("❌ Error getting server status:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 ОБСЛУЖИВАНИЕ ДОМАШНЕЙ СТРАНИЦЫ
        this.app.get("/", (req, res) => {
            res.sendFile(path.join(__dirname, "index.html"));
        });

        // 🔥 WEBPUSH: Сохранение push подписки
        this.app.post("/api/push/subscribe", async (req, res) => {
            try {
                const { userId, subscription } = req.body; // 🔥 было deviceId

                if (!userId || !subscription) {
                    return res.status(400).json({
                        success: false,
                        error: "User ID and subscription are required",
                    });
                }

                console.log(`📱 Saving push subscription for user: ${userId}`);

                // Сохраняем подписку
                this.pushSubscriptions.set(userId, subscription);

                // Отправляем тестовое уведомление для проверки
                try {
                    await this.sendPushNotification(userId, {
                        title: "Подключение установлено",
                        body: "Вы будете получать уведомления о звонках",
                        type: "connection_established",
                    });
                } catch (error) {
                    console.log("⚠️ Test notification failed:", error.message);
                }

                res.json({
                    success: true,
                    message: "Push subscription saved successfully",
                });
            } catch (error) {
                console.error("❌ Error saving push subscription:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 WEBPUSH: Удаление push подписки
        this.app.post("/api/push/unsubscribe", (req, res) => {
            try {
                const { userId } = req.body; // 🔥 было deviceId

                if (!userId) {
                    return res.status(400).json({
                        success: false,
                        error: "User ID is required",
                    });
                }

                const wasRemoved = this.pushSubscriptions.delete(userId);

                if (wasRemoved) {
                    console.log(
                        `✅ Push subscription removed for user: ${userId}`,
                    );
                } else {
                    console.log(
                        `⚠️ No push subscription found for user: ${userId}`,
                    );
                }

                res.status(204).send();
            } catch (error) {
                console.error("❌ Error removing push subscription:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 WEBPUSH: Отправка тестового push уведомления
        this.app.post("/api/push/test", async (req, res) => {
            try {
                const { userId } = req.body; // 🔥 было deviceId

                if (!userId) {
                    return res.status(400).json({
                        success: false,
                        error: "User ID is required",
                    });
                }

                const success = await this.sendPushNotification(userId, {
                    title: "Тестовое уведомление",
                    body: "Это тестовое push уведомление от сервера",
                    type: "test_notification",
                });

                if (success) {
                    res.json({
                        success: true,
                        message: "Test notification sent successfully",
                    });
                } else {
                    res.status(404).json({
                        success: false,
                        error: "User not found or push subscription expired",
                    });
                }
            } catch (error) {
                console.error("❌ Error sending test notification:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 HTTP МЕТОД РЕГИСТРАЦИИ ПОЛЬЗОВАТЕЛЯ
        this.app.post("/api/users/register", (req, res) => {
            try {
                const { userId } = req.body; // 🔥 было deviceId

                if (!userId) {
                    return res.status(400).json({
                        success: false,
                        error: "User ID is required",
                    });
                }

                console.log(`👤 Registering user: ${userId}`);

                // Добавляем пользователя в список
                const wasAdded = !this.connectedUserIds.has(userId);
                this.connectedUserIds.add(userId);

                console.log(
                    `✅ User ${userId} ${wasAdded ? "added to" : "already in"} connected users list`,
                );
                console.log(
                    `📊 Total connected users: ${this.connectedUserIds.size}`,
                );

                // 🔥 ОТПРАВЛЯЕМ ОБНОВЛЕННЫЙ СПИСОК ВСЕМ ПОДКЛЮЧЕННЫМ ПОЛЬЗОВАТЕЛЯМ
                this.broadcastUserListUpdate();

                res.json({
                    success: true,
                    userId: userId,
                    userList: Array.from(this.connectedUserIds),
                    message: `User ${userId} registered successfully`,
                });
            } catch (error) {
                console.error("❌ Error registering user:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 HTTP МЕТОД ОТМЕНЫ РЕГИСТРАЦИИ ПОЛЬЗОВАТЕЛЯ
        this.app.post("/api/users/unregister", (req, res) => {
            try {
                console.log(`👤 USER UNREGISTER REQUEST RECEIVED`);
                console.log(`👤 Request body:`, req.body);
                console.log(`👤 Request headers:`, req.headers);
                
                const { userId } = req.body; // 🔥 было deviceId

                if (!userId) {
                    console.log(`❌ User ID is missing in request body`);
                    return res.status(400).json({
                        success: false,
                        error: "User ID is required",
                    });
                }

                console.log(`👤 Unregistering user: ${userId}`);
                console.log(`👤 Current connected users before removal:`, Array.from(this.connectedUserIds));

                // Удаляем пользователя из списка
                const wasRemoved = this.connectedUserIds.delete(userId);

                if (wasRemoved) {
                    console.log(
                        `✅ User ${userId} removed from connected users list`,
                    );
                    console.log(
                        `📊 Total connected users: ${this.connectedUserIds.size}`,
                    );
                    console.log(`👤 Remaining connected users:`, Array.from(this.connectedUserIds));

                    // 🔥 ОТПРАВЛЯЕМ ОБНОВЛЕННЫЙ СПИСОК ВСЕМ ПОДКЛЮЧЕННЫМ ПОЛЬЗОВАТЕЛЯМ
                    this.broadcastUserListUpdate();
                } else {
                    console.log(
                        `⚠️ User ${userId} was not in connected users list`,
                    );
                }

                res.status(204).send(); // No Content
            } catch (error) {
                console.error("❌ Error unregistering user:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 HTTP МЕТОД РЕГИСТРАЦИИ УСТРОЙСТВА
        this.app.post("/api/devices/register", (req, res) => {
            try {
                const { deviceId, userId } = req.body;

                if (!deviceId || !userId) {
                    return res.status(400).json({
                        success: false,
                        error: "Device ID and User ID are required",
                    });
                }

                console.log(`📱 Registering device: ${deviceId} for user: ${userId}`);

                // Добавляем устройство в список подключенных устройств
                this.connectedDevices.set(deviceId, {
                    deviceId: deviceId,
                    userId: userId,
                    connectedAt: new Date().toISOString()
                });

                console.log(`✅ Device ${deviceId} registered successfully`);
                console.log(`📊 Total connected devices: ${this.connectedDevices.size}`);

                // Отправляем обновленный список устройств всем подключенным пользователям
                this.broadcastDeviceListUpdate();

                res.json({
                    success: true,
                    message: "Device registered successfully",
                    deviceId: deviceId,
                    totalDevices: this.connectedDevices.size
                });
            } catch (error) {
                console.error("❌ Error registering device:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 HTTP МЕТОД ОТМЕНЫ РЕГИСТРАЦИИ УСТРОЙСТВА
        this.app.post("/api/devices/unregister", (req, res) => {
            try {
                console.log(`📱 DEVICE UNREGISTER REQUEST RECEIVED`);
                console.log(`📱 Request body:`, req.body);
                console.log(`📱 Request headers:`, req.headers);
                
                const { deviceId } = req.body;

                if (!deviceId) {
                    console.log(`❌ Device ID is missing in request body`);
                    return res.status(400).json({
                        success: false,
                        error: "Device ID is required",
                    });
                }

                console.log(`📱 Unregistering device: ${deviceId}`);
                console.log(`📱 Current connected devices before removal:`, Array.from(this.connectedDevices.keys()));

                // Удаляем устройство из списка подключенных устройств
                const wasRemoved = this.connectedDevices.delete(deviceId);

                if (wasRemoved) {
                    console.log(`✅ Device ${deviceId} removed from connected devices list`);
                    console.log(`📊 Total connected devices: ${this.connectedDevices.size}`);
                    console.log(`📱 Remaining connected devices:`, Array.from(this.connectedDevices.keys()));

                    // Отправляем обновленный список устройств всем подключенным пользователям
                    this.broadcastDeviceListUpdate();
                } else {
                    console.log(`⚠️ Device ${deviceId} was not in connected devices list`);
                }

                res.status(204).send(); // No Content
            } catch (error) {
                console.error("❌ Error unregistering device:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 HTTP МЕТОД ПОЛУЧЕНИЯ ТЕКУЩЕГО СПИСКА УСТРОЙСТВ
        this.app.get("/api/devices", (req, res) => {
            try {
                const deviceList = Array.from(this.connectedDevices.keys());
                const deviceDetails = Array.from(this.connectedDevices.values());

                res.json({
                    success: true,
                    deviceList: deviceList,
                    deviceDetails: deviceDetails,
                    totalDevices: this.connectedDevices.size
                });
            } catch (error) {
                console.error("❌ Error getting device list:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 HTTP МЕТОД ПОЛУЧЕНИЯ ТЕКУЩЕГО СПИСКА ПОЛЬЗОВАТЕЛЕЙ
        this.app.get("/api/users", (req, res) => {
            try {
                const userList = Array.from(this.connectedUserIds);

                res.json({
                    success: true,
                    userList: userList,
                    totalCount: this.connectedUserIds.size,
                });
            } catch (error) {
                console.error("❌ Error getting user list:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 НОВЫЙ МЕТОД: Принудительное удаление пользователя из списка онлайн
        this.app.delete("/api/users/:userId", (req, res) => {
            try {
                const { userId } = req.params;

                if (!userId) {
                    return res.status(400).json({
                        success: false,
                        error: "User ID is required",
                    });
                }

                console.log(`🗑️ Force removing user from online list: ${userId}`);

                // Удаляем из списка подключенных пользователей
                const wasInList = this.connectedUserIds.has(userId);
                this.connectedUserIds.delete(userId);

                // Удаляем из WebSocket соединений
                const wasConnected = this.connectedUsers.has(userId);
                this.connectedUsers.delete(userId);

                // Отключаем WebSocket если он активен
                const userSocket = this.connectedUsers.get(userId);
                if (userSocket) {
                    this.io.to(userSocket.socketId).emit("force_disconnect", {
                        reason: "User removed by admin",
                        timestamp: Date.now()
                    });
                }

                console.log(`✅ User ${userId} force removed from online list`);
                console.log(`📊 Total connected users: ${this.connectedUserIds.size}`);

                // 🔥 ОТПРАВЛЯЕМ ОБНОВЛЕННЫЙ СПИСОК ВСЕМ ПОДКЛЮЧЕННЫМ ПОЛЬЗОВАТЕЛЯМ
                this.broadcastUserListUpdate();

                res.json({
                    success: true,
                    message: `User ${userId} removed from online list`,
                    wasInList: wasInList,
                    wasConnected: wasConnected,
                    remainingUsers: this.connectedUserIds.size
                });
            } catch (error) {
                console.error("❌ Error force removing user:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 НОВЫЙ МЕТОД: Принудительное удаление устройства из списка подключенных
        this.app.delete("/api/devices/:deviceId", (req, res) => {
            try {
                const { deviceId } = req.params;

                if (!deviceId) {
                    return res.status(400).json({
                        success: false,
                        error: "Device ID is required",
                    });
                }

                console.log(`🗑️ Force removing device from connected list: ${deviceId}`);

                // Удаляем устройство из списка подключенных устройств
                const deviceInfo = this.connectedDevices.get(deviceId);
                const wasRemoved = this.connectedDevices.delete(deviceId);

                if (wasRemoved) {
                    console.log(`✅ Device ${deviceId} force removed from connected devices list`);
                    console.log(`📊 Total connected devices: ${this.connectedDevices.size}`);

                    // 🔥 ОТПРАВЛЯЕМ ОБНОВЛЕННЫЙ СПИСОК ВСЕМ ПОДКЛЮЧЕННЫМ ПОЛЬЗОВАТЕЛЯМ
                    this.broadcastDeviceListUpdate();

                    res.json({
                        success: true,
                        message: `Device ${deviceId} removed from connected devices list`,
                        deviceInfo: deviceInfo,
                        remainingDevices: this.connectedDevices.size
                    });
                } else {
                    res.status(404).json({
                        success: false,
                        error: "Device not found in connected devices list",
                    });
                }
            } catch (error) {
                console.error("❌ Error force removing device:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // API для работы с ActualCall объектами

        // 🔥 НОВЫЙ ЭНДПОИНТ: Обработка ActualCall
        this.app.post("/api/calls", async (req, res) => {
            try {
                const { callId, callerId, recipientId, status, step, createdAt, offerSdp, answerSdp } = req.body;

                console.log(`📞 ActualCall received: ${callId}, step: ${step}, from: ${callerId}, to: ${recipientId}`);

                // Сохраняем ActualCall в файл
                await this.saveActualCallToFile({
                    callId,
                    callerId,
                    recipientId,
                    status,
                    step,
                    createdAt,
                    offerSdp,
                    answerSdp
                });

                // Обрабатываем в зависимости от step
                switch (step) {
                    case "request_call":
                        console.log(`📞 Call request from ${callerId} to ${recipientId}`);
                        // Отправляем push уведомление получателю
                        await this.sendActualCallNotification(recipientId, {
                            callId,
                            callerId,
                            recipientId,
                            status,
                            step,
                            createdAt,
                            offerSdp,
                            answerSdp
                        });
                        break;
                        
                    case "accept_call":
                        console.log(`✅ Call accepted by ${recipientId}`);
                        console.log(`📤 Sending accept_call notification to caller: ${callerId}`);
                        // Отправляем уведомление инициатору
                        await this.sendActualCallNotification(callerId, {
                            callId,
                            callerId,
                            recipientId,
                            status,
                            step,
                            createdAt,
                            offerSdp,
                            answerSdp
                        });
                        
                        console.log(`📤 Sending accept_call notification to recipient: ${recipientId}`);
                        // 🔥 НОВОЕ: Также отправляем уведомление получателю для запуска WebRTC
                        await this.sendActualCallNotification(recipientId, {
                            callId,
                            callerId,
                            recipientId,
                            status,
                            step,
                            createdAt,
                            offerSdp,
                            answerSdp
                        });
                        break;
                        
                    case "reject_call":
                        console.log(`❌ Call rejected by ${recipientId}`);
                        // Отправляем уведомление инициатору
                        await this.sendActualCallNotification(callerId, {
                            callId,
                            callerId,
                            recipientId,
                            status,
                            step,
                            createdAt,
                            offerSdp,
                            answerSdp
                        });
                        // Удаляем звонок через 5 секунд
                        setTimeout(() => {
                            this.deleteActualCall(callId);
                        }, 5000);
                        break;
                        
                    case "end_call":
                        console.log(`📞 Call ended by ${callerId}`);
                        // Отправляем уведомление получателю
                        await this.sendActualCallNotification(recipientId, {
                            callId,
                            callerId,
                            recipientId,
                            status,
                            step,
                            createdAt,
                            offerSdp,
                            answerSdp
                        });
                        // Удаляем звонок
                        this.deleteActualCall(callId);
                        break;
                }

                res.status(201).json({
                    success: true,
                    callId: callId,
                    message: "ActualCall processed successfully",
                });
            } catch (error) {
                console.error("Error processing ActualCall:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // Получение информации о звонке
        this.app.get("/api/calls/:callId", (req, res) => {
            try {
                const { callId } = req.params;
                const call = this.activeCalls.get(callId);

                if (!call) {
                    return res.status(404).json({
                        success: false,
                        error: "Call not found",
                    });
                }

                res.json({
                    success: true,
                    call: call,
                });
            } catch (error) {
                console.error("Error getting call:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // Обновление статуса звонка
        this.app.put("/api/calls/:callId", async (req, res) => {
            try {
                const { callId } = req.params;
                const { status, callerId, recipientId } = req.body;

                console.log(`🔄 Updating call ${callId} status to: ${status}`);

                let call = this.activeCalls.get(callId);
                if (!call) {
                    return res.status(404).json({
                        success: false,
                        error: "Call not found",
                    });
                }

                // Обновляем статус
                call.status = status;
                call.updatedAt = Date.now();

                this.activeCalls.set(callId, call);

                // 🔥 WEBPUSH: Отправляем push уведомление
                const targetUserId =
                    status === "accepted" || status === "rejected"
                        ? callerId
                        : recipientId;

                const notificationSent = await this.sendPushNotification(
                    targetUserId,
                    {
                        title: getCallStatusTitle(status),
                        body: getCallStatusBody(status, call),
                        type: "call_status_update",
                        call: call,
                    },
                );

                if (!notificationSent) {
                    this.sendPushNotificationViaWebSocket(targetUserId, {
                        type: "call_status_update",
                        call: call,
                    });
                }

                // Если звонок отклонен или завершен, очищаем его через некоторое время
                if (status === "rejected" || status === "ended") {
                    setTimeout(() => {
                        this.activeCalls.delete(callId);
                        console.log(`🧹 Cleared call: ${callId}`);
                    }, 30000);
                }

                res.json({
                    success: true,
                    message: `Call ${callId} updated to ${status}`,
                });
            } catch (error) {
                console.error("Error updating call:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // Удаление звонка
        this.app.delete("/api/calls/:callId", (req, res) => {
            try {
                const { callId } = req.params;

                if (this.activeCalls.delete(callId)) {
                    console.log(`🗑️ Call deleted: ${callId}`);
                    res.json({
                        success: true,
                        message: "Call deleted successfully",
                    });
                } else {
                    res.status(404).json({
                        success: false,
                        error: "Call not found",
                    });
                }
            } catch (error) {
                console.error("Error deleting call:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // Получение всех активных звонков для пользователя
        this.app.get("/api/users/:userId/calls", (req, res) => {
            try {
                const { userId } = req.params; // 🔥 было deviceId
                const userCalls = [];

                for (let [callId, call] of this.activeCalls) {
                    if (
                        call.callerId === userId ||
                        call.recipientId === userId
                    ) {
                        userCalls.push(call);
                    }
                }

                res.json({
                    success: true,
                    calls: userCalls,
                });
            } catch (error) {
                console.error("Error getting user calls:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 КРИТИЧЕСКИ ВАЖНО: Получение ожидающих уведомлений для ConnectionService
        this.app.get("/api/notifications/pending", async (req, res) => {
            try {
                const { userId } = req.query; // 🔥 было deviceId

                if (!userId) {
                    return res.status(400).json({
                        success: false,
                        error: "User ID is required",
                    });
                }

                console.log(
                    `📬 Getting pending notifications for user: ${userId}`,
                );

                // Ищем активные звонки для пользователя
                const userNotifications = [];

                // 🔥 НОВОЕ: Проверяем ActualCall из файла
                try {
                    const fs = require('fs').promises;
                    const path = require('path');
                    const filePath = path.join(__dirname, 'actuall_call.json');
                    
                    const fileContent = await fs.readFile(filePath, 'utf8');
                    const actualCalls = JSON.parse(fileContent);
                    
                    console.log(`📁 Found ${Object.keys(actualCalls).length} ActualCalls in file`);
                    
                    for (const [callId, actualCall] of Object.entries(actualCalls)) {
                        console.log(`📞 Checking ActualCall ${callId}: recipientId=${actualCall.recipientId}, callerId=${actualCall.callerId}, step=${actualCall.step}, userId=${userId}`);
                        
                        if (actualCall.recipientId === userId && actualCall.step === "request_call") {
                            console.log(`✅ Found incoming call for user ${userId}: ${callId}`);
                            userNotifications.push({
                                type: "actual_call_update",
                                data: {
                                    actualCall: actualCall
                                }
                            });
                        } else if (actualCall.callerId === userId && 
                                  (actualCall.step === "accept_call" || actualCall.step === "reject_call" || actualCall.step === "end_call")) {
                            console.log(`✅ Found call response for user ${userId}: ${callId}, step=${actualCall.step}`);
                            userNotifications.push({
                                type: "actual_call_update", 
                                data: {
                                    actualCall: actualCall
                                }
                            });
                        } else if (actualCall.recipientId === userId && actualCall.step === "accept_call") {
                            console.log(`✅ Found accept_call notification for user ${userId}: ${callId}`);
                            userNotifications.push({
                                type: "actual_call_update", 
                                data: {
                                    actualCall: actualCall
                                }
                            });
                        }
                    }
                } catch (fileError) {
                    console.log('📁 No actuall_call.json file found or error reading it:', fileError.message);
                }

                // Старая логика для обратной совместимости
                for (let [callId, call] of this.activeCalls) {
                    if (
                        call.recipientId === userId &&
                        call.status === "offer"
                    ) {
                        userNotifications.push({
                            type: "incoming_call",
                            data: {
                                callId: call.callId,
                                callerId: call.callerId,
                                recipientId: call.recipientId,
                                status: call.status,
                                step: "offer",
                                createdAt: call.createdAt,
                                offerSdp: call.offerSdp,
                                answerSdp: call.answerSdp,
                            },
                        });
                    } else if (
                        (call.callerId === userId ||
                            call.recipientId === userId) &&
                        (call.status === "accepted" ||
                            call.status === "rejected" ||
                            call.status === "ended")
                    ) {
                        userNotifications.push({
                            type: "call_status_update",
                            data: {
                                callId: call.callId,
                                callerId: call.callerId,
                                recipientId: call.recipientId,
                                status: call.status,
                                step: call.status,
                                createdAt: call.createdAt,
                                offerSdp: call.offerSdp,
                                answerSdp: call.answerSdp,
                            },
                        });
                    }
                }

                console.log(
                    `📬 Found ${userNotifications.length} pending notifications for user: ${userId}`,
                );

                res.json({
                    success: true,
                    notifications: userNotifications,
                    count: userNotifications.length,
                });
            } catch (error) {
                console.error("❌ Error getting pending notifications:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 КРИТИЧЕСКИ ВАЖНО: Подтверждение получения уведомления
        this.app.post("/api/notifications/acknowledge", (req, res) => {
            try {
                const { userId } = req.body; // 🔥 было deviceId

                if (!userId) {
                    return res.status(400).json({
                        success: false,
                        error: "User ID is required",
                    });
                }

                console.log(`✅ Notification acknowledged by user: ${userId}`);

                res.json({
                    success: true,
                    message: "Notification acknowledged",
                });
            } catch (error) {
                console.error("❌ Error acknowledging notification:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });

        // 🔥 Получение статуса сервера
        this.app.get("/status", (req, res) => {
            try {
                res.json({
                    success: true,
                    connectedUsers: Array.from(this.connectedUserIds),
                    totalUsers: this.connectedUserIds.size,
                    connectedSockets: Array.from(this.connectedUsers.keys()),
                    pushSubscriptions: Array.from(
                        this.pushSubscriptions.keys(),
                    ),
                    activeCalls: Array.from(this.activeCalls.keys()),
                    timestamp: Date.now(),
                });
            } catch (error) {
                console.error("Error getting status:", error);
                res.status(500).json({
                    success: false,
                    error: "Internal server error",
                });
            }
        });
    }
    
    setupSocketHandlers() {
        this.io.on("connection", (socket) => {
            console.log(`🔌 New socket connection: ${socket.id}`);

            // Регистрация пользователя
            socket.on("register", (data) => {
                const { userId, callId } = data; // 🔥 было deviceId

                console.log(
                    `👤 User registered: ${userId} for call: ${callId}`,
                );

                this.connectedUsers.set(userId, {
                    socketId: socket.id,
                    userId: userId,
                    callId: callId,
                });

                // Привязываем socket.id к userId
                socket.userId = userId;
                socket.callId = callId;

                socket.join(callId);
                console.log(`✅ User ${userId} joined room: ${callId}`);
            });

            // Обработка WebRTC сигналов
            socket.on("webrtc_signal", (data) => {
                const { type, toUserId, callId, sdp, candidate } = data; // 🔥 было toDeviceId

                console.log(
                    `📨 WebRTC signal: ${type} from ${socket.userId} to ${toUserId}`,
                );

                const targetUser = this.connectedUsers.get(toUserId);
                if (targetUser) {
                    // Отправляем сигнал целевому пользователю
                    socket.to(targetUser.socketId).emit(`webrtc_${type}`, {
                        sdp: sdp,
                        candidate: candidate,
                        callId: callId,
                        fromUserId: socket.userId,
                    });

                    console.log(`✅ WebRTC signal forwarded to: ${toUserId}`);
                } else {
                    console.log(`❌ Target user not connected: ${toUserId}`);

                    // Отправляем ошибку обратно
                    socket.emit("webrtc_error", {
                        error: "User not connected",
                        toUserId: toUserId,
                    });
                }
            });

            // 🔥 НОВЫЕ ОБРАБОТЧИКИ ДЛЯ WebRTC СИГНАЛИНГА
            socket.on("webrtc_offer", (data) => {
                const { callId, toUserId, sdp } = data;
                console.log(`📤 Forwarding WebRTC offer from ${socket.userId} to ${toUserId}`);
                
                const targetUser = this.connectedUsers.get(toUserId);
                if (targetUser) {
                    socket.to(targetUser.socketId).emit("webrtc_offer", {
                        callId: callId,
                        sdp: sdp,
                        fromUserId: socket.userId
                    });
                    console.log(`✅ WebRTC offer forwarded to: ${toUserId}`);
                } else {
                    console.log(`❌ Target user not connected for offer: ${toUserId}`);
                }
            });

            socket.on("webrtc_answer", (data) => {
                const { callId, toUserId, sdp } = data;
                console.log(`📤 Forwarding WebRTC answer from ${socket.userId} to ${toUserId}`);
                
                const targetUser = this.connectedUsers.get(toUserId);
                if (targetUser) {
                    socket.to(targetUser.socketId).emit("webrtc_answer", {
                        callId: callId,
                        sdp: sdp,
                        fromUserId: socket.userId
                    });
                    console.log(`✅ WebRTC answer forwarded to: ${toUserId}`);
                } else {
                    console.log(`❌ Target user not connected for answer: ${toUserId}`);
                }
            });

            socket.on("webrtc_ice_candidate", (data) => {
                const { callId, toUserId, candidate } = data;
                console.log(`🧊 Forwarding ICE candidate from ${socket.userId} to ${toUserId}`);
                
                const targetUser = this.connectedUsers.get(toUserId);
                if (targetUser) {
                    socket.to(targetUser.socketId).emit("webrtc_ice_candidate", {
                        callId: callId,
                        candidate: candidate,
                        fromUserId: socket.userId
                    });
                    console.log(`✅ ICE candidate forwarded to: ${toUserId}`);
                } else {
                    console.log(`❌ Target user not connected for ICE candidate: ${toUserId}`);
                }
            });

            // Отслеживание отключения
            socket.on("disconnect", () => {
                console.log(`🔌 Socket disconnected: ${socket.id}`);

                if (socket.userId) {
                    this.connectedUsers.delete(socket.userId);
                    // 🔥 ВАЖНО: Удаляем пользователя из списка подключенных
                    this.connectedUserIds.delete(socket.userId);
                    console.log(`👤 User disconnected: ${socket.userId}`);

                    // 🔥 ОТПРАВЛЯЕМ ОБНОВЛЕННЫЙ СПИСОК ВСЕМ ПОДКЛЮЧЕННЫМ ПОЛЬЗОВАТЕЛЯМ
                    this.broadcastUserListUpdate();

                    // Уведомляем других участников звонка
                    if (socket.callId) {
                        socket.to(socket.callId).emit("user_disconnected", {
                            userId: socket.userId,
                            callId: socket.callId,
                        });
                    }
                }
            });

            // Обработка ошибок
            socket.on("error", (error) => {
                console.error(`❌ Socket error for ${socket.userId}:`, error);
            });
        });
    }

    // 🔥 WEBPUSH: Метод отправки push уведомления
    async sendPushNotification(userId, notificationData) {
        const subscription = this.pushSubscriptions.get(userId);

        if (!subscription) {
            console.log(`⚠️ No push subscription found for user: ${userId}`);
            return false;
        }

        try {
            // Создаем payload для WebPush
            const payload = JSON.stringify({
                title: notificationData.title,
                body: notificationData.body,
                icon: "/icon-192x192.png",
                badge: "/badge-72x72.png",
                vibrate: [100, 50, 100],
                timestamp: Date.now(),
                data: {
                    type: notificationData.type,
                    // Если есть данные ActualCall, передаем их как плоские поля
                    ...(notificationData.actualCall ? {
                        callId: notificationData.actualCall.callId,
                        callerId: notificationData.actualCall.callerId,
                        recipientId: notificationData.actualCall.recipientId,
                        status: notificationData.actualCall.status,
                        step: notificationData.actualCall.step,
                        createdAt: notificationData.actualCall.createdAt.toString(),
                        offerSdp: notificationData.actualCall.offerSdp || "",
                        answerSdp: notificationData.actualCall.answerSdp || "",
                    } : {
                        callId: notificationData.call?.callId || "",
                        callerId: notificationData.call?.callerId || "",
                    }),
                    url: "/calls",
                },
                actions: notificationData.actions || [],
            });

            console.log(`📤 Sending WebPush to ${userId}:`, {
                endpoint: subscription.endpoint?.substring(0, 50) + "...",
                payloadSize: payload.length,
                type: notificationData.type
            });

            await webpush.sendNotification(subscription, payload);
            console.log(
                `✅ Push notification sent to: ${userId} - ${notificationData.type}`,
            );
            return true;
        } catch (error) {
            console.error(
                `❌ Error sending push notification to ${userId}:`,
                error.message,
            );
            console.error(`❌ Error details:`, {
                statusCode: error.statusCode,
                statusMessage: error.statusMessage,
                headers: error.headers,
                body: error.body
            });

            // Если подписка устарела или невалидна, удаляем её
            if (error.statusCode === 410 || error.statusCode === 404 || error.statusCode === 400) {
                this.pushSubscriptions.delete(userId);
                console.log(
                    `🗑️ Invalid/expired push subscription removed for: ${userId} (status: ${error.statusCode})`,
                );
            } else if (error.statusCode === 413) {
                console.log(`⚠️ Payload too large for user: ${userId}, trying to reduce payload size`);
                // Попробуем отправить упрощенное уведомление
                try {
                    const simplePayload = JSON.stringify({
                        title: notificationData.title,
                        body: notificationData.body,
                        data: {
                            type: notificationData.type,
                            userId: userId
                        }
                    });
                    await webpush.sendNotification(subscription, simplePayload);
                    console.log(`✅ Simple push notification sent to: ${userId}`);
                    return true;
                } catch (simpleError) {
                    console.error(`❌ Simple push also failed for ${userId}:`, simpleError.message);
                }
            } else if (error.statusCode === 429) {
                console.log(`⚠️ Rate limited for user: ${userId}, will retry later`);
                // Не удаляем подписку при rate limiting
            } else {
                console.log(`⚠️ Unknown error for user: ${userId}, status: ${error.statusCode}`);
            }

            return false;
        }
    }

    // 🔥 WEBPUSH: Специальный метод для уведомлений о звонках
    async sendCallNotification(recipientId, callData) {
        return await this.sendPushNotification(recipientId, {
            title: "Входящий звонок 📞",
            body: `Вам звонит ${callData.callerId}`,
            type: "incoming_call",
            call: callData,
            actions: [
                {
                    action: "accept",
                    title: "📞 Принять",
                    icon: "/accept-icon.png",
                },
                {
                    action: "reject",
                    title: "❌ Отклонить",
                    icon: "/reject-icon.png",
                },
            ],
        });
    }

    // 🔥 WEBPUSH: Специальный метод для уведомлений о списке устройств
    async sendDeviceListNotification(userId, deviceList, deviceDetails, totalDevices) {
        return await this.sendPushNotification(userId, {
            title: "📱 Список устройств обновлен",
            body: `Подключено устройств: ${totalDevices}`,
            type: "device_list_updated",
            data: {
                deviceList: deviceList,
                deviceDetails: deviceDetails,
                totalDevices: totalDevices,
                timestamp: Date.now(),
            },
            actions: [
                {
                    action: "refresh_devices",
                    title: "🔄 Обновить список",
                    icon: "/refresh-icon.png",
                },
            ],
        });
    }

    // 🔥 WEBPUSH: Fallback метод через WebSocket
    sendPushNotificationViaWebSocket(userId, notification) {
        const userSocket = this.connectedUsers.get(userId);
        if (userSocket) {
            this.io
                .to(userSocket.socketId)
                .emit("push_notification", notification);
            console.log(`✅ WebSocket notification sent to: ${userId}`);
            return true;
        } else {
            console.log(`⚠️ User ${userId} not connected via WebSocket`);
            return false;
        }
    }

    // 🔥 МЕТОД ДЛЯ РАССЫЛКИ ОБНОВЛЕННОГО СПИСКА ПОЛЬЗОВАТЕЛЕЙ
    broadcastUserListUpdate() {
        const userList = Array.from(this.connectedUserIds);

        console.log(
            `📢 Broadcasting user list update to ${this.connectedUserIds.size} users`,
        );

        // Рассылаем обновленный список всем подключенным пользователям через WebSocket
        this.io.emit("user_list_update", {
            type: "user_list_updated",
            userList: userList,
            totalCount: this.connectedUserIds.size,
            timestamp: Date.now(),
        });

        // 🔥 WEBPUSH: Также отправляем через push уведомления
        for (const userId of this.connectedUserIds) {
            this.sendPushNotification(userId, {
                title: "Список пользователей обновлен",
                body: `Подключено пользователей: ${this.connectedUserIds.size}`,
                type: "user_list_updated",
                data: {
                    userList: userList,
                    totalUsers: this.connectedUserIds.size,
                    timestamp: Date.now(),
                },
            }).catch((error) => {
                console.log(
                    `⚠️ Could not send user list update to ${userId}:`,
                    error.message,
                );
            });
        }
    }

    // 🔥 НОВЫЙ МЕТОД: Рассылка обновленного списка устройств
    broadcastDeviceListUpdate() {
        const deviceList = Array.from(this.connectedDevices.keys());
        const deviceDetails = Array.from(this.connectedDevices.values());

        console.log(
            `📢 Broadcasting device list update to ${this.connectedUserIds.size} users`,
        );

        // Рассылаем обновленный список всем подключенным пользователям через WebSocket
        this.io.emit("device_list_update", {
            type: "device_list_updated",
            deviceList: deviceList,
            deviceDetails: deviceDetails,
            totalCount: this.connectedDevices.size,
            timestamp: Date.now(),
        });

            // 🔥 WEBPUSH: Отправляем через push уведомления всем онлайн пользователям
            for (const userId of this.connectedUserIds) {
                this.sendDeviceListNotification(
                    userId, 
                    deviceList, 
                    deviceDetails, 
                    this.connectedDevices.size
                ).then((success) => {
                    if (!success) {
                        console.log(`⚠️ WebPush failed for ${userId}, will use polling as fallback`);
                    }
                }).catch((error) => {
                    console.log(
                        `⚠️ Could not send device list update to ${userId}:`,
                        error.message,
                    );
                });
            }
    }

    // 🔥 НОВЫЕ МЕТОДЫ ДЛЯ РАБОТЫ С ActualCall
    
    /**
     * Сохранение ActualCall в файл actuall_call.json
     */
    async saveActualCallToFile(actualCall) {
        try {
            const fs = require('fs').promises;
            const path = require('path');
            
            const filePath = path.join(__dirname, 'actuall_call.json');
            
            // Читаем существующий файл или создаем пустой объект
            let data = {};
            try {
                const fileContent = await fs.readFile(filePath, 'utf8');
                data = JSON.parse(fileContent);
            } catch (error) {
                console.log('📁 Creating new actuall_call.json file');
            }
            
            // Сохраняем ActualCall
            data[actualCall.callId] = actualCall;
            
            // Записываем обратно в файл
            await fs.writeFile(filePath, JSON.stringify(data, null, 2));
            console.log(`💾 ActualCall saved to file: ${actualCall.callId}`);
        } catch (error) {
            console.error('❌ Error saving ActualCall to file:', error);
        }
    }
    
    /**
     * Отправка ActualCall через push уведомление
     */
    async sendActualCallNotification(userId, actualCall) {
        try {
            console.log(`📤 Sending ActualCall notification to ${userId}: ${actualCall.step}`);
            console.log(`📤 ActualCall data:`, JSON.stringify(actualCall, null, 2));
            
            // Используем общий метод sendPushNotification с данными ActualCall
            const success = await this.sendPushNotification(userId, {
                title: this.getCallStatusTitle(actualCall.step),
                body: this.getCallStatusBody(actualCall.step, actualCall),
                type: "actual_call_update",
                actualCall: actualCall, // Передаем весь объект ActualCall
                actions: actualCall.step === "request_call" ? [
                    {
                        action: "accept",
                        title: "📞 Принять",
                        icon: "/accept-icon.png",
                    },
                    {
                        action: "reject",
                        title: "❌ Отклонить",
                        icon: "/reject-icon.png",
                    },
                ] : [],
            });
            
            if (success) {
                console.log(`✅ ActualCall notification sent to ${userId}`);
            } else {
                console.log(`⚠️ ActualCall notification failed for ${userId}, saving to file`);
            }
            
            // Всегда сохраняем в файл для polling как backup
            await this.saveActualCallToFile(actualCall);
            
            return success;
        } catch (error) {
            console.error(`❌ Error sending ActualCall notification to ${userId}:`, error);
            
            // Если push не удался, сохраняем в файл для polling
            await this.saveActualCallToFile(actualCall);
            
            return false;
        }
    }
    
    /**
     * Удаление ActualCall
     */
    deleteActualCall(callId) {
        try {
            const fs = require('fs').promises;
            const path = require('path');
            
            const filePath = path.join(__dirname, 'actuall_call.json');
            
            // Читаем файл
            fs.readFile(filePath, 'utf8').then(fileContent => {
                const data = JSON.parse(fileContent);
                delete data[callId];
                
                // Записываем обратно
                return fs.writeFile(filePath, JSON.stringify(data, null, 2));
            }).then(() => {
                console.log(`🗑️ ActualCall deleted: ${callId}`);
            }).catch(error => {
                console.error('❌ Error deleting ActualCall:', error);
            });
        } catch (error) {
            console.error('❌ Error deleting ActualCall:', error);
        }
    }

    // 🔥 МЕТОД ДЛЯ ПОЛУЧЕНИЯ ТЕКУЩЕГО СОСТОЯНИЯ СЕРВЕРА
    getServerStatus() {
        return {
            connectedUsers: Array.from(this.connectedUserIds),
            totalUsers: this.connectedUserIds.size,
            connectedDevices: Array.from(this.connectedDevices.keys()),
            totalDevices: this.connectedDevices.size,
            connectedSockets: Array.from(this.connectedUsers.keys()),
            pushSubscriptions: Array.from(this.pushSubscriptions.keys()),
            activeCalls: Array.from(this.activeCalls.keys()),
            timestamp: Date.now(),
        };
    }

    // Запуск сервера
    start(port = 3000) {
        this.server.listen(port, () => {
            console.log(`🚀 Call server running on port ${port}`);
            console.log(
                `📞 API endpoints available at http://localhost:${port}/api`,
            );
            console.log(`🔌 WebSocket available at ws://localhost:${port}`);
            console.log(`👤 User management endpoints:`);
            console.log(`   POST /api/users/register - Register user`);
            console.log(`   POST /api/users/unregister - Unregister user`);
            console.log(`   GET /api/users - Get user list`);
            console.log(`🔔 WebPush endpoints:`);
            console.log(`   GET /api/push/vapid-key - Get VAPID public key`);
            console.log(
                `   POST /api/push/subscribe - Subscribe to push notifications`,
            );
            console.log(
                `   POST /api/push/unsubscribe - Unsubscribe from push notifications`,
            );
            console.log(`   POST /api/push/test - Send test notification`);
        });
    }

    // Остановка сервера
    stop() {
        this.server.close(() => {
            console.log("🛑 Call server stopped");
        });
    }
    
    /**
     * Вспомогательные функции для текста уведомлений
     */
    getCallStatusTitle(step) {
        const titles = {
            request_call: "📞 Входящий звонок",
            accept_call: "✅ Звонок принят",
            reject_call: "❌ Звонок отклонен",
            end_call: "📞 Звонок завершен",
            offer: "📞 Входящий звонок",
            accepted: "Звонок принят ✅",
            rejected: "Звонок отклонен ❌",
            ended: "Звонок завершен 📞",
            missed: "Пропущенный звонок ⏰",
        };
        return titles[step] || "Обновление статуса звонка";
    }

    getCallStatusBody(step, call) {
        const bodies = {
            request_call: `Вам звонит ${call.callerId}`,
            accept_call: `Пользователь принял ваш звонок`,
            reject_call: `Пользователь отклонил ваш звонок`,
            end_call: `Разговор завершен`,
            offer: `Вам звонит ${call.callerId}`,
            accepted: `Пользователь принял ваш звонок`,
            rejected: `Пользователь отклонил ваш звонок`,
            ended: `Разговор завершен`,
            missed: `Вы пропустили звонок от ${call.callerId}`,
        };
        return bodies[step] || `Статус звонка изменен на: ${step}`;
    }
}

// Запуск сервера
if (require.main === module) {
    const server = new CallServer();
    server.start(process.env.PORT || 3000);
}

module.exports = CallServer;
