// Service Worker для WebPush уведомлений
const CACHE_NAME = 'mycontacts-sw-v1';

// Устанавливаем Service Worker
self.addEventListener('install', (event) => {
    console.log('📱 Service Worker installing...');
    self.skipWaiting();
});

// Активируем Service Worker
self.addEventListener('activate', (event) => {
    console.log('📱 Service Worker activating...');
    event.waitUntil(self.clients.claim());
});

// Обрабатываем push уведомления
self.addEventListener('push', (event) => {
    console.log('📱 Push event received:', event);
    
    if (event.data) {
        try {
            const data = event.data.json();
            console.log('📱 Push data:', data);
            
            const options = {
                body: data.body,
                icon: data.icon || '/icon-192x192.png',
                badge: data.badge || '/badge-72x72.png',
                vibrate: data.vibrate || [100, 50, 100],
                data: data.data,
                actions: data.actions || [],
                requireInteraction: true, // Не исчезает автоматически
                tag: data.data?.callId || 'call-notification', // Группируем по callId
            };
            
            event.waitUntil(
                self.registration.showNotification(data.title, options)
            );
        } catch (error) {
            console.error('❌ Error processing push data:', error);
            
            // Fallback уведомление
            event.waitUntil(
                self.registration.showNotification('MyContacts', {
                    body: 'Новое уведомление',
                    icon: '/icon-192x192.png',
                    data: { type: 'unknown' }
                })
            );
        }
    }
});

// Обрабатываем клики по уведомлениям
self.addEventListener('notificationclick', (event) => {
    console.log('📱 Notification clicked:', event);
    
    event.notification.close();
    
    const data = event.notification.data;
    const action = event.action;
    
    console.log('📱 Notification data:', data);
    console.log('📱 Action:', action);
    
    // Открываем приложение
    event.waitUntil(
        self.clients.matchAll({ type: 'window', includeUncontrolled: true })
            .then((clients) => {
                // Если приложение уже открыто, фокусируемся на нем
                for (const client of clients) {
                    if (client.url.includes('mycontacts') && 'focus' in client) {
                        client.focus();
                        
                        // Отправляем данные в приложение
                        client.postMessage({
                            type: 'notification_click',
                            action: action,
                            data: data
                        });
                        return;
                    }
                }
                
                // Если приложение не открыто, открываем его
                if (self.clients.openWindow) {
                    return self.clients.openWindow('/');
                }
            })
    );
});

// Обрабатываем сообщения от приложения
self.addEventListener('message', (event) => {
    console.log('📱 Service Worker received message:', event.data);
    
    if (event.data && event.data.type === 'SKIP_WAITING') {
        self.skipWaiting();
    }
});
