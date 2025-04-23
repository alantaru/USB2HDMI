package com.perfectcorp.usb2hdmi.data.repository

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat // Para registrar receiver
import com.perfectcorp.usb2hdmi.R
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
private const val DEFAULT_DISPLAY_ID = 0 // WORKAROUND

/**
 * Repositório responsável por detectar o estado da conexão USB-C/HDMI
 * e informações das telas, usando DisplayManager e UsbManager.
 * Não gerencia mais MediaProjection diretamente.
 *
 * @param context Contexto da aplicação para acessar serviços do sistema.
 * @param externalScope CoroutineScope para operações de longa duração.
 */
@SuppressLint("WrongConstant")
class ConnectionRepository(
    private val context: Context,
    private val externalScope: CoroutineScope
) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // --- State Flows ---
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _availableResolutions = MutableStateFlow<List<Resolution>>(emptyList())
    val availableResolutions: StateFlow<List<Resolution>> = _availableResolutions.asStateFlow()

    // Não precisamos mais expor a resolução atual, o serviço pode gerenciar isso internamente se necessário
    // private val _currentResolution = MutableStateFlow<Resolution?>(null)
    // val currentResolution: StateFlow<Resolution?> = _currentResolution.asStateFlow()

    // --- Event Flow for Errors ---
    private val _errorEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    // --- Internal State ---
    private var displayListener: DisplayManager.DisplayListener? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var isUsbDeviceAttached = false // Flag para rastrear estado USB

    init {
        registerDisplayListener()
        registerUsbReceiver()
        // Verificar estado inicial combinando informações de USB e Display
        updateConnectionStatus()
    }

    // --- Display Listener Logic ---
    private fun registerDisplayListener() {
        // ... (código existente sem alterações) ...
        if (displayListener != null) return

        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                Log.d(TAG, "Display Adicionado: $displayId")
                updateConnectionStatus()
            }

            override fun onDisplayRemoved(displayId: Int) {
                Log.d(TAG, "Display Removido: $displayId")
                // A lógica de parar projeção agora é responsabilidade externa (Serviço/ViewModel)
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
        // ... (código existente sem alterações) ...
        displayListener?.let {
            displayManager.unregisterDisplayListener(it)
            displayListener = null
            Log.d(TAG, "DisplayListener desregistrado.")
        }
    }

    // --- USB Receiver Logic ---
    private fun registerUsbReceiver() {
        if (usbReceiver != null) return

        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        Log.d(TAG, "USB Device Attached: $device")
                        // TODO: Verificar se o device é o adaptador H'Maston (Vendor/Product ID)
                        isUsbDeviceAttached = true // Simplificação inicial
                        updateConnectionStatus()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        Log.d(TAG, "USB Device Detached: $device")
                        // TODO: Verificar se era o adaptador H'Maston
                        isUsbDeviceAttached = false // Simplificação inicial
                        updateConnectionStatus()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Registrar receiver com contexto e flags apropriadas
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Verificar estado inicial do USB (dispositivos já conectados)
        checkInitialUsbState()
        Log.d(TAG, "UsbReceiver registrado.")
    }

     private fun checkInitialUsbState() {
         externalScope.launch(Dispatchers.IO) {
             val deviceList = usbManager.deviceList
             // TODO: Iterar e verificar se algum dispositivo conectado é o adaptador H'Maston
             isUsbDeviceAttached = deviceList.isNotEmpty() // Simplificação MUITO básica
             Log.d(TAG, "Estado inicial USB verificado: isUsbDeviceAttached=$isUsbDeviceAttached")
             // Não chamar updateConnectionStatus aqui diretamente para evitar race condition com display listener inicial
         }
     }


    private fun unregisterUsbReceiver() {
        usbReceiver?.let {
            try {
                context.unregisterReceiver(it)
                usbReceiver = null
                Log.d(TAG, "UsbReceiver desregistrado.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "UsbReceiver já estava desregistrado ou nunca foi registrado.")
            }
        }
    }

    // --- Connection Status Update Logic (Refatorado) ---
    private fun updateConnectionStatus() {
        externalScope.launch(Dispatchers.IO) {
            try {
                val displays = displayManager.displays
                val externalDisplay = displays.find { it.displayId != DEFAULT_DISPLAY_ID }

                val currentStatus = _connectionStatus.value
                val newStatus: ConnectionStatus

                if (externalDisplay != null) {
                    // Se um display externo está visível, consideramos pronto ou transmitindo (depende do serviço)
                    Log.d(TAG, "Monitor externo detectado: ID=${externalDisplay.displayId}")
                    updateAvailableResolutions(externalDisplay)
                    // O estado TRANSMITTING agora é gerenciado externamente.
                    // Se o display existe, está pelo menos pronto.
                    newStatus = ConnectionStatus.READY_TO_TRANSMIT
                    // Poderíamos tentar inferir TRANSMITTING se soubéssemos que o serviço está rodando,
                    // mas é mais seguro deixar o ViewModel/Serviço gerenciar isso.

                } else {
                    // Sem display externo. Verificar se o adaptador USB está conectado.
                    if (isUsbDeviceAttached) {
                         Log.d(TAG, "Adaptador USB detectado, mas sem monitor externo.")
                         newStatus = ConnectionStatus.ADAPTER_CONNECTED
                    } else {
                         Log.d(TAG, "Nenhum adaptador USB ou monitor externo detectado.")
                         newStatus = ConnectionStatus.DISCONNECTED
                    }
                    // Limpar resoluções se não há display externo
                    _availableResolutions.value = emptyList()
                }

                if (currentStatus != newStatus) {
                    Log.i(TAG, "Mudança de Status: $currentStatus -> $newStatus")
                    _connectionStatus.value = newStatus
                }
                // Não precisamos mais atualizar _currentResolution aqui

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao atualizar status da conexão", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                emitError(context.getString(R.string.error_unknown_connection))
            }
        }
    }

    // --- Resolution Update Logic ---
    private suspend fun updateAvailableResolutions(display: Display) = withContext(Dispatchers.IO) {
        // ... (código existente sem alterações) ...
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
        }
    }

    // Não precisamos mais desta função aqui
    // private suspend fun updateCurrentResolution(display: Display) = ...

    // --- Media Projection Logic (REMOVIDA) ---
    // fun createScreenCaptureIntent(): Intent { ... }
    // fun startProjection(...) { ... }
    // fun stopProjection() { ... }
    // private fun stopProjectionInternal() { ... }
    // private val mediaProjectionCallback = ...
    // private val virtualDisplayCallback = ...

    // --- Cleanup ---
    fun cleanup() {
        Log.d(TAG, "Limpando ConnectionRepository...")
        unregisterDisplayListener()
        unregisterUsbReceiver()
        // Não precisamos mais parar projeção aqui
    }

    // --- Error Handling ---
    private suspend fun emitError(message: String) {
        _errorEvents.emit(message)
    }

    // --- USB Detection (Placeholder) ---
    // A lógica básica está no receiver, refinar a verificação do dispositivo específico
    private suspend fun isSpecificAdapterAttached(device: UsbDevice?): Boolean = withContext(Dispatchers.IO) {
        if (device == null) return@withContext false
        // TODO: Implementar verificação de Vendor ID (VID) e Product ID (PID)
        // Exemplo: return device.vendorId == VENDOR_ID_HMASTON && device.productId == PRODUCT_ID_HMASTON
        Log.w(TAG, "Verificação específica do adaptador H'Maston não implementada (VID/PID).")
        return@withContext true // Assumir que qualquer dispositivo é o adaptador por enquanto
    }
}