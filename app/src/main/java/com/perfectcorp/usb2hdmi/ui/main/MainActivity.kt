package com.perfectcorp.usb2hdmi.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager // Import necessário
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider // Usar para instanciação manual
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.perfectcorp.usb2hdmi.R // Necessário para R.layout.activity_main etc.
import com.perfectcorp.usb2hdmi.data.model.ConnectionStatus
import com.perfectcorp.usb2hdmi.data.model.Resolution
import com.perfectcorp.usb2hdmi.data.repository.ConnectionRepository
import com.perfectcorp.usb2hdmi.data.repository.SettingsRepository
// Importar ViewBinding se for usar
// import com.perfectcorp.usb2hdmi.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Constante para Logging
private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    // Instanciação manual do ViewModel e dependências (SUBSTITUIR POR HILT/DI)
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main) // Escopo para repositórios
    private lateinit var connectionRepository: ConnectionRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: MainViewModel

    // ViewBinding (alternativa ao findViewById)
    // private lateinit var binding: ActivityMainBinding

    // ActivityResultLauncher para a permissão MediaProjection
    private val mediaProjectionPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Log.d(TAG, "Resultado da permissão MediaProjection recebido: resultCode=${result.resultCode}")
            // Usar Activity.RESULT_OK
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { intent ->
                    viewModel.permissionResultReceived(result.resultCode, intent)
                } ?: run {
                    Log.e(TAG, "Resultado da permissão OK, mas Intent é nulo.")
                    viewModel.permissionResultReceived(Activity.RESULT_CANCELED, null)
                    showErrorSnackbar(getString(R.string.error_failed_to_get_permission))
                }
            } else {
                Log.w(TAG, "Permissão MediaProjection negada ou cancelada.")
                viewModel.permissionResultReceived(result.resultCode, null)
                showErrorSnackbar(getString(R.string.error_permission_needed))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate chamado")

        // Inicializar dependências (SUBSTITUIR POR HILT/DI)
        connectionRepository = ConnectionRepository(applicationContext, applicationScope)
        settingsRepository = SettingsRepository(applicationContext)
        // Factory manual para ViewModel - Passar applicationContext
        val viewModelFactory = MainViewModelFactory(applicationContext, connectionRepository, settingsRepository)
        viewModel = ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]


        // Inflar layout
        setContentView(R.layout.activity_main)

        setupUIListeners()
        observeUiState()
    }

    private fun setupUIListeners() {
        Log.d(TAG, "Configurando listeners da UI...")
        findViewById<View>(R.id.buttonAction)?.setOnClickListener { handleActionButtonClick() }
        findViewById<android.widget.Spinner>(R.id.spinnerResolution)?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem = parent?.getItemAtPosition(position)
                if (selectedItem is Resolution) {
                    Log.d(TAG, "Seleção no Spinner: $selectedItem")
                    viewModel.updateSelectedResolution(selectedItem)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* Não fazer nada */ }
        }
    }

    private fun handleActionButtonClick() {
        val uiState = viewModel.uiState.value // Obter estado atual
        Log.d(TAG, "Botão de ação clicado. Status: ${uiState.connectionStatus}, isTransmitting (VM): ${uiState.isTransmitting}")

        if (uiState.isTransmitting) {
            // Se o ViewModel acha que está transmitindo, tentamos parar
            viewModel.stopTransmissionRequest()
        } else if (uiState.connectionStatus == ConnectionStatus.READY_TO_TRANSMIT) {
            // Se não está transmitindo mas está pronto, tentamos iniciar
            viewModel.startTransmissionRequest()
        } else {
            Log.w(TAG, "Clique no botão de ação em estado inesperado: ${uiState.connectionStatus}")
        }
    }


    private fun observeUiState() {
        Log.d(TAG, "Iniciando observação do UiState...")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    Log.v(TAG, "Novo UiState recebido: $state")
                    updateUi(state)

                    if (state.requiresPermissionRequest) {
                        launchMediaProjectionRequest()
                    }

                    state.errorMessage?.let { message ->
                        val displayMessage = try { getString(resources.getIdentifier(message, "string", packageName)) } catch (e: Exception) { message }
                        showErrorSnackbar(displayMessage)
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    /**
     * Atualiza a interface do usuário com base no UiState recebido.
     */
    private fun updateUi(state: UiState) {
        // --- Atualizar Indicador de Status ---
        findViewById<android.widget.TextView>(R.id.textViewStatus)?.text = getStatusString(state.connectionStatus)
        val statusIconResId = when (state.connectionStatus) {
            ConnectionStatus.DISCONNECTED -> R.drawable.ic_baseline_link_off_24
            ConnectionStatus.ADAPTER_CONNECTED -> R.drawable.ic_baseline_usb_24
            ConnectionStatus.READY_TO_TRANSMIT -> R.drawable.ic_baseline_cast_connected_24 // Ícone de pronto
            ConnectionStatus.TRANSMITTING -> R.drawable.ic_baseline_cast_connected_24 // Mesmo ícone para transmitindo?
            ConnectionStatus.ERROR -> R.drawable.ic_baseline_error_24
        }
        findViewById<ImageView>(R.id.imageViewStatusIcon)?.setImageResource(statusIconResId)


        // --- Atualizar Informações da Tela Externa ---
        findViewById<View>(R.id.layoutExternalInfo)?.isVisible = false // Oculto por enquanto

        // --- Atualizar Botão de Ação ---
        findViewById<android.widget.Button>(R.id.buttonAction)?.apply {
            isEnabled = state.connectionStatus == ConnectionStatus.READY_TO_TRANSMIT || state.isTransmitting
            text = if (state.isTransmitting) getString(R.string.action_stop_transmission) else getString(R.string.action_start_transmission)
        }


        // --- Atualizar Seletor de Resolução ---
        val showSelector = state.availableResolutions.isNotEmpty() &&
                           (state.connectionStatus == ConnectionStatus.READY_TO_TRANSMIT || state.isTransmitting)
        findViewById<View>(R.id.textViewResolutionLabel)?.isVisible = showSelector
        findViewById<android.widget.Spinner>(R.id.spinnerResolution)?.isVisible = showSelector
        if (showSelector) {
            updateResolutionSpinner(state.availableResolutions, state.selectedResolution)
        }

        // --- Atualizar Indicador de Loading ---
        findViewById<android.widget.ProgressBar>(R.id.progressBar)?.isVisible = state.isLoading

        // --- Atualizar Mensagem de Texto Auxiliar ---
        val helperMessage = getHelperMessage(state.connectionStatus)
        findViewById<android.widget.TextView>(R.id.textViewMessage)?.apply {
            text = helperMessage
            isVisible = !state.isLoading && state.errorMessage == null && helperMessage.isNotEmpty()
        }
    }

    /**
     * Atualiza o conteúdo e a seleção do Spinner de resoluções.
     */
    private fun updateResolutionSpinner(resolutions: List<Resolution>, selectedResolution: Resolution?) {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerResolution) ?: return
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter

        selectedResolution?.let { selected ->
            val position = resolutions.indexOf(selected)
            if (position >= 0 && spinner.selectedItemPosition != position) {
                 Log.d(TAG, "Definindo seleção do Spinner para: $selected (posição $position)")
                 spinner.setSelection(position, false)
            }
        }
    }


    /**
     * Lança o Intent para solicitar a permissão MediaProjection.
     */
    private fun launchMediaProjectionRequest() {
        Log.i(TAG, "Lançando solicitação de permissão MediaProjection...")
        try {
            // CORRIGIDO: Obter o manager e criar o intent aqui
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mediaProjectionManager.createScreenCaptureIntent()
             mediaProjectionPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao tentar lançar MediaProjection Intent", e)
            showErrorSnackbar(getString(R.string.error_failed_start_projection_request))
            viewModel.permissionResultReceived(Activity.RESULT_CANCELED, null) // Simula cancelamento
        }
    }

    /**
     * Exibe uma mensagem de erro usando Snackbar.
     */
    private fun showErrorSnackbar(message: String) {
        val rootView = findViewById<View>(android.R.id.content)
        if (rootView != null) {
            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Retorna a string de status apropriada para exibição na UI.
     */
    private fun getStatusString(status: ConnectionStatus): String {
        return when (status) {
            ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected)
            ConnectionStatus.ADAPTER_CONNECTED -> getString(R.string.status_adapter_connected)
            ConnectionStatus.READY_TO_TRANSMIT -> getString(R.string.status_ready_to_transmit)
            ConnectionStatus.TRANSMITTING -> getString(R.string.status_transmitting)
            ConnectionStatus.ERROR -> getString(R.string.status_error)
        }
    }

     /**
     * Retorna uma mensagem auxiliar baseada no status (opcional).
     */
     private fun getHelperMessage(status: ConnectionStatus): String {
         return when (status) {
             ConnectionStatus.DISCONNECTED -> getString(R.string.message_connect_adapter)
             ConnectionStatus.ADAPTER_CONNECTED -> getString(R.string.message_connect_hdmi)
             else -> "" // Nenhuma mensagem para outros estados
         }
     }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy chamado")
        if (!isChangingConfigurations) {
             Log.d(TAG, "Cancelando applicationScope...")
             applicationScope.cancel()
             // ViewModel limpa o repositório
        }
    }
}

// Factory manual para MainViewModel (SUBSTITUIR POR HILT/DI)
class MainViewModelFactory(
    private val context: Context, // Adicionado contexto
    private val connectionRepository: ConnectionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            // Passar contexto para o ViewModel
            return MainViewModel(context, connectionRepository, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}