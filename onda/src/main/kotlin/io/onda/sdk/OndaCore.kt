package io.onda.sdk

import android.content.Context
import java.util.UUID

/**
 * 코어 오케스트레이터 (M1 골격). iOS OndaCore와 대칭 구조.
 * 구현: Identity(SharedPreferences 영속)·EventQueue(SQLite/파일)·Network(OkHttp/HttpURLConnection)
 * ·WorkManager 백그라운드 플러시 — 상세 구현은 M1~M2 진행.
 */
internal class OndaCore(
    private val context: Context,
    private val config: OndaConfig,
) {
    private val prefs = context.getSharedPreferences("onda", Context.MODE_PRIVATE)

    val anonId: String
        get() = prefs.getString("anon_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("anon_id", it).apply()
        }

    val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }

    var externalId: String?
        get() = prefs.getString("external_id", null)
        set(v) = prefs.edit().putString("external_id", v).apply()

    fun start() {
        OndaLog.info("Onda 초기화: host=${config.apiHost}")
        // TODO(M1): 영속 큐 로드, 플러시 워커 등록, 이전 세션 잔존분 전송
    }

    fun identify(externalId: String) {
        this.externalId = externalId
        // TODO(M1): /v1/identify 전송
    }

    fun reset() {
        prefs.edit().remove("external_id").putString("anon_id", UUID.randomUUID().toString()).apply()
        // TODO(M1): 잔존 큐 플러시 후 새 anon_id (device_id 유지)
    }

    fun setUserAttributes(attrs: Map<String, Any?>) {
        // TODO(M1): /v1/identify attributes 전송 (null = unset)
    }

    fun track(name: String, properties: Map<String, Any?>) {
        // TODO(M1): 오프라인 큐 enqueue + 배치 플러시
    }

    fun flush() {
        // TODO(M1): 큐 배치 전송
    }
}
