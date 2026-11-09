package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.ImageView
import coil.ImageLoader
import coil.request.ImageRequest
import coil.target.Target
import com.hiennv.flutter_callkit_incoming.widgets.CircleTransform
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

object ImageLoaderProvider {
    // One thread for the backdrop blur, so a burst of rings never piles up work.
    private val blurExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "callkit-backdrop-blur").apply { isDaemon = true }
    }

    @SuppressLint("StaticFieldLeak")
    private var instance: ImageLoader? = null

    fun get(context: Context, headers: HashMap<String, Any?>?): ImageLoader {
        if (instance == null) {
            val imageLoader = ImageLoader.Builder(context)
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .addNetworkInterceptor { chain ->
                    val newRequestBuilder: okhttp3.Request.Builder = chain.request().newBuilder()
                    if (headers != null) {
                        for ((key, value) in headers) {
                            newRequestBuilder.addHeader(key, value.toString())
                        }
                    }
                    chain.proceed(newRequestBuilder.build())
                }
                .build()
            instance = imageLoader.okHttpClient(client).build()
        }
        return instance!!
    }

    fun loadImage(context: Context, url: String, headers: HashMap<String, Any?>?, target: Target?) {
        val imageLoader = get(context, headers)
        val requestBuilder = ImageRequest.Builder(context)
        headers?.forEach { (key, value) ->
            value?.toString()?.let {
                requestBuilder.addHeader(key, it)
            }
        }
        requestBuilder.data(url)
        requestBuilder.allowHardware(false)
        requestBuilder.transformations(CircleTransform())
        requestBuilder.target(target)

        imageLoader.enqueue(requestBuilder.build())
    }

    fun loadImage(context: Context, url: String, headers: HashMap<String, Any?>?, placeholder: Int, target: ImageView, enableBlur: Boolean = false) {
        val imageLoader = get(context, headers)
        val requestBuilder = ImageRequest.Builder(context)
        headers?.forEach { (key, value) ->
            value?.toString()?.let {
                requestBuilder.addHeader(key, it)
            }
        }
        requestBuilder.data(url)
        requestBuilder.allowHardware(false)
        requestBuilder.placeholder(placeholder)
        requestBuilder.error(placeholder)
        requestBuilder.target(
            onStart = { target.setImageDrawable(it) },
            onError = { target.setImageDrawable(it) },
            onSuccess = {
                val bitmap = (it as? BitmapDrawable)?.bitmap
                if (enableBlur && bitmap != null) {
                    blurInto(context, bitmap, target)
                } else {
                    target.setImageDrawable(it)
                }
            }
        )
        imageLoader.enqueue(requestBuilder.build())
    }

    /**
     * Blurs off the main thread and sets the result when it is back. The view and
     * the context are held weakly and checked again on the main thread, so a blur
     * that finishes after the screen is gone is dropped instead of touching a dead
     * view. A failed blur (out of memory, a recycled bitmap) leaves the ground
     * color showing, which is a valid screen.
     */
    private fun blurInto(context: Context, bitmap: Bitmap, target: ImageView) {
        val metrics = context.resources.displayMetrics
        val targetRef = WeakReference(target)
        val contextRef = WeakReference(context)
        try {
            blurExecutor.execute {
                val blurred = try {
                    BackdropBlur.create(bitmap, metrics.widthPixels, metrics.heightPixels, metrics.density)
                } catch (error: Throwable) {
                    Log.w("ImageLoaderProvider", "Backdrop blur failed", error)
                    null
                }
                if (blurred == null) return@execute
                Handler(Looper.getMainLooper()).post {
                    val view = targetRef.get()
                    val owner = contextRef.get()
                    val gone = view == null || owner == null || (owner is Activity && isGone(owner))
                    if (gone) {
                        blurred.recycle()
                    } else {
                        view!!.setImageBitmap(blurred)
                    }
                }
            }
        } catch (error: Throwable) {
            Log.w("ImageLoaderProvider", "Backdrop blur not scheduled", error)
        }
    }

    private fun isGone(activity: Activity): Boolean =
        activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)
}


open class SafeTarget(
    private val notificationId: Int,
    private val onLoaded: (Bitmap) -> Unit
) : Target {

    var isCancelled = false

    override fun onSuccess(result: Drawable) {
        super.onSuccess(result)
        Log.d("onSuccess", "-")
        if (!isCancelled) {
            onLoaded((result as BitmapDrawable).bitmap)
        }
    }

    override fun onStart(placeholder: Drawable?) {
        super.onStart(placeholder)
        Log.d("onStart", "-")
    }

    override fun onError(error: Drawable?) {
        super.onError(error)
        Log.d("onError", "-")
    }


}