//  Modification by Signify in this file are under the following license:
//
//  Copyright 2024, Signify Holding
//  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
//  The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package com.hiennv.flutter_callkit_incoming

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.DrawableCompat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hiennv.flutter_callkit_incoming.widgets.RippleRelativeLayout
import de.hdodenhof.circleimageview.CircleImageView
import kotlin.math.abs
import android.view.ViewGroup.MarginLayoutParams
import android.os.PowerManager
import android.text.TextUtils
import android.util.Log

class CallkitIncomingActivity : Activity() {

    companion object {

        private const val ACTION_ENDED_CALL_INCOMING =
            "com.hiennv.flutter_callkit_incoming.ACTION_ENDED_CALL_INCOMING"

        fun getIntent(context: Context, data: Bundle) =
            Intent(CallkitConstants.ACTION_CALL_INCOMING).apply {
                setClassName(context.packageName, "com.hiennv.flutter_callkit_incoming.CallkitIncomingActivity")
                action = "${context.packageName}.${CallkitConstants.ACTION_CALL_INCOMING}"
                putExtra(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA, data)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                `package` = context.packageName
            }

        fun getIntentEnded(context: Context, isAccepted: Boolean): Intent {
            val intent = Intent("${context.packageName}.${ACTION_ENDED_CALL_INCOMING}")
            intent.putExtra("ACCEPTED", isAccepted)
            intent.setPackage(context.packageName)
            intent.setClassName(
                context.packageName,
                "com.hiennv.flutter_callkit_incoming.CallkitIncomingActivity"
            )
            return intent
        }
    }

    inner class EndedCallkitIncomingBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isFinishing) return
            val isAccepted = intent.getBooleanExtra("ACCEPTED", false)
            // PANCAKE: nghe máy trên màn khoá thì màn này CHÍNH LÀ màn đang gọi,
            // không được đóng. `CallkitNotificationService` xử lý
            // ACTION_CALL_ACCEPT luôn gọi `clearIncomingNotification(data, true)`
            // → bắn ngược broadcast này với ACCEPTED=true, tức là không chặn thì
            // đúng 1 giây sau khi nghe máy màn gọi biến mất.
            //
            // ACCEPTED=false (decline / ended / timeout) vẫn đóng như cũ — đây là
            // đường bên kia cúp máy, phải dọn màn.
            if (isAccepted && isOngoing) return
            if (isAccepted) {
                finishDelayed()
            } else {
                finishTask()
            }
        }
    }

    private var endedCallkitIncomingBroadcastReceiver = EndedCallkitIncomingBroadcastReceiver()

    private lateinit var ivBackground: ImageView
    private lateinit var llBackgroundAnimation: RippleRelativeLayout

    private lateinit var tvNameCaller: TextView
    private lateinit var tvNumber: TextView

    // PANCAKE: đồng hồ đếm giây sau khi nghe máy.
    private lateinit var tvDuration: Chronometer

    /// Đã nghe máy và đang ở lại màn khoá (xem [showOngoing]).
    private var isOngoing = false
    private lateinit var ivLogo: ImageView
    private lateinit var ivAvatar: CircleImageView

    private lateinit var llAction: LinearLayout
    private lateinit var ivAcceptCall: ImageView
    private lateinit var tvAccept: TextView

    private lateinit var ivDeclineCall: ImageView
    private lateinit var tvDecline: TextView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = if (!Utils.isTablet(this@CallkitIncomingActivity)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        transparentStatusAndNavigation()
        setContentView(R.layout.activity_callkit_incoming)
        initView()
        incomingData(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                endedCallkitIncomingBroadcastReceiver,
                IntentFilter("${packageName}.${ACTION_ENDED_CALL_INCOMING}"),
                Context.RECEIVER_EXPORTED,
            )
        } else {
            registerReceiver(
                endedCallkitIncomingBroadcastReceiver,
                IntentFilter("${packageName}.${ACTION_ENDED_CALL_INCOMING}")
            )
        }
        FlutterCallkitIncomingPlugin.getInstance()?.getCallkitSoundPlayerManager()?.keepRingingOnFullScreen();
    }

    private fun wakeLockRequest(duration: Long) {

        val pm = applicationContext.getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Callkit:PowerManager"
        )
        wakeLock.acquire(duration)
    }

    private fun transparentStatusAndNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            setWindowFlag(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, true
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setWindowFlag(
                (WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION), false
            )
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
    }

    private fun setWindowFlag(bits: Int, on: Boolean) {
        val win: Window = window
        val winParams: WindowManager.LayoutParams = win.attributes
        if (on) {
            winParams.flags = winParams.flags or bits
        } else {
            winParams.flags = winParams.flags and bits.inv()
        }
        win.attributes = winParams
    }


    private fun incomingData(intent: Intent) {
        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)
        if (data == null) finish()

        val isShowFullLockedScreen =
            data?.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_SHOW_FULL_LOCKED_SCREEN, true)
        if (isShowFullLockedScreen == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }

        val textColor = data?.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_COLOR, "#ffffff")
        val isShowCallID = data?.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_SHOW_CALL_ID, false)
        tvNameCaller.text = data?.getString(CallkitConstants.EXTRA_CALLKIT_NAME_CALLER, "")
        tvNumber.text = data?.getString(CallkitConstants.EXTRA_CALLKIT_HANDLE, "")
        tvNumber.visibility = if (isShowCallID == true) View.VISIBLE else View.INVISIBLE

        try {
            tvNameCaller.setTextColor(Color.parseColor(textColor))
            tvNumber.setTextColor(Color.parseColor(textColor))
        } catch (error: Exception) {
        }

        val isShowLogo = data?.getBoolean(CallkitConstants.EXTRA_CALLKIT_IS_SHOW_LOGO, false)
        ivLogo.visibility = if (isShowLogo == true) View.VISIBLE else View.INVISIBLE
        var logoUrl = data?.getString(CallkitConstants.EXTRA_CALLKIT_LOGO_URL, "")
        if (!logoUrl.isNullOrEmpty()) {
            if (!logoUrl.startsWith("http://", true) && !logoUrl.startsWith("https://", true)) {
                logoUrl = String.format("file:///android_asset/flutter_assets/%s", logoUrl)
            }
            val headers =
                data?.getSerializable(CallkitConstants.EXTRA_CALLKIT_HEADERS) as HashMap<String, Any?>
            ImageLoaderProvider.loadImage(this@CallkitIncomingActivity, logoUrl, headers, R.drawable.transparent, ivLogo)
        }

        var avatarUrl = data?.getString(CallkitConstants.EXTRA_CALLKIT_AVATAR, "")
        if (!avatarUrl.isNullOrEmpty()) {
            ivAvatar.visibility = View.VISIBLE
            if (!avatarUrl.startsWith("http://", true) && !avatarUrl.startsWith("https://", true)) {
                avatarUrl = String.format("file:///android_asset/flutter_assets/%s", avatarUrl)
            }
            val headers =
                data?.getSerializable(CallkitConstants.EXTRA_CALLKIT_HEADERS) as HashMap<String, Any?>
            ImageLoaderProvider.loadImage(this@CallkitIncomingActivity, avatarUrl, headers, R.drawable.ic_default_avatar, ivAvatar)
        }

        val callType = data?.getInt(CallkitConstants.EXTRA_CALLKIT_TYPE, 0) ?: 0
        if (callType > 0) {
            ivAcceptCall.setImageResource(R.drawable.ic_video)
        }
        val duration = data?.getLong(CallkitConstants.EXTRA_CALLKIT_DURATION, 0L) ?: 0L
        wakeLockRequest(duration)

        finishTimeout(data, duration)

        val textAccept = data?.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_ACCEPT, "")
        tvAccept.text =
            if (TextUtils.isEmpty(textAccept)) getString(R.string.text_accept) else textAccept
        val textDecline = data?.getString(CallkitConstants.EXTRA_CALLKIT_TEXT_DECLINE, "")
        tvDecline.text =
            if (TextUtils.isEmpty(textDecline)) getString(R.string.text_decline) else textDecline

        val acceptCallColor =
            data?.getString(CallkitConstants.EXTRA_CALLKIT_ACCEPT_COLOR, "#4CAF50")
        try {
            ivAcceptCall.setBackground(AppUtils.createCircleDrawable(Color.parseColor(acceptCallColor)))
        } catch (error: Exception) {
        }

        val declineCallColor =
            data?.getString(CallkitConstants.EXTRA_CALLKIT_DECLINE_COLOR, "#F44336")
        try {
            ivDeclineCall.setBackground(AppUtils.createCircleDrawable(Color.parseColor(declineCallColor)))
        } catch (error: Exception) {
        }

        try {
            tvAccept.setTextColor(Color.parseColor(textColor))
            tvDecline.setTextColor(Color.parseColor(textColor))
        } catch (error: Exception) {
        }

        val backgroundColor =
            data?.getString(CallkitConstants.EXTRA_CALLKIT_BACKGROUND_COLOR, "#0955fa")
        try {
            ivBackground.setBackgroundColor(Color.parseColor(backgroundColor))
        } catch (error: Exception) {
        }
        var backgroundUrl = data?.getString(CallkitConstants.EXTRA_CALLKIT_BACKGROUND_URL, "")
        if (!backgroundUrl.isNullOrEmpty()) {
            if (!backgroundUrl.startsWith("http://", true) && !backgroundUrl.startsWith(
                    "https://",
                    true
                )
            ) {
                backgroundUrl =
                    String.format("file:///android_asset/flutter_assets/%s", backgroundUrl)
            }
            val headers =
                data?.getSerializable(CallkitConstants.EXTRA_CALLKIT_HEADERS) as HashMap<String, Any?>
            ImageLoaderProvider.loadImage(this@CallkitIncomingActivity, backgroundUrl, headers, R.drawable.transparent, ivBackground)
        }
    }

    private fun finishTimeout(data: Bundle?, duration: Long) {
        val currentSystemTime = System.currentTimeMillis()
        val timeStartCall =
            data?.getLong(CallkitNotificationManager.EXTRA_TIME_START_CALL, currentSystemTime)
                ?: currentSystemTime

        val timeOut = duration - abs(currentSystemTime - timeStartCall)
        Handler(Looper.getMainLooper()).postDelayed({
            // PANCAKE: `isOngoing` — đã nghe máy thì đây là cuộc gọi đang diễn
            // ra, không phải chuông quá hạn. Thiếu điều kiện này thì đúng 30s
            // sau khi nghe, màn gọi tự tắt giữa chừng.
            if (!isFinishing && !isOngoing) {
                finishTask()
            }
        }, timeOut)
    }

    private fun initView() {
        ivBackground = findViewById(R.id.ivBackground)
        llBackgroundAnimation = findViewById(R.id.llBackgroundAnimation)
        llBackgroundAnimation.layoutParams.height =
            Utils.getScreenWidth() + Utils.getStatusBarHeight(this@CallkitIncomingActivity)
        llBackgroundAnimation.startRippleAnimation()

        tvNameCaller = findViewById(R.id.tvNameCaller)
        tvNumber = findViewById(R.id.tvNumber)
        tvDuration = findViewById(R.id.tvDuration)
        ivLogo = findViewById(R.id.ivLogo)
        ivAvatar = findViewById(R.id.ivAvatar)

        llAction = findViewById(R.id.llAction)

        val params = llAction.layoutParams as MarginLayoutParams
        params.setMargins(0, 0, 0, Utils.getNavigationBarHeight(this@CallkitIncomingActivity))
        llAction.layoutParams = params

        ivAcceptCall = findViewById(R.id.ivAcceptCall)
        tvAccept = findViewById(R.id.tvAccept)
        ivDeclineCall = findViewById(R.id.ivDeclineCall)
        tvDecline = findViewById(R.id.tvDecline)
        animateAcceptCall()

        ivAcceptCall.setOnClickListener {
            onAcceptClick()
        }
        ivDeclineCall.setOnClickListener {
            onDeclineClick()
        }
    }

    private fun animateAcceptCall() {
        val shakeAnimation =
            AnimationUtils.loadAnimation(this@CallkitIncomingActivity, R.anim.shake_anim)
        ivAcceptCall.animation = shakeAnimation
    }


    private fun onAcceptClick() {
        // Log.d("CallkitIncomingActivity", "[CALLKIT] 📱 onAcceptClick")
        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)

        // PANCAKE: phải bật trạng thái "đang gọi" TRƯỚC mọi broadcast. Service
        // xử lý ACTION_CALL_ACCEPT bắn ngược `getIntentEnded(ACCEPTED=true)` về
        // receiver ở trên; đặt sau thì có cửa sổ đua mà `isOngoing` còn false →
        // receiver đóng mất màn gọi.
        if (isKeyguardLocked()) {
            showOngoing()
        }

        CallkitNotificationService.startServiceWithAction(
            this@CallkitIncomingActivity,
            CallkitConstants.ACTION_CALL_ACCEPT,
            data
        )


        // PANCAKE — thay đổi hành vi so với upstream.
        //
        // Upstream: startActivity(TransparentActivity) + dismissKeyguard() + finish()
        // → máy hỏi mật khẩu NGAY khi vừa bấm nghe, và vì app nằm sau keyguard
        //   nên người dùng không thấy màn gọi lẫn đồng hồ đếm giây.
        //
        // Ở đây: máy đang khoá thì Ở LẠI màn này và chuyển sang trạng thái "đang
        // gọi" (đồng hồ chạy, nút Kết thúc) — như Zalo. Chỉ mở khoá khi người
        // dùng chủ động bấm vào avatar để vào app.
        // startActivity BẮT BUỘC phải chạy ở CẢ HAI nhánh: nó dựng FlutterEngine.
        // `FlutterCallkitIncomingPlugin.sendEvent` chỉ đẩy vào các eventChannel
        // đang tồn tại — app bị kill thì không có engine, không có channel, sự
        // kiện accept rơi vào hư không và cuộc gọi KHÔNG bao giờ được answer.
        // Plugin không có background isolate nào để đỡ.
        //
        // Khởi động activity KHÔNG đòi mở khoá: nó chạy sau keyguard, engine lên,
        // Dart answer SIP bình thường. Người dùng không thấy nó vì màn này
        // (showWhenLocked) vẫn nằm trên.
        val acceptIntent =
            TransparentActivity.getIntent(this, CallkitConstants.ACTION_CALL_ACCEPT, data)
        startActivity(acceptIntent)

        // Máy đang khoá: ở lại đây, chạy đồng hồ. KHÔNG dismissKeyguard, KHÔNG
        // finish — đó là chỗ upstream bắt nhập mật khẩu.
        if (isOngoing) return

        dismissKeyguard()
        finish()
    }

    private fun isKeyguardLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    /**
     * PANCAKE — chuyển màn đổ chuông sang trạng thái "đang gọi" tại chỗ.
     *
     * Tái dùng view sẵn có: tắt hiệu ứng chuông, ẩn nút Trả lời, đổi nút Từ chối
     * thành Kết thúc, hiện [tvDuration]. Bấm avatar để vào app (chỗ DUY NHẤT
     * được hỏi mật khẩu).
     *
     * Không giữ trạng thái qua config change: activity khoá portrait trên điện
     * thoại nên thực tế không xoay; tablet xoay sẽ về lại giao diện đổ chuông.
     */
    private fun showOngoing() {
        if (isOngoing) return
        isOngoing = true

        llBackgroundAnimation.stopRippleAnimation()
        ivAcceptCall.clearAnimation()
        ivAcceptCall.visibility = View.GONE
        tvAccept.visibility = View.GONE
        // "Hang up" — nút giờ mang nghĩa kết thúc cuộc đang nói, không phải từ chối.
        tvDecline.text = getString(R.string.text_hang_up)

        tvDuration.visibility = View.VISIBLE
        tvDuration.base = SystemClock.elapsedRealtime()
        tvDuration.start()

        ivAvatar.setOnClickListener { openAppFromLockScreen() }
    }

    /**
     * PANCAKE — mở app khi người dùng bấm avatar lúc đang gọi trên màn khoá.
     *
     * Chỉ đưa app lên foreground bằng launch intent, KHÔNG đi qua
     * [TransparentActivity]: activity đó `sendBroadcast(ACTION_CALL_ACCEPT)` nên
     * gọi lại sẽ bắn accept lần hai xuống Dart cho một cuộc đã answer rồi.
     *
     * Chờ mở khoá xong mới `startActivity`: gọi trước thì app dựng phía sau
     * keyguard và người dùng huỷ mở khoá sẽ mất luôn màn đang gọi.
     */
    private fun openAppFromLockScreen() {
        val launchApp = {
            AppUtils.getAppIntent(this)?.let { startActivity(it) }
            finish()
        }
        val keyguardManager =
            getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            keyguardManager?.isKeyguardLocked == true
        ) {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = launchApp()
                    // Huỷ mở khoá → ở lại màn đang gọi, đồng hồ vẫn chạy.
                    override fun onDismissCancelled() = Unit
                }
            )
        } else {
            launchApp()
        }
    }

    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }

    private fun onDeclineClick() {
        // Log.d("CallkitIncomingActivity", "[CALLKIT] 📱 onDeclineClick")
        val data = intent.extras?.getBundle(CallkitConstants.EXTRA_CALLKIT_INCOMING_DATA)

        // PANCAKE: cùng một nút, hai ý nghĩa. Chưa nghe máy = từ chối; đã nghe
        // máy = kết thúc. Gửi nhầm `decline` cho cuộc đang nói chuyện sẽ báo sai
        // trạng thái về backend.
        val intent = if (isOngoing) {
            CallkitIncomingBroadcastReceiver.getIntentEnded(this@CallkitIncomingActivity, data)
        } else {
            CallkitIncomingBroadcastReceiver.getIntentDecline(this@CallkitIncomingActivity, data)
        }
        sendBroadcast(intent)
        finishTask()
    }

    private fun finishDelayed() {
        Handler(Looper.getMainLooper()).postDelayed({
            finishTask()
        }, 1000)
    }

    private fun finishTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(endedCallkitIncomingBroadcastReceiver)
        super.onDestroy()
    }

    // Start Signify modification
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val soundPlayerManager = FlutterCallkitIncomingPlugin.getInstance()?.getCallkitSoundPlayerManager()
            if (soundPlayerManager?.isPlaying == true) {
                soundPlayerManager.stop()
                return true 
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    // End Signify modification

    override fun onBackPressed() {}
}
