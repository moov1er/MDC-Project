package com.mouserelay.app.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Простая шина состояния между слоями приложения:
 *  - ServerService / TcpServer пишут сетевые события;
 *  - RelayAccessibilityService подписывается и исполняет жесты/оверлей;
 *  - MainActivity показывает статус.
 * Односторонние каналы — StateFlow; команды (одноразовые события) — тоже через flow,
 * consumer обязан сбросить на None после обработки. MVP: polling-free, collectLatest.
 */
sealed interface ServerStatus {
    data object Stopped : ServerStatus
    data object Listening : ServerStatus
    data object WaitingAuth : ServerStatus
    data object Ready : ServerStatus          // auth_ok + hello отправлен
    data class Error(val message: String) : ServerStatus
}

sealed interface PhoneCommand {
    data object None : PhoneCommand
    data class Enter(val yRatio: Double) : PhoneCommand
    data class Move(val dx: Int, val dy: Int) : PhoneCommand
    data class Button(val button: String, val action: String) : PhoneCommand
    data class Scroll(val dx: Int, val dy: Int) : PhoneCommand
    data object Exit : PhoneCommand           // со стороны ПК (например hotkey) — скрыть оверлей
}

object SessionState {
    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)
    val status: StateFlow<ServerStatus> = _status

    private val _info = MutableStateFlow(ConnectionInfo(null, null, 0))
    val info: StateFlow<ConnectionInfo> = _info

    /** Команды из сети для AccessibilityService. Последняя выигрывает; consumer читает и сбрасывает на None. */
    private val _command = MutableStateFlow<PhoneCommand>(PhoneCommand.None)
    val command: StateFlow<PhoneCommand> = _command

    /** Оверлей активен (подтверждение state{overlay}). */
    private val _overlayActive = MutableStateFlow(false)
    val overlayActive: StateFlow<Boolean> = _overlayActive

    fun setStatus(s: ServerStatus) { _status.value = s }
    fun setInfo(i: ConnectionInfo) { _info.value = i }
    fun sendCommand(c: PhoneCommand) { _command.value = c }
    fun clearCommand() { _command.value = PhoneCommand.None }
    fun setOverlayActive(a: Boolean) { _overlayActive.value = a }
}

data class ConnectionInfo(
    val ip: String?,
    val token: String?,
    val port: Int,
)
