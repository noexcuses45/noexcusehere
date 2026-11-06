package com.noexcusehere.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.noexcusehere.screenrecorder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: RecordingsAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAutoStart = false

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startRecordingService(result.resultCode, data)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (prefs.recordMic && grants[Manifest.permission.RECORD_AUDIO] == false) {
            prefs.recordMic = false
            binding.micSwitch.isChecked = false
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_SHORT).show()
        }
        launchCapturePrompt()
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus(ScreenRecordService.state.value ?: ScreenRecordService.Companion.State.IDLE)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.micSwitch.isChecked = prefs.recordMic
        binding.countdownSwitch.isChecked = prefs.countdown
        binding.qualityGroup.check(
            when (prefs.quality) {
                Quality.HIGH -> R.id.qualityHigh
                Quality.MEDIUM -> R.id.qualityMedium
                Quality.LOW -> R.id.qualityLow
            }
        )
        binding.micSwitch.setOnCheckedChangeListener { _, checked -> prefs.recordMic = checked }
        binding.countdownSwitch.setOnCheckedChangeListener { _, checked -> prefs.countdown = checked }
        binding.qualityGroup.setOnCheckedChangeListener { _, id ->
            prefs.quality = when (id) {
                R.id.qualityMedium -> Quality.MEDIUM
                R.id.qualityLow -> Quality.LOW
                else -> Quality.HIGH
            }
        }

        binding.recordButton.setOnClickListener {
            if (ScreenRecordService.isActive) stopRecording() else requestPermissionsThenStart()
        }

        adapter = RecordingsAdapter(onClick = ::playRecording, onLongClick = ::showRecordingMenu)
        binding.recordingsList.layoutManager = LinearLayoutManager(this)
        binding.recordingsList.adapter = adapter

        ScreenRecordService.state.observe(this) { state ->
            updateStatus(state)
            if (state == ScreenRecordService.Companion.State.IDLE) loadRecordings()
        }

        pendingAutoStart = intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false) && !ScreenRecordService.isActive) {
            requestPermissionsThenStart()
        }
    }

    override fun onResume() {
        super.onResume()
        loadRecordings()
        handler.post(ticker)
        if (pendingAutoStart) {
            pendingAutoStart = false
            if (!ScreenRecordService.isActive) requestPermissionsThenStart()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---- recording control ----

    private fun requestPermissionsThenStart() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (prefs.recordMic &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.RECORD_AUDIO

        if (needed.isEmpty()) launchCapturePrompt() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun launchCapturePrompt() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenRecordService::class.java)
            .setAction(ScreenRecordService.ACTION_START)
            .putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            .putExtra(ScreenRecordService.EXTRA_WITH_AUDIO, prefs.recordMic)
            .putExtra(ScreenRecordService.EXTRA_QUALITY, prefs.quality.name)
            .putExtra(ScreenRecordService.EXTRA_COUNTDOWN_SECONDS, if (prefs.countdown) 3 else 0)
        ContextCompat.startForegroundService(this, intent)
        // Get out of the way so the user can record whatever they want.
        moveTaskToBack(true)
    }

    private fun stopRecording() {
        startService(ScreenRecordService.stopIntent(this))
    }

    private fun updateStatus(state: ScreenRecordService.Companion.State) {
        when (state) {
            ScreenRecordService.Companion.State.IDLE -> {
                binding.statusText.text = getString(R.string.status_idle)
                binding.recordButton.text = getString(R.string.start_recording)
                binding.recordButton.setIconResource(R.drawable.ic_record)
            }
            ScreenRecordService.Companion.State.COUNTDOWN -> {
                binding.statusText.text = getString(R.string.status_countdown, 3)
                binding.recordButton.text = getString(R.string.stop_recording)
                binding.recordButton.setIconResource(R.drawable.ic_stop)
            }
            ScreenRecordService.Companion.State.RECORDING -> {
                val elapsed = (System.currentTimeMillis() - ScreenRecordService.startedAtMillis) / 1000
                binding.statusText.text = getString(R.string.status_recording, DateUtils.formatElapsedTime(elapsed))
                binding.recordButton.text = getString(R.string.stop_recording)
                binding.recordButton.setIconResource(R.drawable.ic_stop)
            }
        }
        val settingsEnabled = state == ScreenRecordService.Companion.State.IDLE
        binding.micSwitch.isEnabled = settingsEnabled
        binding.countdownSwitch.isEnabled = settingsEnabled
        for (i in 0 until binding.qualityGroup.childCount) binding.qualityGroup.getChildAt(i).isEnabled = settingsEnabled
    }

    // ---- recordings list ----

    private fun loadRecordings() {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0"
        val args = arrayOf("${ScreenRecordService.RELATIVE_DIR}%")
        val list = mutableListOf<Recording>()
        runCatching {
            contentResolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    list += Recording(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "recording.mp4",
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                        dateMillis = c.getLong(dateCol) * 1000,
                    )
                }
            }
        }
        adapter.submit(list)
        binding.emptyText.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun playRecording(r: Recording) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(r.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRecordingMenu(r: Recording) {
        val options = arrayOf(getString(R.string.play), getString(R.string.share), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(r.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playRecording(r)
                    1 -> shareRecording(r)
                    2 -> confirmDelete(r)
                }
            }
            .show()
    }

    private fun shareRecording(r: Recording) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, r.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun confirmDelete(r: Recording) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { contentResolver.delete(r.uri, null, null) }
                loadRecordings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
