package io.nudgeon.sample

import android.app.Application
import android.util.Log
import io.nudgeon.sdk.NudgeOn
import io.nudgeon.sdk.NudgeOnConfig

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.NUDGEON_SDK_KEY == "pk_sample_replace_me") {
            Log.w(TAG, "placeholder publishable key 사용 중 — 실제 수집은 -PnudgeonSdkKey=pk_... 로 설정하세요")
        }

        NudgeOn.initialize(
            this,
            NudgeOnConfig(
                sdkKey = BuildConfig.NUDGEON_SDK_KEY,
                apiHost = BuildConfig.NUDGEON_API_HOST,
                autoTrackSessions = true,
                // The sample fetches FirebaseMessaging.getToken explicitly and hands it to NudgeOn.
                autoRegisterPushToken = false,
            ),
        )
    }

    private companion object {
        const val TAG = "NudgeOnSample"
    }
}
