package app.simgate.sms

import app.simgate.data.Message
import app.simgate.net.RemoteClient
import app.simgate.net.TelegramBot
import app.simgate.net.WebhookDispatcher

/** Single event publishing point: webhooks + persistent SMS server connection. */
object Events {
    const val RECEIVED = "sms.received"
    const val SENT = "sms.sent"
    const val DELIVERED = "sms.delivered"
    const val FAILED = "sms.failed"
    const val USSD = "ussd.result"
    const val CALL_RINGING = "call.ringing"
    const val CALL_MISSED = "call.missed"
    const val CALL_ENDED = "call.ended"
    val ALL = listOf(RECEIVED, SENT, DELIVERED, FAILED, USSD, CALL_RINGING, CALL_MISSED, CALL_ENDED)

    fun emit(event: String, message: Message) {
        WebhookDispatcher.dispatch(event, message)
        RemoteClient.sendEvent(event, message)
        TelegramBot.onEvent(event, message)
    }
}
