package com.alexworld.screenshoot.manager

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.OrientationEventListener
import android.view.WindowManager
import com.alexworld.screenshoot.common.Utils
import com.alexworld.screenshoot.common.NotificationUtils
import com.google.firebase.FirebaseApp
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

class ScreenshotService: Service() {
    private var IMAGES_PRODUCED = 0
    private var mMediaProjection: MediaProjection? = null
    private var mStoreDir: String? = null
    private var mImageReader: ImageReader? = null
    private var mHandler: Handler? = null
    private var mDisplay: Display? = null
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mDensity = 0
    private var mWidth = 0
    private var mHeight = 0
    private var mRotation = 0
    private var mOrientationChangeCallback: OrientationChangeCallback? = null

    private fun isStartCommand(intent: Intent): Boolean {
        return (intent.hasExtra(Utils.RESULT_CODE) && intent.hasExtra(Utils.DATA)
                && intent.hasExtra(Utils.ACTION) && Objects.equals(intent.getStringExtra(Utils.ACTION),
            Utils.START
        ))
    }

    private fun isStopCommand(intent: Intent): Boolean {
        return intent.hasExtra(Utils.ACTION) && Objects.equals(intent.getStringExtra(Utils.ACTION),
            Utils.STOP
        )
    }

    private fun getVirtualDisplayFlags(): Int {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    }

    lateinit var bitmap: Bitmap
    private inner class ImageAvailableListener : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader?) {
            var fos: FileOutputStream? = null

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    mImageReader!!.acquireLatestImage().use { image ->
                        if (image != null) {
                            val planes: Array<Image.Plane> = image.planes
                            val buffer: ByteBuffer = planes[0].buffer
                            val pixelStride: Int = planes[0].pixelStride
                            val rowStride: Int = planes[0].rowStride
                            val rowPadding: Int = rowStride - pixelStride * mWidth

                            bitmap = Bitmap.createBitmap(
                                mWidth + rowPadding / pixelStride,
                                mHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)

                            val fName = "myscreen_$IMAGES_PRODUCED.png"
                            uploadData(bitmap, fName)
                            fos = FileOutputStream("$mStoreDir/$fName")
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                            IMAGES_PRODUCED++
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    if (fos != null) {
                        try {
                            fos!!.close()
                        } catch (ioe: IOException) {
                            ioe.printStackTrace()
                        }
                    }
                    bitmap.recycle()
                }
            }, 60000)
        }
    }

    private fun uploadData(bitmap: Bitmap, fName: String) {
        FirebaseApp.initializeApp(this)
        val storageRef = Firebase.storage.reference
        val mountainsRef = storageRef.child("screenshot")
        val mountainImagesRef = mountainsRef.child(fName)

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val data = baos.toByteArray()

        var uploadTask = mountainImagesRef.putBytes(data)
        uploadTask.addOnFailureListener {

        }.addOnSuccessListener { taskSnapshot ->

        }
    }

    private inner class OrientationChangeCallback internal constructor(context: Context?) :
        OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            val rotation: Int = mDisplay!!.rotation
            if (rotation != mRotation) {
                mRotation = rotation
                try {
                    if (mVirtualDisplay != null) mVirtualDisplay!!.release()
                    if (mImageReader != null) mImageReader!!.setOnImageAvailableListener(null, null)

                    createVirtualDisplay()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private inner class MediaProjectionStopCallback : MediaProjection.Callback() {
        override fun onStop() {
            mHandler!!.post(Runnable {
                if (mVirtualDisplay != null) mVirtualDisplay!!.release()
                if (mImageReader != null) mImageReader!!.setOnImageAvailableListener(null, null)
                if (mOrientationChangeCallback != null) mOrientationChangeCallback!!.disable()
                mMediaProjection!!.unregisterCallback(this@MediaProjectionStopCallback)
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        val externalFilesDir: File? = getExternalFilesDir(null)
        if (externalFilesDir != null) {
            mStoreDir = externalFilesDir.absolutePath + "/screenshots/"
            val storeDirectory = File(mStoreDir)
            if (!storeDirectory.exists()) {
                val success: Boolean = storeDirectory.mkdirs()
                if (!success) {
                    stopSelf()
                }
            }
        } else {
            stopSelf()
        }

        object : Thread() {
            override fun run() {
                Looper.prepare()
                mHandler = Handler()
                Looper.loop()
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (isStartCommand(intent)) {
            val (first, second) = NotificationUtils.getNotification(this)
            startForeground(first, second)
            val resultCode = intent.getIntExtra(Utils.RESULT_CODE, Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>(Utils.DATA)
            startProjection(resultCode, data)
        } else if (isStopCommand(intent)) {
            stopProjection()
            stopSelf()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent?) {
        val mpManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (mMediaProjection == null) {
            mMediaProjection = mpManager.getMediaProjection(resultCode, data!!)
            if (mMediaProjection != null) {
                mDensity = Resources.getSystem().displayMetrics.densityDpi
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                mDisplay = windowManager.defaultDisplay
                createVirtualDisplay()
                mOrientationChangeCallback = OrientationChangeCallback(this)
                if (mOrientationChangeCallback!!.canDetectOrientation()) {
                    mOrientationChangeCallback!!.enable()
                }
                mMediaProjection!!.registerCallback(MediaProjectionStopCallback(), mHandler)
            }
        }
    }

    private fun stopProjection() {
        if (mHandler != null) {
            mHandler!!.post(Runnable {
                if (mMediaProjection != null) {
                    mMediaProjection!!.stop()
                }
            })
        }
    }

    @SuppressLint("WrongConstant")
    private fun createVirtualDisplay() {
        mWidth = Resources.getSystem().displayMetrics.widthPixels
        mHeight = Resources.getSystem().displayMetrics.heightPixels

        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
        mVirtualDisplay = mMediaProjection!!.createVirtualDisplay(
            Utils.SCREENCAP_NAME, mWidth, mHeight,
            mDensity, getVirtualDisplayFlags(), mImageReader!!.surface, null, mHandler
        )
        mImageReader!!.setOnImageAvailableListener(ImageAvailableListener(), mHandler)
    }

}