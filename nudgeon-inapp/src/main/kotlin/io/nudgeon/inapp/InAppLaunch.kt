package io.nudgeon.inapp

/** Completion of the launch-ad attempt, not the user's later interaction. */
enum class InAppLaunchResult { SHOWN, NO_CAMPAIGN, TIMED_OUT, BLOCKED, CANCELLED, FAILED, ALREADY_HANDLED }

internal class InAppLaunchRegistry {
    private val handled = mutableSetOf<String>()
    @Synchronized fun claim(key: String): Boolean = handled.add(key)
    companion object { val process = InAppLaunchRegistry() }
}

/** Monotonic preparation budget; never persists across process restarts. */
internal class InAppLaunchWindow(timeoutSeconds: Double, nowMillis: Long) {
    val deadline = nowMillis + ((if (timeoutSeconds.isFinite()) timeoutSeconds.coerceIn(1.0,10.0) else 3.0) * 1000).toLong()
    var result: InAppLaunchResult? = null
        private set
    fun canPresent(nowMillis: Long) = result == null && nowMillis < deadline
    fun complete(value: InAppLaunchResult, nowMillis: Long): InAppLaunchResult? {
        if (result != null) return null
        val effective = if (nowMillis >= deadline && value != InAppLaunchResult.CANCELLED) InAppLaunchResult.TIMED_OUT else value
        result = effective
        return effective
    }
}
