package com.nomananik.expensemanager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nomananik.expensemanager.databinding.ActivityUpdateRequiredBinding

/**
 * Shown when remote_config.json has "force_update": true and this app's versionCode is below
 * "min_supported_version_code". Blocks access to the app until the user updates — there is no
 * skip button. The back button simply exits the app (Android's default for a task-root activity).
 */
class UpdateRequiredActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateRequiredBinding

    companion object {
        const val EXTRA_MESSAGE = "extra_update_message"
        const val EXTRA_UPDATE_URL = "extra_update_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateRequiredBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val message = intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.update_required_default_message)
        val updateUrl = intent.getStringExtra(EXTRA_UPDATE_URL)?.takeIf { it.isNotBlank() }

        binding.txtMessage.text = message
        binding.btnUpdate.setOnClickListener {
            if (updateUrl != null) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                } catch (_: Exception) {
                    // No app can handle it (e.g. no Play Store installed); ignore silently.
                }
            }
        }
    }
}
