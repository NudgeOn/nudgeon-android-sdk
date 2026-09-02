package io.nudgeon.sdk

import java.util.UUID

/**
 * 푸시 이벤트 리스너 레지스트리 + 콜드스타트 버퍼 (PRD-01A 2.5, 4장). iOS EventBus와 대칭.
 *
 * 콜드 스타트(푸시 탭으로 앱 실행)에서 pushOpened가 리스너 등록보다 먼저 발생 →
 * 리스너 없을 때 최대 20건 버퍼링 후 첫 등록 시 재생 (브리지 계약 4장).
 * getInitialPushPayload와 리스너 버퍼 재생의 이중 경로를 이 클래스가 담당.
 *
 * @param deliver 핸들러 호출 디스패치(기본 즉시). 브리지/코어가 메인 스레드 전달을 주입.
 */
internal class EventBus(private val deliver: (() -> Unit) -> Unit = { it() }) {

    private val lock = Any()
    private val openedHandlers = LinkedHashMap<UUID, (PushPayload) -> Unit>()
    private val receivedHandlers = LinkedHashMap<UUID, (PushPayload) -> Unit>()
    private val openedBuffer = ArrayDeque<PushPayload>()
    private val receivedBuffer = ArrayDeque<PushPayload>()
    private var initialOpened: PushPayload? = null

    private val maxBuffer = 20

    fun onPushOpened(handler: (PushPayload) -> Unit): UUID = subscribe(openedHandlers, openedBuffer, handler)
    fun onPushReceived(handler: (PushPayload) -> Unit): UUID = subscribe(receivedHandlers, receivedBuffer, handler)

    fun off(token: UUID) = synchronized(lock) {
        openedHandlers.remove(token)
        receivedHandlers.remove(token)
        Unit
    }

    private fun subscribe(
        handlers: MutableMap<UUID, (PushPayload) -> Unit>,
        buffer: ArrayDeque<PushPayload>,
        handler: (PushPayload) -> Unit,
    ): UUID {
        val token = UUID.randomUUID()
        val replay: List<PushPayload>
        synchronized(lock) {
            handlers[token] = handler
            replay = buffer.toList()
            buffer.clear()
        }
        for (p in replay) deliver { handler(p) }
        return token
    }

    fun emitOpened(payload: PushPayload) {
        val handlers: List<(PushPayload) -> Unit>
        synchronized(lock) {
            initialOpened = payload
            handlers = openedHandlers.values.toList()
            if (handlers.isEmpty()) appendBuffered(openedBuffer, payload)
        }
        for (h in handlers) deliver { h(payload) }
    }

    fun emitReceived(payload: PushPayload) {
        val handlers: List<(PushPayload) -> Unit>
        synchronized(lock) {
            handlers = receivedHandlers.values.toList()
            if (handlers.isEmpty()) appendBuffered(receivedBuffer, payload)
        }
        for (h in handlers) deliver { h(payload) }
    }

    /** 콜드 스타트 진입 페이로드 (RN/Flutter getInitialPushPayload 대응). 없으면 null. */
    fun getInitialPushPayload(): PushPayload? = synchronized(lock) { initialOpened }

    /** 버퍼 상한 유지 — 초과 시 oldest drop. */
    private fun appendBuffered(buffer: ArrayDeque<PushPayload>, payload: PushPayload) {
        buffer.addLast(payload)
        while (buffer.size > maxBuffer) buffer.removeFirst()
    }
}
