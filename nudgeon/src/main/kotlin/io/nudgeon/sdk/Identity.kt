package io.nudgeon.sdk

import android.content.SharedPreferences
import java.util.UUID

/**
 * anon_id / external_id / device_id 영속화 (PRD-01 3.1). iOS Identity와 대칭.
 * 코어가 유일한 상태 보유자 — SharedPreferences에 영속.
 */
internal class Identity(private val prefs: SharedPreferences) {

    /** 최초 실행 시 anon_id(UUID) 발급·영속. 이후 동일 값. */
    val anonId: String
        get() = prefs.getString(ANON, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(ANON, it).apply()
        }

    /** 디바이스 식별자 — 설치 단위 불변(재설치 시 새로 발급). */
    val deviceId: String
        get() = prefs.getString(DEVICE, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(DEVICE, it).apply()
        }

    var externalId: String?
        get() = prefs.getString(EXTERNAL, null)
        set(v) = prefs.edit().apply { if (v == null) remove(EXTERNAL) else putString(EXTERNAL, v) }.apply()

    /**
     * 서버에 아직 반영되지 않은 identify (external_id, 당시 anon_id) — 앱 재시작 후에도 재시도한다.
     * IdentifySync가 읽고 쓴다.
     */
    val pendingIdentify: IdentifySync.Store = object : IdentifySync.Store {
        override var pending: Pair<String, String>?
            get() {
                val ext = prefs.getString(PENDING_EXT, null) ?: return null
                val anon = prefs.getString(PENDING_ANON, null) ?: return null
                return ext to anon
            }
            set(v) = prefs.edit().apply {
                if (v == null) { remove(PENDING_EXT); remove(PENDING_ANON) }
                else { putString(PENDING_EXT, v.first); putString(PENDING_ANON, v.second) }
            }.apply()
    }

    /** 최근 수신한 push message_id — 중복 수신 접기(SeenMessages). reset과 무관하게 유지한다. */
    val seenMessages: SeenMessages.Store = object : SeenMessages.Store {
        override var ids: List<String>
            get() = prefs.getString(SEEN, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
            set(v) = prefs.edit().putString(SEEN, v.joinToString("\n")).apply()
    }

    /**
     * reset() — 로그아웃. external 제거 + 새 anon_id. device_id는 유지(설치 단위).
     * 이전 유저에게 다음 유저 푸시가 가는 사고 방지 (S-4). 이전 유저의 미전송 identify도 버린다
     * (코어가 reset 직전에 마지막 1회 전송을 시도한다) — 새 anon이 그 유저에 묶이지 않는다.
     */
    fun reset() {
        prefs.edit().remove(EXTERNAL).remove(PENDING_EXT).remove(PENDING_ANON)
            .putString(ANON, UUID.randomUUID().toString()).apply()
    }

    private companion object {
        const val ANON = "nudgeon.anon_id"
        const val DEVICE = "nudgeon.device_id"
        const val EXTERNAL = "nudgeon.external_id"
        const val PENDING_EXT = "nudgeon.identify_pending.external_id"
        const val PENDING_ANON = "nudgeon.identify_pending.anon_id"
        const val SEEN = "nudgeon.push.seen_message_ids"
    }
}
