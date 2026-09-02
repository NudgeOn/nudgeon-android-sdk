package io.nudgeon.sdk

import android.content.SharedPreferences

/**
 * 푸시 토큰 라이프사이클·구독 상태 (PRD-01A 2.4). iOS PushManager와 대칭.
 * OS 권한 요청(POST_NOTIFICATIONS)은 Activity 컨텍스트가 필요하므로 NudgeOn 공개 API에서 처리하고,
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

    /** 마지막으로 서버에 등록한 토큰 (foreground 권한 재동기화 진입점 — R-08). */
    val cachedToken: String? get() = prefs.getString(TOKEN, null)

    fun subscriptionState(osPermission: String) =
        SubscriptionState(serviceOptIn, osPermission, tokenRegistered)

    /**
     * 토큰 대사 판정 — 마지막 등록분과 토큰·유저·OS 권한 중 하나라도 달라졌는지 (S-5/R-08 핵심 결정).
     * os_permission을 대사에 포함해 토큰 값이 불변이어도(설정에서 알림 on/off) 권한 변경이 서버에 반영된다.
     */
    fun needsRegistration(token: String, externalId: String?, osPermission: String): Boolean =
        token != prefs.getString(TOKEN, null) ||
            externalId != prefs.getString(TOKEN_EXT, null) ||
            osPermission != prefs.getString(TOKEN_PERM, null)

    /** 등록 성공 영속 — 이후 동일 토큰/유저/권한은 no-op. */
    fun markRegistered(token: String, externalId: String?, osPermission: String) = prefs.edit().apply {
        putString(TOKEN, token)
        putString(TOKEN_PERM, osPermission)
        if (externalId == null) remove(TOKEN_EXT) else putString(TOKEN_EXT, externalId)
    }.apply()

    /** reset 시 로컬 등록 캐시 무효화 — 다음 토큰을 새 유저로 재등록 (S-4). */
    fun clearTokenCache() = prefs.edit().remove(TOKEN).remove(TOKEN_EXT).remove(TOKEN_PERM).apply()

    /** 새 토큰/유저/권한 조합이 마지막 등록분과 다를 때만 서버 등록. 성공 시 영속. */
    fun registerToken(token: String, externalId: String?, anonId: String, osPermission: String) {
        if (!needsRegistration(token, externalId, osPermission)) {
            NudgeOnLog.info("push 토큰·권한 변화 없음 — 등록 생략")
            return
        }
        network.registerToken(token, externalId, anonId, osPermission) { ok ->
            if (ok) { markRegistered(token, externalId, osPermission); NudgeOnLog.info("push 토큰 등록 완료 (권한=$osPermission)") }
            else NudgeOnLog.warn("push 토큰 등록 실패 — 다음 기회 재시도")
        }
    }

    private companion object {
        const val OPT_IN = "nudgeon.push.service_opt_in"
        const val TOKEN = "nudgeon.push.last_token"
        const val TOKEN_EXT = "nudgeon.push.last_token_external"
        const val TOKEN_PERM = "nudgeon.push.last_token_permission"
    }
}
