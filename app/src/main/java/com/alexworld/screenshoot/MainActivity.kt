package com.alexworld.screenshoot

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.alexworld.screenshoot.common.Utils
import com.alexworld.screenshoot.databinding.ActivityMainBinding
import com.alexworld.screenshoot.manager.ScreenshotService
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var seconds = 0

    // Is the stopwatch running?
    private var running = false

    private var wasRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {

            seconds = savedInstanceState
                .getInt("seconds")
            running = savedInstanceState
                .getBoolean("running")
            wasRunning = savedInstanceState
                .getBoolean("wasRunning")
        }

        binding.btnStart.setOnClickListener {
            startTimer()
            running = true
            startProjection()
            binding.btnStart.visibility = View.GONE
            binding.btnEndWork.visibility = View.VISIBLE
        }

        binding.btnEndWork.setOnClickListener {
            running = false
            binding.btnEndWork.visibility = View.GONE
            binding.btnStart.visibility = View.VISIBLE
            stopProjection()
        }
    }

    override fun onSaveInstanceState(
        savedInstanceState: Bundle
    ) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState
            .putInt("seconds", seconds)
        savedInstanceState
            .putBoolean("running", running)
        savedInstanceState
            .putBoolean("wasRunning", wasRunning)
    }

//    private fun endWork() {
//        WorkManager.getInstance().cancelAllWork()
//    }

//    private fun startWork() {
//        val periodicWorkRequest = PeriodicWorkRequestBuilder<ScreenShotManager>(2, TimeUnit.MINUTES).build()
//        WorkManager.getInstance().enqueue(periodicWorkRequest)
//    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101) {
            if (resultCode == RESULT_OK) {
                startService(getStartIntent(this, resultCode, data))
            }
        }
    }


    private fun startProjection() {
        val mProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), 101)
    }

    private fun stopProjection() {
        getStopIntent(this)
    }

    private fun getStartIntent(context: Context?, resultCode: Int, data: Intent?): Intent {
        val intent = Intent(context, ScreenshotService ::class.java)
        intent.putExtra(Utils.ACTION, Utils.START)
        intent.putExtra(Utils.RESULT_CODE, resultCode)
        intent.putExtra(Utils.DATA, data)
        return intent
    }

    private fun getStopIntent(context: Context?): Intent {
        val intent = Intent(context, ScreenshotService::class.java)
        intent.putExtra(Utils.ACTION, Utils.STOP)
        return intent
    }

    private fun startTimer(){
        val handler = Handler()
        handler.post(object : Runnable {
            override fun run() {
                val hours: Int = seconds / 3600
                val minutes: Int = seconds % 3600 / 60
                val secs: Int = seconds % 60

                // Format the seconds into hours, minutes,
                // and seconds.
                val time: String = java.lang.String
                    .format(
                        Locale.getDefault(),
                        "%02d:%02d:%02d", hours, minutes, secs
                    )

                // Set the text view text.
                binding.tvTime.text = time

                // If running is true, increment the
                // seconds variable.
                if (running) {
                    seconds++
                }

                // Post the code again
                // with a delay of 1 second.
                handler.postDelayed(this, 1000)
            }
        })
    }

    override fun onPause() {
        super.onPause()
        wasRunning = running
        running = false
    }

    override fun onResume() {
        super.onResume()
        if (wasRunning) {
            running = true
        }
    }
}