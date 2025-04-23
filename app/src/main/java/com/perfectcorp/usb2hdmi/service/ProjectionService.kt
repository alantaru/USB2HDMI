package com.perfectcorp.usb2hdmi.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.perfectcorp.usb2hdmi.R
import com.perfectcorp.usb2hdmi.data.model.Resolution
import com.perfectcorp.usb2hdmi.ui.main.MainActivity // Para intent da notificação

// Constantes
private const val TAG = "ProjectionService"
private const val NOTIFICATION_CHANNEL_ID = "ProjectionServiceChannel"
private const val NOTIFICATION_ID = 1
private const val VIRTUAL_DISPLAY_NAME = "USB2HDMIProjectionService"
private const val DEFAULT_DISPLAY_ID = 0 // WORKAROUND

class ProjectionService : Service() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var displayManager: DisplayManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    companion object {
        const val ACTION_START = "com.perfectcorp.usb2hdmi.service.ACTION_START"
        const val ACTION_STOP = "com.perfectcorp.usb2hdmi.service.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_RESOLUTION = "extra_target_resolution" // Pode ser Parcelable
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Serviço onCreate")
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Serviço onStartCommand: Action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1) // Activity.RESULT_CANCELED
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                // Obter resolução alvo (precisa ser Parcelable ou usar Bundle)
                val targetResolution: Resolution? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_TARGET_RESOLUTION, Resolution::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_TARGET_RESOLUTION)
                }


                if (resultCode != -1 /* Activity.RESULT_OK */ && resultData != null) {
                    startForegroundWithNotification()
                    startProjectionInternal(resultCode, resultData, targetResolution)
                } else {
                    Log.e(TAG, "Falha ao iniciar: resultCode ou resultData inválidos.")
                    stopSelf() // Para o serviço se não puder iniciar
                }
            }
            ACTION_STOP -> {
                stopProjectionInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY // Não reiniciar automaticamente se o sistema matar
    }

    private fun startProjectionInternal(resultCode: Int, resultData: Intent, targetResolution: Resolution?) {
        if (mediaProjection != null || virtualDisplay != null) {
            Log.w(TAG, "Projeção já ativa.")
            return
        }

        Log.i(TAG, "Iniciando projeção no serviço...")
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao obter MediaProjection (exceção)", e)
            // TODO: Comunicar erro de volta (ex: via BroadcastReceiver ou Flow no Repo)
            stopSelf()
            return
        }

        if (mediaProjection == null) {
            Log.e(TAG, "Falha ao obter MediaProjection (retornou null).")
            // TODO: Comunicar erro
            stopSelf()
            return
        }

        mediaProjection?.registerCallback(mediaProjectionCallback, null)

        // Lógica de criação do VirtualDisplay (similar ao ConnectionRepository anterior)
        val externalDisplay = displayManager.displays.find { it.displayId != DEFAULT_DISPLAY_ID }
        if (externalDisplay == null) {
            Log.e(TAG, "Display externo não encontrado no serviço.")
            stopProjectionInternal()
            // TODO: Comunicar erro
            stopSelf()
            return
        }

        val width = targetResolution?.width ?: externalDisplay.mode.physicalWidth
        val height = targetResolution?.height ?: externalDisplay.mode.physicalHeight
        val metrics = DisplayMetrics()
        val defaultDisplay = displayManager.getDisplay(DEFAULT_DISPLAY_ID)
        if (defaultDisplay != null) {
            defaultDisplay.getMetrics(metrics)
        } else {
            Log.e(TAG, "Não foi possível obter o display padrão (ID 0) para métricas.")
            externalDisplay.getMetrics(metrics) // Fallback
        }
        val densityDpi = metrics.densityDpi
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        Log.d(TAG, "Criando VirtualDisplay no serviço: ${width}x${height} @ ${densityDpi}dpi, Flags=$flags")

        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME, width, height, densityDpi, flags,
                null, virtualDisplayCallback, null
            )

            if (virtualDisplay == null) {
                Log.e(TAG, "Falha ao criar VirtualDisplay no serviço (retornou null).")
                stopProjectionInternal()
                // TODO: Comunicar erro
                stopSelf()
            } else {
                Log.i(TAG, "VirtualDisplay criado com sucesso no serviço.")
                // TODO: Atualizar estado global/repositório se necessário
            }
        } catch (e: Exception) { // Captura SecurityException e outros
            Log.e(TAG, "Erro ao criar VirtualDisplay no serviço", e)
            stopProjectionInternal()
            // TODO: Comunicar erro
            stopSelf()
        }
    }

    private fun stopProjectionInternal() {
        Log.i(TAG, "Parando projeção no serviço...")
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        // TODO: Atualizar estado global/repositório
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection parado externamente (Callback).")
            stopProjectionInternal()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() { Log.d(TAG, "VirtualDisplay (Serviço) pausado.") }
        override fun onResumed() { Log.d(TAG, "VirtualDisplay (Serviço) retomado.") }
        override fun onStopped() {
            Log.w(TAG, "VirtualDisplay (Serviço) parado (Callback).")
            stopProjectionInternal()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundWithNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "Serviço iniciado em primeiro plano.")
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        // TODO: Adicionar ação para parar a projeção na notificação
        // val stopIntent = Intent(this, ProjectionService::class.java).apply { action = ACTION_STOP }
        // val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("USB2HDMI Transmitindo")
            .setContentText("A tela está sendo transmitida para o monitor externo.")
            .setSmallIcon(R.drawable.ic_baseline_cast_connected_24) // Usar ícone apropriado
            .setContentIntent(pendingIntent)
            // .addAction(R.drawable.ic_stop, "Parar", stopPendingIntent) // Adicionar ação de parada
            .setOngoing(true) // Torna a notificação não dispensável enquanto o serviço roda
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Canal Serviço de Projeção USB2HDMI",
                NotificationManager.IMPORTANCE_LOW // Baixa importância para não ser intrusivo
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            Log.d(TAG, "Canal de notificação criado.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Não fornecemos binding para este serviço
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Serviço onDestroy")
        stopProjectionInternal() // Garantir limpeza final
    }
}