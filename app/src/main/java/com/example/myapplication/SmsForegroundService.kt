package com.example.myapplication

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url

// Data classes for API
data class SmsApiResponse(
    @SerializedName("PhoneNumber") val phoneNumber: String?,
    @SerializedName("Message") val message: String?
)

interface SmsApiService {
    // We use @Url to pass the FULL URL dynamically
    @GET
    suspend fun getSmsData(@Url fullUrl: String): List<SmsApiResponse>
}

class SmsForegroundService : Service() {

    private val TAG = "SmsForegroundService"
    private val CHANNEL_ID = "SmsServiceChannel"
    private val NOTIFICATION_ID = 123

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // We use a dummy base URL because we will override it with @Url at runtime
    private val apiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://dummy.com/") 
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SmsApiService::class.java)
    }

    companion object {
        var isServiceRunning = false
        // Keys for SharedPreferences
        const val PREF_NAME = "SmsSettings"
        const val KEY_USER_TOKEN = "user_token"
        const val KEY_INTERVAL = "timer_interval"
        const val KEY_ENDPOINT = "api_endpoint"
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isServiceRunning = true
        Log.d(TAG, "Foreground Service Started")

        startSmsLoop()

        return START_STICKY
    }

    private fun startSmsLoop() {
        serviceScope.launch {
            while (isServiceRunning) {
                // 1. Read Settings
                val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                var intervalMinutes = prefs.getInt(KEY_INTERVAL, 1)
                if (intervalMinutes < 1) intervalMinutes = 1
                if (intervalMinutes > 60) intervalMinutes = 60

                // 2. Process
                Log.d(TAG, "Checking for SMS... Next check in $intervalMinutes minutes.")
                processSmsSending(prefs)

                // 3. Wait based on user setting
                delay(intervalMinutes * 60 * 1000L) 
            }
        }
    }

    private suspend fun processSmsSending(prefs: android.content.SharedPreferences) {
        val database = AppDatabase.getDatabase(applicationContext)
        val dao = database.smsLogDao()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SMS Permission not granted.")
            return
        }

        try {
            // Read base URL from settings
            // Default: https://6948e8b21ee66d04a4507a69.mockapi.io/
            var baseUrl = prefs.getString(KEY_ENDPOINT, "https://6948e8b21ee66d04a4507a69.mockapi.io/") ?: "https://6948e8b21ee66d04a4507a69.mockapi.io/"
            
            // Ensure base URL ends with /
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/"
            }

            // Append "SMS" to create the full URL
            val fullUrl = baseUrl + "SMS"
            
            val userKey = prefs.getString(KEY_USER_TOKEN, "") ?: ""
            
            Log.d(TAG, "Fetching from Full URL: $fullUrl using Key: $userKey")

            val smsDataList = apiService.getSmsData(fullUrl)
            Log.d(TAG, "API data received: ${smsDataList.size} items.")

            if (smsDataList.isNotEmpty()) {
                smsDataList.forEach { smsData ->
                    val rawPhone = smsData.phoneNumber ?: ""
                    val messageText = smsData.message ?: ""
                    
                    val sanitizedPhone = rawPhone.filter { it.isDigit() || it == '+' }

                    if (sanitizedPhone.isNotBlank()) {
                        val isLocalValid = sanitizedPhone.startsWith("09") && sanitizedPhone.length == 11
                        val isIntlValid = sanitizedPhone.startsWith("+98") && sanitizedPhone.length == 13

                        if (isLocalValid || isIntlValid) {
                            val status = sendSms(sanitizedPhone, messageText)
                            dao.insert(SmsLog(
                                phoneNumber = sanitizedPhone,
                                message = messageText,
                                status = status
                            ))
                        } else {
                            val errorDetail = if (sanitizedPhone.length < 11) "TOO SHORT" 
                                             else if (sanitizedPhone.length > 13) "TOO LONG" 
                                             else "INVALID FORMAT"
                            
                            val errorMsg = "FAILED: $errorDetail (${sanitizedPhone.length} chars)"
                            Log.w(TAG, "Strict validation failed: '$sanitizedPhone'")
                            
                            dao.insert(SmsLog(
                                phoneNumber = sanitizedPhone,
                                message = messageText,
                                status = errorMsg
                            ))
                        }
                    } else {
                        Log.w(TAG, "Skipping invalid number: '$rawPhone'")
                        dao.insert(SmsLog(
                            phoneNumber = rawPhone,
                            message = messageText,
                            status = "FAILED: EMPTY/INVALID"
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "API Error in Service", e)
        }
    }

    private fun sendSms(phoneNumber: String, message: String): String {
        return try {
            val smsManager = getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.i(TAG, "Successfully sent SMS to $phoneNumber")
            "SUCCESS"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $phoneNumber", e)
            "FAILED: ${e.message}"
        }
    }

    private fun stopForegroundService() {
        isServiceRunning = false
        serviceJob.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
        Log.d(TAG, "Foreground Service Stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "SMS Sending Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, SmsForegroundService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Service Running")
            .setContentText("Check settings for interval & endpoint...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop Service", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        serviceJob.cancel()
    }
}