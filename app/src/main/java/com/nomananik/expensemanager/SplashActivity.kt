package com.nomananik.expensemanager

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.nomananik.expensemanager.databinding.ActivitySplashBinding
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    companion object {
        // The splash is always shown for at least this long, for a smooth feel.
        private const val SPLASH_MIN_DELAY_MS = 2000L
        // Absolute ceiling: if the remote config check hasn't finished by then, proceed anyway
        // with safe defaults so a slow/broken network never traps the user on the splash screen.
        private const val SPLASH_MAX_WAIT_MS = 4000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // System splash screen (Android 12+) shown instantly while cold-start happens.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        binding.imgLogo.startAnimation(fadeIn)
        binding.txtAppName.startAnimation(fadeIn)

        lifecycleScope.launch {
            // Start the remote-config fetch immediately, in parallel with the splash delay,
            // so we don't pay for both durations back-to-back.
            val configDeferred = async { RemoteConfig.fetch(this@SplashActivity) }
            delay(SPLASH_MIN_DELAY_MS)

            val config = try {
                withTimeout(SPLASH_MAX_WAIT_MS) { configDeferred.await() }
            } catch (e: TimeoutCancellationException) {
                RemoteConfig.default(this@SplashActivity)
            }

            proceed(config)
        }
    }

    private fun proceed(config: RemoteConfig) {
        val versionCode = BuildConfig.VERSION_CODE

        val intent = when {
            config.maintenanceMode -> {
                Intent(this, MaintenanceActivity::class.java).apply {
                    putExtra(MaintenanceActivity.EXTRA_TITLE, config.maintenanceTitle)
                    putExtra(MaintenanceActivity.EXTRA_MESSAGE, config.maintenanceMessage)
                }
            }
            config.forceUpdate && versionCode < config.minSupportedVersionCode -> {
                Intent(this, UpdateRequiredActivity::class.java).apply {
                    putExtra(UpdateRequiredActivity.EXTRA_MESSAGE, config.updateMessage)
                    putExtra(UpdateRequiredActivity.EXTRA_UPDATE_URL, config.updateUrl)
                }
            }
            else -> {
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_WEBSITE_URL, config.websiteUrl)
                    if (config.announcementEnabled && config.announcementMessage.isNotBlank()) {
                        putExtra(MainActivity.EXTRA_ANNOUNCEMENT_TITLE, config.announcementTitle)
                        putExtra(MainActivity.EXTRA_ANNOUNCEMENT_MESSAGE, config.announcementMessage)
                    }
                    if (!config.forceUpdate && config.latestVersionCode > versionCode) {
                        putExtra(MainActivity.EXTRA_SOFT_UPDATE_MESSAGE, config.updateMessage)
                        putExtra(MainActivity.EXTRA_UPDATE_URL, config.updateUrl)
                    }
                }
            }
        }

        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
