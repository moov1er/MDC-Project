package com.mouserelay.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mouserelay.app.a11y.RelayAccessibilityService
import com.mouserelay.app.databinding.ActivityMainBinding
import com.mouserelay.app.state.ServerStatus
import com.mouserelay.app.state.SessionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Экран телефона: IP, порт, PIN-токен, статус, кнопки.
 * Пользователь вручную: включает Accessibility, запускает сервер, вводит токен на ПК.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener {
            if (ServerService.running) ServerService.stop(this) else ensureNotifAndStart()
        }
        binding.btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnBattery.setOnClickListener {
            // запрос исключения battery optimization (разрешение не требуется — системный диалог)
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        }

        lifecycleScope.launch {
            SessionState.status.collectLatest { render(it) }
        }
        lifecycleScope.launch {
            SessionState.info.collectLatest { info ->
                binding.textAddr.text = "Адрес: ${info.ip ?: "нет Wi-Fi?:"}:${info.port}"
                binding.textToken.text = "PIN: ${info.token ?: "—"}"
            }
        }
        lifecycleScope.launch {
            SessionState.overlayActive.collectLatest { active ->
                binding.textOverlay.text = if (active) "Курсор на телефоне ●" else "Курсор на телефоне ○"
            }
        }
    }

    private fun ensureNotifAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
            // стартуем всё равно: FGS без разрешения просто не покажет уведомление, но будет живым
        }
        ServerService.start(this)
    }

    private fun render(s: ServerStatus) {
        binding.textStatus.text = when (s) {
            ServerStatus.Stopped -> "Сервер остановлен"
            ServerStatus.Listening -> "Слушаю… (ожидание ПК)"
            ServerStatus.WaitingAuth -> "ПК подключился, жду auth"
            ServerStatus.Ready -> "ПК авторизован ✔"
            is ServerStatus.Error -> "Ошибка: ${s.message}"
        }
        val a11yOn = accessibilityEnabled()
        binding.textA11y.text = if (a11yOn)
            "Accessibility: включён ✔"
        else
            "Accessibility: ВЫКЛЮЧЕН — включите в настройках (кнопка ниже)"
        binding.btnToggle.text = if (ServerService.running) "Stop server" else "Start server"
    }

    override fun onResume() {
        super.onResume()
        // перерисовать статус accessibility при возврате из настроек
        render(SessionState.status.value)
    }

    /** Проверка «видит ли система наш a11y-сервис» — для надёжного индикатора. */
    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val mine = "$packageName/${RelayAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(mine, ignoreCase = true) ||
                it.endsWith("${packageName}.a11y.RelayAccessibilityService") }
    }
}
