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
     * reset() — 로그아웃. external 제거 + 새 anon_id. device_id는 유지(설치 단위).
     * 이전 유저에게 다음 유저 푸시가 가는 사고 방지 (S-4).
     */
    fun reset() {
        prefs.edit().remove(EXTERNAL).putString(ANON, UUID.randomUUID().toString()).apply()
    }

    private companion object {
        const val ANON = "nudgeon.anon_id"
        const val DEVICE = "nudgeon.device_id"
        const val EXTERNAL = "nudgeon.external_id"
    }
}
