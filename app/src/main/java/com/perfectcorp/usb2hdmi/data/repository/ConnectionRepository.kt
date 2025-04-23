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
import androidx.core.content.ContextCompat
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

    // --- Event Flow for Errors ---
    private val _errorEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    // --- Internal State ---
    private var displayListener: DisplayManager.DisplayListener? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var isUsbDeviceAttached = false

    init {
        registerDisplayListener()
        registerUsbReceiver()
        updateConnectionStatus() // Verificar estado inicial
    }

    // --- Display Listener Logic ---
    private fun registerDisplayListener() {
        if (displayListener != null) return

        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                // LOG ADICIONADO
                Log.i(TAG, "*** DisplayListener: onDisplayAdded(displayId=$displayId)")
                updateConnectionStatus()
            }

            override fun onDisplayRemoved(displayId: Int) {
                // LOG ADICIONADO
                Log.i(TAG, "*** DisplayListener: onDisplayRemoved(displayId=$displayId)")
                updateConnectionStatus()
            }

            override fun onDisplayChanged(displayId: Int) {
                // LOG ADICIONADO
                Log.i(TAG, "*** DisplayListener: onDisplayChanged(displayId=$displayId)")
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

    // --- USB Receiver Logic ---
    private fun registerUsbReceiver() {
        if (usbReceiver != null) return

        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                // LOG ADICIONADO
                Log.i(TAG, "*** UsbReceiver: onReceive(action=$action, device=${device?.deviceName}, VID=${device?.vendorId}, PID=${device?.productId})")

                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        // TODO: Verificar VID/PID do H'Maston
                        if (isSpecificAdapterAttachedBlocking(device)) { // Usar versão bloqueante aqui
                            Log.i(TAG, "Adaptador USB específico conectado.")
                            isUsbDeviceAttached = true
                        } else {
                            Log.d(TAG, "Outro dispositivo USB conectado, ignorando.")
                        }
                        // CHAMADA EXTRA ADICIONADA: Reavaliar status mesmo se já estava conectado
                        updateConnectionStatus()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        // TODO: Verificar VID/PID do H'Maston
                        if (isSpecificAdapterAttachedBlocking(device)) { // Usar versão bloqueante aqui
                             Log.i(TAG, "Adaptador USB específico desconectado.")
                             isUsbDeviceAttached = false
                             updateConnectionStatus() // Reavaliar status
                        } else {
                             Log.d(TAG, "Outro dispositivo USB desconectado, ignorando.")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        checkInitialUsbState()
        Log.d(TAG, "UsbReceiver registrado.")
    }

     private fun checkInitialUsbState() {
         externalScope.launch(Dispatchers.IO) {
             val deviceList = usbManager.deviceList
             isUsbDeviceAttached = deviceList.values.any { isSpecificAdapterAttachedBlocking(it) }
             Log.d(TAG, "Estado inicial USB verificado: isUsbDeviceAttached=$isUsbDeviceAttached")
             // Chamar updateConnectionStatus aqui pode ser útil após a verificação inicial
             updateConnectionStatus()
         }
     }

    private fun unregisterUsbReceiver() {
        usbReceiver?.let {
            try {
                context.unregisterReceiver(it)
                usbReceiver = null
                Log.d(TAG, "UsbReceiver desregistrado.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "UsbReceiver já estava desregistrado.")
            }
        }
    }

    // --- Connection Status Update Logic (Refatorado) ---
    private fun updateConnectionStatus() {
        externalScope.launch(Dispatchers.IO) {
            // LOG ADICIONADO
            Log.d(TAG, "==> updateConnectionStatus chamado. isUsbAttached=$isUsbDeviceAttached")
            try {
                val displays = displayManager.displays
                // LOG ADICIONADO
                val displayInfo = displays.joinToString { "ID=${it.displayId}, Name=${it.name}, Valid=${it.isValid}" }
                Log.d(TAG, "Displays encontrados: [${displayInfo}]")

                val externalDisplay = displays.find { it.displayId != DEFAULT_DISPLAY_ID && it.isValid } // Adicionado isValid

                val currentStatus = _connectionStatus.value
                val newStatus: ConnectionStatus

                if (externalDisplay != null) {
                    Log.i(TAG, "Monitor externo VÁLIDO detectado: ID=${externalDisplay.displayId}")
                    updateAvailableResolutions(externalDisplay)
                    newStatus = ConnectionStatus.READY_TO_TRANSMIT
                } else {
                    Log.d(TAG, "Nenhum monitor externo válido detectado.")
                    if (isUsbDeviceAttached) {
                         Log.d(TAG, "Adaptador USB detectado, mas sem monitor externo válido.")
                         newStatus = ConnectionStatus.ADAPTER_CONNECTED
                    } else {
                         Log.d(TAG, "Nenhum adaptador USB ou monitor externo válido detectado.")
                         newStatus = ConnectionStatus.DISCONNECTED
                    }
                    _availableResolutions.value = emptyList()
                }

                if (currentStatus != newStatus) {
                    Log.i(TAG, "Mudança de Status: $currentStatus -> $newStatus")
                    _connectionStatus.value = newStatus
                } else {
                    Log.d(TAG, "Status permaneceu: $currentStatus")
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

    // --- Cleanup ---
    fun cleanup() {
        Log.d(TAG, "Limpando ConnectionRepository...")
        unregisterDisplayListener()
        unregisterUsbReceiver()
    }

    // --- Error Handling ---
    private suspend fun emitError(message: String) {
        _errorEvents.emit(message)
    }

    // --- USB Detection ---
    // Versão bloqueante para uso dentro do BroadcastReceiver
    private fun isSpecificAdapterAttachedBlocking(device: UsbDevice?): Boolean {
        if (device == null) return false
        // TODO: Implementar verificação de Vendor ID (VID) e Product ID (PID)
        Log.w(TAG, "Verificação específica do adaptador H'Maston não implementada (VID/PID). Assumindo true para qualquer dispositivo.")
        return true // Assumir true por enquanto para teste
    }
}