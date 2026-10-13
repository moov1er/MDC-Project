package com.mouserelay.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MouseRelay protocol v1 — JSON Lines over TCP.
 * Дискриминатор — поле "type". Неизвестные поля/типы игнорируются (forward compat).
 */
@Serializable
data class Msg(
    val type: String,
    // auth / auth_fail
    val token: String? = null,
    val proto: Int? = null,
    val name: String? = null,
    val reason: String? = null,
    // hello (phone -> pc)
    val width: Int? = null,
    val height: Int? = null,
    val density: Int? = null,
    val rotation: Int? = null,
    // hello_ack (pc -> phone)
    @SerialName("screen_width") val screenWidth: Int? = null,
    @SerialName("screen_height") val screenHeight: Int? = null,
    // enter
    val edge: String? = null,
    @SerialName("y_ratio") val yRatio: Double? = null,
    // move / scroll
    val dx: Int? = null,
    val dy: Int? = null,
    // button
    val button: String? = null,
    val action: String? = null,
    // state
    val overlay: Boolean? = null,
    // error
    val code: String? = null,
    val message: String? = null,
    // ping/pong
    val timestamp: Long? = null,
) {
    companion object Types {
        const val AUTH = "auth"
        const val AUTH_OK = "auth_ok"
        const val AUTH_FAIL = "auth_fail"
        const val HELLO = "hello"
        const val HELLO_ACK = "hello_ack"
        const val ENTER = "enter"
        const val MOVE = "move"
        const val BUTTON = "button"
        const val SCROLL = "scroll"
        const val EXIT = "exit"
        const val STATE = "state"
        const val ERROR = "error"
        const val PING = "ping"
        const val PONG = "pong"

        const val PROTO_VERSION = 1

        const val BTN_LEFT = "left"
        const val BTN_RIGHT = "right"
        const val ACT_DOWN = "down"
        const val ACT_UP = "up"

        const val EXIT_LEFT_EDGE = "left_edge"
        const val EXIT_USER = "user"
        const val EXIT_TIMEOUT = "timeout"

        const val ERR_GESTURE = "gesture_not_allowed"
        const val ERR_REPLACED = "replaced"
    }
}

object JsonCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(msg: Msg): String = json.encodeToString(Msg.serializer(), msg)

    /** @return распарсенное сообщение или null, если строка не валидный Msg. */
    fun decode(line: String): Msg? =
        try {
            json.decodeFromString(Msg.serializer(), line)
        } catch (_: Exception) {
            null
        }
}
