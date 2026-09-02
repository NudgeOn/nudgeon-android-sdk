package io.onda.sample

import android.app.Application
import android.util.Log
import io.onda.sdk.Onda
import io.onda.sdk.OndaConfig

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.ONDA_SDK_KEY == "pk_sample_replace_me") {
            Log.w(TAG, "placeholder publishable key 사용 중 — 실제 수집은 -PondaSdkKey=pk_... 로 설정하세요")
        }

        Onda.initialize(
            this,
            OndaConfig(
                sdkKey = BuildConfig.ONDA_SDK_KEY,
                apiHost = BuildConfig.ONDA_API_HOST,
                autoTrackSessions = true,
                // The sample fetches FirebaseMessaging.getToken explicitly and hands it to Onda.
                autoRegisterPushToken = false,
            ),
        )
    }

    private companion object {
        const val TAG = "OndaSample"
    }
}
