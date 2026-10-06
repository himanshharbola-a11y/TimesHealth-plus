package timeshealth.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Placeholder screen. The real UI is built once the team confirms the toolkit
 * (Jetpack Compose or XML Views); everything below the UI is toolkit-agnostic.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "TimesHealth+ — API: ${BuildConfig.API_BASE_URL}" })
    }
}
