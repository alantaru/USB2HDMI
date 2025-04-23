package com.perfectcorp.usb2hdmi.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
// import android.hardware.display.DisplayManager.DEFAULT_DISPLAY // Mantendo comentado devido a erro persistente
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.perfectcorp.usb2hdmi.R // Importar R para string resources
import com.perfectcorp.usb2hdmi.data.model.ConnectionStatus
import com.perfectcorp.usb2hdmi.data.model.Resolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Constante para Logging
private const val TAG = "ConnectionRepository"
private const val VIRTUAL_DISPLAY_NAME = "USB2HDMIProjection"
private const val DEFAULT_DISPLAY_ID = 0 // WORKAROUND: Usar ID 0 explicitamente

/**
 * Repositório responsável por gerenciar a detecção da conexão USB-C/HDMI,
 * o estado da projeção de mídia (MediaProjection) e informações das telas.
 *
 * Interage com DisplayManager, MediaProjectionManager e potencialmente UsbManager.
 *
 * @param context Contexto da aplicação para acessar serviços do sistema.
 * @param externalScope CoroutineScope para operações de longa duração ou que precisam sobreviver ao ViewModel.
 */
@SuppressLint("WrongConstant")
class ConnectionRepository(
    private val context: Context,
    private val externalScope: CoroutineScope
) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    // --- State Flows ---
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _availableResolutions = MutableStateFlow<List<Resolution>>(emptyList())
    val availableResolutions: StateFlow<List<Resolution>> = _availableResolutions.asStateFlow()

    private val _currentResolution = MutableStateFlow<Resolution?>(null)
    val currentResolution: StateFlow<Resolution?> = _currentResolution.asStateFlow()

    // --- Event Flow for Errors ---
    private val _errorEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1, // Buffer para garantir que o último erro seja observado
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()


    // --- Internal State ---
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    init {
        registerDisplayListener()
        updateConnectionStatus()
    }

    // --- Display Listener Logic ---
    private fun registerDisplayListener() {
        if (displayListener != null) return

        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                Log.d(TAG, "Display Adicionado: $displayId")
                updateConnectionStatus()
            }

            override fun onDisplayRemoved(displayId: Int) {
                Log.d(TAG, "Display Removido: $displayId")
                if (_connectionStatus.value == ConnectionStatus.TRANSMITTING && virtualDisplay?.display?.displayId == displayId) {
                    Log.w(TAG, "Display da projeção ativa foi removido. Parando projeção.")
                    stopProjectionInternal()
                    // Emitir evento informativo?
                    // emitError("Monitor desconectado.") // Ou deixar updateConnectionStatus tratar
                }
                updateConnectionStatus()
            }

            override fun onDisplayChanged(displayId: Int) {
                Log.d(TAG, "Display Modificado: $displayId")
                updateConnectionStatus()
            }
        }
        displayManager.registerDisplayListener(displayListener, null)
        Log.d(TAG, "DisplayListener registrado.")
    }

    private fun unregisterDisplayListener() {
        displayListener?.let {
            displayManager.unregisterDisplayListener(it)
            displayListener = null
            Log.d(TAG, "DisplayListener desregistrado.")
        }
    }

    // --- Connection Status Update Logic ---
    private fun updateConnectionStatus() {
        externalScope.launch(Dispatchers.IO) {
            try {
                val displays = displayManager.displays
                val externalDisplay = displays.find { it.displayId != DEFAULT_DISPLAY_ID }

                val currentStatus = _connectionStatus.value
                var newStatus: ConnectionStatus

                if (externalDisplay != null) {
                    Log.d(TAG, "Monitor externo detectado: ID=${externalDisplay.displayId}, Nome=${externalDisplay.name}")
                    updateAvailableResolutions(externalDisplay)

                    newStatus = if (mediaProjection != null && virtualDisplay != null) {
                        updateCurrentResolution(externalDisplay)
                        ConnectionStatus.TRANSMITTING
                    } else {
                        ConnectionStatus.READY_TO_TRANSMIT
                    }
                } else {
                    Log.d(TAG, "Nenhum monitor externo detectado.")
                    _availableResolutions.value = emptyList()
                    _currentResolution.value = null

                    if (virtualDisplay != null) {
                        Log.w(TAG, "Display externo sumiu enquanto projeção estava ativa. Parando internamente.")
                        withContext(Dispatchers.Main) { stopProjectionInternal() }
                    }
                    // TODO: Implementar detecção do adaptador USB para diferenciar DISCONNECTED de ADAPTER_CONNECTED
                    newStatus = ConnectionStatus.DISCONNECTED
                }

                if (currentStatus != newStatus) {
                    Log.i(TAG, "Mudança de Status: $currentStatus -> $newStatus")
                    _connectionStatus.value = newStatus
                } else if (newStatus == ConnectionStatus.TRANSMITTING && externalDisplay != null) {
                    updateCurrentResolution(externalDisplay)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao atualizar status da conexão", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                emitError(context.getString(R.string.error_unknown_connection))
            }
        }
    }

    // --- Resolution Update Logic ---
    private suspend fun updateAvailableResolutions(display: Display) = withContext(Dispatchers.IO) {
        try {
            val modes = display.supportedModes.mapNotNull { mode ->
                if (mode.physicalWidth > 0 && mode.physicalHeight > 0) {
                    Resolution(mode.physicalWidth, mode.physicalHeight, mode.refreshRate.toInt())
                } else null
            }.distinct()
             .sortedWith(compareByDescending<Resolution> { it.width * it.height }.thenByDescending { it.refreshRate })

            if (_availableResolutions.value != modes) {
                Log.d(TAG, "Resoluções suportadas para Display ${display.displayId}: $modes")
                _availableResolutions.value = modes
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter modos suportados para Display ${display.displayId}", e)
            _availableResolutions.value = emptyList()
            // Não emitir erro aqui necessariamente, pode ser normal não ter modos
        }
    }

    private suspend fun updateCurrentResolution(display: Display) = withContext(Dispatchers.IO) {
        try {
            val currentMode = display.mode
            val resolution = if (currentMode.physicalWidth > 0 && currentMode.physicalHeight > 0) {
                Resolution(currentMode.physicalWidth, currentMode.physicalHeight, currentMode.refreshRate.toInt())
            } else null

            if (_currentResolution.value != resolution) {
                Log.d(TAG, "Resolução atual para Display ${display.displayId}: $resolution")
                _currentResolution.value = resolution
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter modo atual para Display ${display.displayId}", e)
            _currentResolution.value = null
            // Não emitir erro aqui necessariamente
        }
    }

    // --- Media Projection Logic ---
    fun createScreenCaptureIntent(): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    fun startProjection(resultCode: Int, resultData: Intent, targetResolution: Resolution?) {
        externalScope.launch(Dispatchers.Main) {
            if (mediaProjection != null || virtualDisplay != null) {
                Log.w(TAG, "Tentativa de iniciar projeção quando já existe uma ativa.")
                return@launch
            }

            Log.i(TAG, "Iniciando projeção...")
            try {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            } catch (e: Exception) {
                 Log.e(TAG, "Falha ao obter MediaProjection (exceção)", e)
                 _connectionStatus.value = ConnectionStatus.ERROR
                 emitError(context.getString(R.string.error_failed_to_get_permission)) // Mensagem genérica de permissão
                 return@launch
            }


            if (mediaProjection == null) {
                Log.e(TAG, "Falha ao obter MediaProjection (retornou null).")
                _connectionStatus.value = ConnectionStatus.ERROR
                emitError(context.getString(R.string.error_failed_to_get_permission))
                return@launch
            }

            mediaProjection?.registerCallback(mediaProjectionCallback, null)

            val externalDisplay = displayManager.displays.find { it.displayId != DEFAULT_DISPLAY_ID }
            if (externalDisplay == null) {
                Log.e(TAG, "Display externo desapareceu antes de iniciar VirtualDisplay.")
                stopProjectionInternal()
                updateConnectionStatus() // Reavalia status
                emitError("Monitor externo desconectado inesperadamente.") // Mensagem mais específica
                return@launch
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

            Log.d(TAG, "Criando VirtualDisplay: ${width}x${height} @ ${densityDpi}dpi, Flags=$flags")

            try {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME, width, height, densityDpi, flags,
                    null, virtualDisplayCallback, null
                )

                if (virtualDisplay == null) {
                    Log.e(TAG, "Falha ao criar VirtualDisplay (retornou null).")
                    stopProjectionInternal()
                    _connectionStatus.value = ConnectionStatus.ERROR
                    emitError("Falha ao iniciar a transmissão para o monitor.")
                } else {
                    Log.i(TAG, "VirtualDisplay criado com sucesso no Display ID: ${virtualDisplay?.display?.displayId}")
                    _connectionStatus.value = ConnectionStatus.TRANSMITTING
                    updateCurrentResolution(externalDisplay)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Erro de segurança ao criar VirtualDisplay. Permissão revogada?", e)
                stopProjectionInternal()
                _connectionStatus.value = ConnectionStatus.ERROR
                emitError("Erro de segurança ao iniciar transmissão. Verifique as permissões.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro inesperado ao criar VirtualDisplay", e)
                stopProjectionInternal()
                _connectionStatus.value = ConnectionStatus.ERROR
                emitError(context.getString(R.string.error_unknown_connection))
            }
        }
    }

    fun stopProjection() {
        externalScope.launch(Dispatchers.Main) {
            stopProjectionInternal()
            updateConnectionStatus()
        }
    }

    private fun stopProjectionInternal() {
        if (virtualDisplay != null) {
            Log.d(TAG, "Liberando VirtualDisplay...")
            virtualDisplay?.release()
            virtualDisplay = null
            Log.d(TAG, "VirtualDisplay liberado.")
        }
        if (mediaProjection != null) {
            Log.i(TAG, "Parando MediaProjection...")
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            _currentResolution.value = null
            Log.i(TAG, "MediaProjection parado.")
        }
        if (_connectionStatus.value == ConnectionStatus.TRANSMITTING) {
            Log.d(TAG, "Projeção parada internamente, status será reavaliado.")
            // Não mudar o status aqui diretamente, deixar updateConnectionStatus fazer isso
        }
    }

    // --- Callbacks ---
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection parado externamente (Callback).")
            externalScope.launch(Dispatchers.Main) {
                stopProjectionInternal()
                updateConnectionStatus()
                // Emitir evento informativo?
                // emitError("Transmissão interrompida.")
            }
        }
    }

    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() { Log.d(TAG, "VirtualDisplay pausado.") }
        override fun onResumed() { Log.d(TAG, "VirtualDisplay retomado.") }
        override fun onStopped() {
            Log.w(TAG, "VirtualDisplay parado (Callback).")
            externalScope.launch(Dispatchers.Main) {
                stopProjectionInternal()
                updateConnectionStatus()
                emitError("Erro interno na exibição externa.")
            }
        }
    }

    // --- Cleanup ---
    fun cleanup() {
        Log.d(TAG, "Limpando ConnectionRepository...")
        externalScope.launch(Dispatchers.Main) {
            unregisterDisplayListener()
            stopProjectionInternal()
        }
    }

    // --- Error Handling ---
    private suspend fun emitError(message: String) {
        _errorEvents.emit(message)
    }

    // --- USB Detection (Placeholder) ---
    private suspend fun detectUsbAdapter(): Boolean = withContext(Dispatchers.IO) {
        Log.w(TAG, "Detecção de adaptador USB ainda não implementada.")
        return@withContext false
    }
}