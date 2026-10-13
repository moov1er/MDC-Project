package com.mouserelay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * MVP: не автозапускаем сервер после reboot — пользователю нужно видеть свежий PIN.
 * Но держим ресивер: показываем тихое уведомление «нажмите, чтобы запустить MouseRelay».
 * (Автозапуск FGS сразу после BOOT с type CONNECTED_DEVICE разрешён только special-use;
 * для MVP сознательно просим ручной запуск — проще и легально.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // TODO polish: notification "tap to start". MVP — no-op.
    }
}
