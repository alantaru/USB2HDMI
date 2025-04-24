package com.perfectcorp.usb2hdmi.data.repository

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbConstants // Import necessário
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.perfectcorp.usb2hdmi.R
import com.perfectcorp.usb2hdmi.data.model.ConnectionStatus
import com.perfectcorp.usb2hdmi.data.model.Resolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Constante para Logging
private const val TAG = "ConnectionRepository"
private const val DEFAULT_DISPLAY_ID = 0 // WORKAROUND
private const val POLLING_INTERVAL_MS = 3000L

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
    private var pollingJob: Job? = null

    init {
        registerDisplayListener()
        registerUsbReceiver()
        observeConnectionStatusForPolling()
        updateConnectionStatus()
    }

    // --- Display Listener Logic ---
    private fun registerDisplayListener() {
        if (displayListener != null) return
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                Log.i(TAG, "*** DisplayListener: onDisplayAdded(displayId=$displayId)")
                updateConnectionStatus()
            }
            override fun onDisplayRemoved(displayId: Int) {
                Log.i(TAG, "*** DisplayListener: onDisplayRemoved(displayId=$displayId)")
                updateConnectionStatus()
            }
            override fun onDisplayChanged(displayId: Int) {
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
                Log.i(TAG, "*** UsbReceiver: onReceive(action=$action, deviceName=${device?.deviceName}, VID=${device?.vendorId}, PID=${device?.productId})")

                device?.let { logUsbDeviceDetails(it) }

                var needsStatusUpdate = false
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (isSpecificAdapterAttachedBlocking(device)) {
                            Log.i(TAG, "Adaptador USB específico (ou genérico) conectado.")
                            if (!isUsbDeviceAttached) {
                                isUsbDeviceAttached = true
                                needsStatusUpdate = true
                            }
                            needsStatusUpdate = true // Sempre reavaliar
                        } else {
                            Log.d(TAG, "Outro dispositivo USB conectado, ignorando.")
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (isSpecificAdapterAttachedBlocking(device)) {
                             Log.i(TAG, "Adaptador USB específico (ou genérico) desconectado.")
                             if (isUsbDeviceAttached) {
                                 isUsbDeviceAttached = false
                                 needsStatusUpdate = true
                             }
                        } else {
                             Log.d(TAG, "Outro dispositivo USB desconectado, ignorando.")
                        }
                    }
                }
                if (needsStatusUpdate) {
                    updateConnectionStatus()
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
             Log.d(TAG, "Verificando ${deviceList.size} dispositivos USB iniciais...")
             deviceList.values.forEach { logUsbDeviceDetails(it) }
             val adapterAttached = deviceList.values.any { isSpecificAdapterAttachedBlocking(it) }
             var needsUpdate = false
             if (adapterAttached != isUsbDeviceAttached) {
                 isUsbDeviceAttached = adapterAttached
                 needsUpdate = true
             }
             Log.d(TAG, "Estado inicial USB verificado: isUsbDeviceAttached=$isUsbDeviceAttached")
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

    // --- Connection Status Update Logic ---
    private fun updateConnectionStatus() {
        externalScope.launch(Dispatchers.IO) {
            Log.i(TAG, "==> updateConnectionStatus INICIADO. isUsbAttached=$isUsbDeviceAttached")
            try {
                val displays = displayManager.displays
                Log.i(TAG, "------ Displays Atuais (${displays.size}) ------")
                var foundPotentialExternal = false
                var potentialExternalDisplay: Display? = null

                displays.forEach { display ->
                    Log.i(TAG, "  Display ID: ${display.displayId}")
                    Log.i(TAG, "    Name: ${display.name}")
                    Log.i(TAG, "    isValid: ${display.isValid}")
                    try {
                        val mode = display.mode
                        Log.i(TAG, "    Mode: ${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate.toInt()}Hz (Mode ID: ${mode.modeId})")
                    } catch (e: Exception) {
                        Log.w(TAG, "    Mode: Erro ao obter modo (${e.message})")
                    }
                    if (display.displayId != DEFAULT_DISPLAY_ID && display.isValid) {
                        Log.i(TAG,"    >> Potencial display externo encontrado (ID != 0 e isValid=true)")
                        foundPotentialExternal = true
                        potentialExternalDisplay = display
                    }
                }
                Log.i(TAG, "------------------------------------")

                val currentStatus = _connectionStatus.value
                val newStatus: ConnectionStatus

                if (foundPotentialExternal) {
                    Log.i(TAG, "Conclusão: Monitor externo POTENCIALMENTE detectado.")
                    // CORRIGIDO: Usar ?.let para chamada segura
                    potentialExternalDisplay?.let { updateAvailableResolutions(it) }
                    newStatus = ConnectionStatus.READY_TO_TRANSMIT
                } else {
                    Log.d(TAG, "Nenhum monitor externo válido detectado na verificação.")
                    if (isUsbDeviceAttached) {
                         Log.d(TAG, "Adaptador USB detectado, mas sem monitor externo válido.")
                         newStatus = ConnectionStatus.ADAPTER_CONNECTED
                    } else {
                         Log.d(TAG, "Nenhum adaptador USB ou monitor externo válido detectado.")
                         newStatus = ConnectionStatus.DISCONNECTED
                    }
                    if (_availableResolutions.value.isNotEmpty()) {
                        _availableResolutions.value = emptyList()
                    }
                }

                if (currentStatus != newStatus) {
                    Log.i(TAG, ">>> Mudança de Status: $currentStatus -> $newStatus <<<")
                    _connectionStatus.value = newStatus
                } else {
                    Log.d(TAG, "Status permaneceu: $currentStatus")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro CRÍTICO ao atualizar status da conexão", e)
                _connectionStatus.value = ConnectionStatus.ERROR
                emitError(context.getString(R.string.error_unknown_connection))
            }
        }
    }

    // --- Polling Logic ---
    private fun observeConnectionStatusForPolling() {
        connectionStatus
            .onEach { status ->
                if (status == ConnectionStatus.ADAPTER_CONNECTED) {
                    startPollingForDisplay()
                } else {
                    stopPollingForDisplay()
                }
            }
            .launchIn(externalScope)
    }

    private fun startPollingForDisplay() {
        if (pollingJob?.isActive == true) return
        Log.i(TAG, "Iniciando polling para display externo (intervalo ${POLLING_INTERVAL_MS}ms)...")
        pollingJob = externalScope.launch(Dispatchers.IO) {
            while (isActive && _connectionStatus.value == ConnectionStatus.ADAPTER_CONNECTED) {
                Log.d(TAG, "Polling: Verificando displays...")
                updateConnectionStatus()
                delay(POLLING_INTERVAL_MS)
            }
            Log.i(TAG, "Polling para display externo parado (status mudou ou job cancelado).")
        }
    }

    private fun stopPollingForDisplay() {
        if (pollingJob?.isActive == true) {
            Log.i(TAG, "Cancelando polling para display externo.")
            pollingJob?.cancel()
        }
        pollingJob = null
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
        }
    }

    // --- Cleanup ---
    fun cleanup() {
        Log.d(TAG, "Limpando ConnectionRepository...")
        stopPollingForDisplay()
        unregisterDisplayListener()
        unregisterUsbReceiver()
    }

    // --- Error Handling ---
    private suspend fun emitError(message: String) {
        _errorEvents.emit(message)
    }

    // --- USB Detection ---
    private fun isSpecificAdapterAttachedBlocking(device: UsbDevice?): Boolean {
        if (device == null) return false
        // TODO: Implementar verificação de Vendor ID (VID) e Product ID (PID)
        Log.w(TAG, "Verificação específica do adaptador H'Maston não implementada (VID/PID). Assumindo true para qualquer dispositivo.")
        return true // Assumir true por enquanto para teste
    }

    // --- USB Logging ---
    private fun logUsbDeviceDetails(device: UsbDevice) {
        Log.d(TAG, "  Detalhes USB Device: ${device.deviceName}")
        Log.d(TAG, "    VID: ${device.vendorId}, PID: ${device.productId}")
        // CORRIGIDO: Usar UsbConstants.classToString
        Log.d(TAG, "    Class: ${UsbConstants.classToString(device.deviceClass)}, Subclass: ${device.deviceSubclass}, Protocol: ${device.deviceProtocol}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // CORRIGIDO: Usar UsbConstants.classToString
            Log.d(TAG, "    Interface $i: ID=${iface.id}, Class=${UsbConstants.classToString(iface.interfaceClass)}, Subclass=${iface.interfaceSubclass}, Protocol=${iface.interfaceProtocol}")
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                // CORRIGIDO: Usar UsbConstants.endpointTypeToString e endpointDirectionToString
                Log.d(TAG, "      Endpoint $j: Address=0x${Integer.toHexString(endpoint.address)}, Type=${UsbConstants.endpointTypeToString(endpoint.type)}, Direction=${UsbConstants.endpointDirectionToString(endpoint.direction)}")
            }
        }
    }
}