package io.onda.sdk

import android.content.SharedPreferences

/**
 * 푸시 토큰 라이프사이클·구독 상태 (PRD-01A 2.4). iOS PushManager와 대칭.
 * OS 권한 요청(POST_NOTIFICATIONS)은 Activity 컨텍스트가 필요하므로 Onda 공개 API에서 처리하고,
 * 여기서는 토큰 대사·구독 영속(순수 로직, 단위 테스트 대상)만 담당.
 */
internal class PushManager(
    private val network: Network,
    private val prefs: SharedPreferences,
) {
    /** 서비스 수준 수신 동의 — 미설정 시 기본 true. */
    val serviceOptIn: Boolean
        get() = if (prefs.contains(OPT_IN)) prefs.getBoolean(OPT_IN, true) else true

    fun setServiceOptIn(optedIn: Boolean) = prefs.edit().putBoolean(OPT_IN, optedIn).apply()

    val tokenRegistered: Boolean get() = prefs.getString(TOKEN, null) != null

    fun subscriptionState(osPermission: String) =
        SubscriptionState(serviceOptIn, osPermission, tokenRegistered)

    /** 토큰 대사 판정 — 마지막 등록분과 토큰 또는 유저가 달라졌는지 (S-5 핵심 결정). */
    fun needsRegistration(token: String, externalId: String?): Boolean =
        token != prefs.getString(TOKEN, null) || externalId != prefs.getString(TOKEN_EXT, null)

    /** 등록 성공 영속 — 이후 동일 토큰/유저는 no-op. */
    fun markRegistered(token: String, externalId: String?) = prefs.edit().apply {
        putString(TOKEN, token)
        if (externalId == null) remove(TOKEN_EXT) else putString(TOKEN_EXT, externalId)
    }.apply()

    /** reset 시 로컬 등록 캐시 무효화 — 다음 토큰을 새 유저로 재등록 (S-4). */
    fun clearTokenCache() = prefs.edit().remove(TOKEN).remove(TOKEN_EXT).apply()

    /** 새 토큰/유저 조합이 마지막 등록분과 다를 때만 서버 등록. 성공 시 영속. */
    fun registerToken(token: String, externalId: String?, anonId: String, osPermission: String) {
        if (!needsRegistration(token, externalId)) {
            OndaLog.info("push 토큰 변화 없음 — 등록 생략")
            return
        }
        network.registerToken(token, externalId, anonId, osPermission) { ok ->
            if (ok) { markRegistered(token, externalId); OndaLog.info("push 토큰 등록 완료") }
            else OndaLog.warn("push 토큰 등록 실패 — 다음 기회 재시도")
        }
    }

    private companion object {
        const val OPT_IN = "onda.push.service_opt_in"
        const val TOKEN = "onda.push.last_token"
        const val TOKEN_EXT = "onda.push.last_token_external"
    }
}
