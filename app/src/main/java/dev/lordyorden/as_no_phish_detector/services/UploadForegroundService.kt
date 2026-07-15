package dev.lordyorden.as_no_phish_detector.services

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.clerk.api.Clerk
import dev.lordyorden.as_no_phish_detector.MainActivity
import dev.lordyorden.as_no_phish_detector.models.CapturedNotificationPayload
import dev.lordyorden.as_no_phish_detector.models.PendingNotificationUpload
import dev.lordyorden.as_no_phish_detector.models.RelentNotificationInfo
import dev.lordyorden.as_no_phish_detector.repositories.CircleMembersRepository
import dev.lordyorden.as_no_phish_detector.retrofit.NotificationController
import dev.lordyorden.as_no_phish_detector.retrofit.SmsController
import dev.lordyorden.as_no_phish_detector.utilities.Constants
import dev.lordyorden.as_no_phish_detector.utilities.AiSecurityAnalysisSettingsStore
import dev.lordyorden.as_no_phish_detector.utilities.NotificationHelper
import dev.lordyorden.as_no_phish_detector.utilities.PendingNotificationUploadStore
import dev.lordyorden.as_no_phish_detector.utilities.SecureNotificationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID


class UploadForegroundService : LifecycleService() {

    private var isServiceRunningRightNow = false
    private lateinit var wakeLock: WakeLock
    private lateinit var powerManager: PowerManager
    private var lastShownNotificationId = -1
    private val smsController: SmsController = SmsController()
    private val notificationController: NotificationController = NotificationController()
    private lateinit var pendingUploadStore: PendingNotificationUploadStore
    private lateinit var aiSecurityAnalysisSettingsStore: AiSecurityAnalysisSettingsStore
    private var retryPendingUploadsJob: Job? = null
    private val flushMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: created()")

        pendingUploadStore = PendingNotificationUploadStore.getInstance(this)
        aiSecurityAnalysisSettingsStore = AiSecurityAnalysisSettingsStore.getInstance(this)
        schedulePendingUploadRetry()

        lifecycleScope.launch {
            MessageBridge.messageFlow.collect { bundle ->
                handleNewMessage(bundle)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand: Intent Data: ${intent?.action}")

        val action = intent?.action

        if (action == ACTION_START) {

            if (!isServiceRunningRightNow) {
                isServiceRunningRightNow = true
                notifyToUserForForegroundService()
            }
            _isRunning.value = true

            lifecycleScope.launch {
                flushPendingUploads()
            }

        } else if (action == ACTION_STOP) {
            stopHandlingMassages()
            isServiceRunningRightNow = false
            _isRunning.value = false
            stopSelf()
        }

        return START_STICKY
    }

    private suspend fun handleNewMessage(message: Bundle){
        //Log.d(TAG, "startRecording() + " + Thread.currentThread().name)
        flushPendingUploads()

        val isSMS = message.getBoolean("isSMS", true)

/*        if(isSMS){
            val number = message.getString("address", "none")
            val body = message.getString("body", "none")
            val timestamp = message.getLong("timestamp", 0L)

            Log.i(TAG, "$timestamp new message from $number: $body")

            smsController.uploadSms(CreateSmsMessage(number, body, timestamp), object : GenericCallback<SmsMessage>{
                override fun success(data: SmsMessage?) {
                    Log.i(TAG, "uploaded sms message $data")
                }

                override fun error(error: String?) {
                    Log.e(TAG, "upload failed: $error")
                }
            })
        }else{*/
        if(!isSMS){

            val title = message.getString("title", "none")
            val extraTitle = message.getString("extraTitle", "none")
            val isGroup = message.getBoolean("isGroup", false)
            val body = message.getString("body", "none")
            val packageName = message.getString("packageName", "none")
            val timestamp = message.getLong("timestamp", 0L)
            val urls = message.getStringArrayList("urls")?.toList() ?: listOf<String>()
            val payload = CapturedNotificationPayload(
                eventId = UUID.randomUUID().toString(),
                title = title,
                body = body,
                packageName = packageName,
                timestamp = timestamp,
                urls = urls
            )
            val sourceUserId = Clerk.activeUser?.id
            if (sourceUserId.isNullOrBlank()) {
                Log.e(TAG, "Rejecting notification upload: missing Clerk user for eventId=${payload.eventId}")
                return
            }

            val circleId = CircleMembersRepository.getInstance().currentCircleId()
            if (circleId.isNullOrBlank()) {
                Log.e(TAG, "Rejecting notification upload: missing circleId for eventId=${payload.eventId}")
                return
            }

            val pendingUpload = PendingNotificationUpload(
                payload = payload,
                createdAt = System.currentTimeMillis(),
                sourceUserId = sourceUserId,
                circleId = circleId
            )

/*            val notif = CreateNotification(
                title,
                extraTitle,
                isGroup,
                body,
                packageName,
                timestamp)*/

            if (!uploadNotification(pendingUpload)) {
                pendingUploadStore.save(pendingUpload)
                schedulePendingUploadRetry()
            }
        }


/*        // Keep CPU working
        val powerManager: PowerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoPhishDetector:tag");
        wakeLock.acquire(10*60*1000L)s*/
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        retryPendingUploadsJob?.cancel()
    }

    private fun stopHandlingMassages(){
        //wakeLock.release();
        retryPendingUploadsJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun schedulePendingUploadRetry() {
        if (retryPendingUploadsJob?.isActive == true) return

        retryPendingUploadsJob = lifecycleScope.launch {
            while (true) {
                flushPendingUploads()

                if (pendingUploadStore.getPendingUploads().isEmpty()) {
                    return@launch
                }

                delay(Constants.UploadScheduler.RETRY_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun flushPendingUploads() {
        flushMutex.withLock {
            pendingUploadStore.removeExpired()

            val uploadedEventIds = mutableSetOf<String>()

            pendingUploadStore.getPendingUploads().forEach { pendingUpload ->
                if (uploadNotification(pendingUpload)) {
                    uploadedEventIds.add(pendingUpload.payload.eventId)
                }
            }

            pendingUploadStore.remove(uploadedEventIds)
        }
    }

    private suspend fun uploadNotification(
        upload: PendingNotificationUpload
    ): Boolean {
        val payload = upload.payload
        val allowExternalAnalysis = aiSecurityAnalysisSettingsStore.isEnabled()

        val contentHash = SecureNotificationHelper.contentHash(
            eventId = payload.eventId,
            title = payload.title,
            body = payload.body,
            packageName = payload.packageName,
            urls = payload.urls,
            notificationTimestamp = payload.timestamp,
            sourceUserId = upload.sourceUserId
        )

        val rel = RelentNotificationInfo(
            eventId = payload.eventId,
            sourceUserId = upload.sourceUserId,
            circleId = upload.circleId,
            title = payload.title,
            body = payload.body,
            packageName = payload.packageName,
            timestamp = payload.timestamp,
            contentHash = contentHash,
            urls = payload.urls,
            allowExternalAnalysis = allowExternalAnalysis
        )

        return try {
            //notificationController.apiService.uploadNotification(notif)
            val response = notificationController.apiService.uploadRelInfo(rel)
            if (!response.isSuccessful) {
                Log.e(TAG, "upload failed with HTTP ${response.code()} for event: ${rel.eventId}")
                return false
            }

            Log.i(TAG, "uploaded notification event: ${rel.eventId}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "upload failed: $e")
            false
        }
    }

    /*@OptIn(DelicateCoroutinesApi::class)
    fun makeApiCall() {
        Log.i(TAG, "makeApiCall: Called!")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitBuilder.getApiService().getPosts()
                Log.i(TAG, "rawJSON: ${response.body()}")
            } catch (e: Exception) {
               Log.e(TAG, "rawJSON: ", e)
            }

            //stop the service running foreground.
            stopSelf()
        }
        Log.i(TAG, "makeApiCall: Released!")
    }*/

    private fun notifyToUserForForegroundService(){

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            action = MAIN_ACTION
            //flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val notification = NotificationHelper.getInstance().buildForegroundServiceNotification(
            title = "Keeping you protected",
            body = "Scanning incoming messages and notifications",
            channelId = CHANNEL_ID,
            intent = notificationIntent
        )

        startForeground(NOTIFICATION_ID, notification)

        if (NOTIFICATION_ID != lastShownNotificationId) {
            // Cancel previous notification
            val notificationManager: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(lastShownNotificationId)
        }
        lastShownNotificationId = NOTIFICATION_ID
    }

/*    override fun onBind(p0: Intent?): IBinder? {
        Log.i(TAG, "onBind: called!")
        return null
    }

    override fun stopService(name: Intent?): Boolean {
        Log.i(TAG, "stopService: stopping.........")
        return super.stopService(name)
    }

    fun isMyServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        //val runs = manager.getRunningServices(Int.MAX_VALUE)
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (UploadForegroundService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }*/

    companion object {
        private const val TAG = "UploadForegroundService"
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        const val CHANNEL_ID = "dev.lordyorden.as_no_phish_detector.CHANNEL_ID_FOREGROUND"
        const val NOTIFICATION_ID = 127
        const val MAIN_ACTION: String = "dev.lordyorden.as_no_phish_detector.Services.UploadForegroundService.action.main"
        const val ACTION_START: String = "ACTION_START"
        const val ACTION_STOP: String = "ACTION_STOP"
    }
}
