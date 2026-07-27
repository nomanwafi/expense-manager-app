package com.nomananik.expensemanager

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nomananik.expensemanager.databinding.ActivityMaintenanceBinding
import kotlinx.coroutines.launch

/**
 * Shown when remote_config.json has "maintenance_mode": true.
 * Auto re-checks every 30s, and lets the user manually re-check too, so the app moves on to
 * the site automatically as soon as an admin flips maintenance_mode back to false.
 */
class MaintenanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMaintenanceBinding
    private val handler = Handler(Looper.getMainLooper())
    private val autoRetryRunnable = Runnable { checkAgain() }

    companion object {
        const val EXTRA_TITLE = "extra_maintenance_title"
        const val EXTRA_MESSAGE = "extra_maintenance_message"
        private const val AUTO_RETRY_INTERVAL_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMaintenanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.maintenance_default_title)
        val message = intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.maintenance_default_message)

        binding.txtTitle.text = title
        binding.txtMessage.text = message
        binding.btnCheckAgain.setOnClickListener {
            handler.removeCallbacks(autoRetryRunnable)
            checkAgain()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(autoRetryRunnable, AUTO_RETRY_INTERVAL_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(autoRetryRunnable)
        super.onPause()
    }

    private fun checkAgain() {
        binding.btnCheckAgain.isEnabled = false
        binding.progressChecking.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            val config = RemoteConfig.fetch(this@MaintenanceActivity)
            binding.progressChecking.visibility = android.view.View.GONE
            binding.btnCheckAgain.isEnabled = true

            if (!config.maintenanceMode) {
                startActivity(
                    Intent(this@MaintenanceActivity, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_WEBSITE_URL, config.websiteUrl)
                        if (config.announcementEnabled && config.announcementMessage.isNotBlank()) {
                            putExtra(MainActivity.EXTRA_ANNOUNCEMENT_TITLE, config.announcementTitle)
                            putExtra(MainActivity.EXTRA_ANNOUNCEMENT_MESSAGE, config.announcementMessage)
                        }
                    }
                )
                finish()
            } else {
                // Still under maintenance — update the message in case the admin changed it,
                // and schedule the next automatic check.
                binding.txtTitle.text = config.maintenanceTitle.ifBlank {
                    getString(R.string.maintenance_default_title)
                }
                binding.txtMessage.text = config.maintenanceMessage.ifBlank {
                    getString(R.string.maintenance_default_message)
                }
                handler.postDelayed(autoRetryRunnable, AUTO_RETRY_INTERVAL_MS)
            }
        }
    }
}
