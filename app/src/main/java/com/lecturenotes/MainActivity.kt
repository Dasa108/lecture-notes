package com.lecturenotes

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.lecturenotes.audio.Importer
import com.lecturenotes.ui.AppNav
import com.lecturenotes.ui.theme.LectureNotesTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Recording to open after an import started from a share intent. */
    private val openRecording = MutableStateFlow<Long?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            LectureNotesTheme {
                AppNav(openRecording)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        lifecycleScope.launch {
            runCatching {
                if (com.lecturenotes.docs.DocumentImporter.isDocument(this@MainActivity, uri)) {
                    com.lecturenotes.docs.DocumentImporter.importAsLecture(this@MainActivity, uri)
                } else {
                    Importer.import(this@MainActivity, uri)
                }
            }
                .onSuccess { openRecording.value = it }
                .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
        }
    }
}
