package com.perfectcorp.usb2hdmi.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager // Import necessário para getSystemService
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
            if (result.resultCode == Activity.RESULT_OK) {
                // Permissão concedida, repassar resultado para ViewModel
                result.data?.let { intent ->
                    viewModel.permissionResultReceived(result.resultCode, intent)
                } ?: run {
                    // Caso raro: resultado OK mas Intent nulo? Tratar como erro.
                    Log.e(TAG, "Resultado da permissão OK, mas Intent é nulo.")
                    viewModel.permissionResultReceived(Activity.RESULT_CANCELED, null) // Informa VM sobre falha
                    showErrorSnackbar(getString(R.string.error_failed_to_get_permission)) // Usar string resource
                }
            } else {
                // Permissão negada ou cancelada pelo usuário
                Log.w(TAG, "Permissão MediaProjection negada ou cancelada.")
                viewModel.permissionResultReceived(result.resultCode, null) // Informa VM sobre falha
                showErrorSnackbar(getString(R.string.error_permission_needed)) // Usar string resource
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate chamado")

        // Inicializar dependências (SUBSTITUIR POR HILT/DI)
        connectionRepository = ConnectionRepository(applicationContext, applicationScope)
        settingsRepository = SettingsRepository(applicationContext)
        // Factory manual para ViewModel
        val viewModelFactory = MainViewModelFactory(connectionRepository, settingsRepository)
        viewModel = ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]


        // Inflar layout usando ViewBinding (preferível)
        // binding = ActivityMainBinding.inflate(layoutInflater)
        // setContentView(binding.root)

        // Inflar layout usando findViewById (tradicional) - Requer activity_main.xml
        setContentView(R.layout.activity_main) // Certifique-se que R foi importado corretamente

        setupUIListeners() // Configurar listeners para botões, spinners, etc.
        observeUiState() // Começar a observar o estado do ViewModel
    }

    private fun setupUIListeners() {
        Log.d(TAG, "Configurando listeners da UI...")
        // Exemplo com ViewBinding:
        // binding.buttonAction.setOnClickListener { handleActionButtonClick() }
        // binding.spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { ... }

        // Exemplo com findViewById (requer IDs no XML):
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

        // Listener para limpar erro ao clicar no Snackbar (exemplo)
        // (A implementação do Snackbar está em showErrorSnackbar)
    }

    private fun handleActionButtonClick() {
        val status = viewModel.uiState.value.connectionStatus
        Log.d(TAG, "Botão de ação clicado. Status atual: $status")
        when (status) {
            ConnectionStatus.READY_TO_TRANSMIT -> viewModel.startTransmissionRequest()
            ConnectionStatus.TRANSMITTING -> viewModel.stopTransmissionRequest()
            else -> Log.w(TAG, "Clique no botão de ação em estado inesperado: $status")
        }
    }

    private fun observeUiState() {
        Log.d(TAG, "Iniciando observação do UiState...")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    Log.v(TAG, "Novo UiState recebido: $state") // Log verboso para debug
                    updateUi(state)

                    // Lançar pedido de permissão se necessário
                    if (state.requiresPermissionRequest) {
                        launchMediaProjectionRequest()
                    }

                    // Mostrar erro se houver
                    state.errorMessage?.let { message ->
                        // Usar string resource se for uma chave, senão usar a mensagem direta
                        val displayMessage = try { getString(resources.getIdentifier(message, "string", packageName)) } catch (e: Exception) { message }
                        showErrorSnackbar(displayMessage)
                        viewModel.clearError() // Limpa o erro após mostrar
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
        // Atualizar ícone de status
        val statusIconResId = when (state.connectionStatus) {
            ConnectionStatus.DISCONNECTED -> R.drawable.ic_baseline_link_off_24
            ConnectionStatus.ADAPTER_CONNECTED -> R.drawable.ic_baseline_usb_24
            ConnectionStatus.READY_TO_TRANSMIT -> R.drawable.ic_baseline_cast_connected_24 // Usar cast_connected para pronto também? Ou um ícone 'cast'?
            ConnectionStatus.TRANSMITTING -> R.drawable.ic_baseline_cast_connected_24
            ConnectionStatus.ERROR -> R.drawable.ic_baseline_error_24
        }
        findViewById<ImageView>(R.id.imageViewStatusIcon)?.setImageResource(statusIconResId)


        // --- Atualizar Informações da Tela Externa ---
        val showExternalInfo = state.isTransmitting && state.currentResolution != null
        findViewById<View>(R.id.layoutExternalInfo)?.isVisible = showExternalInfo
        if (showExternalInfo) {
            findViewById<android.widget.TextView>(R.id.textViewCurrentResolution)?.text = state.currentResolution.toString()
        }

        // --- Atualizar Botão de Ação ---
        findViewById<android.widget.Button>(R.id.buttonAction)?.apply {
            isEnabled = state.isActionButtonEnabled
            text = if (state.isTransmitting) getString(R.string.action_stop_transmission) else getString(R.string.action_start_transmission)
        }


        // --- Atualizar Seletor de Resolução ---
        val showSelector = state.showResolutionSelector
        findViewById<View>(R.id.textViewResolutionLabel)?.isVisible = showSelector // Mostrar/ocultar label também
        findViewById<android.widget.Spinner>(R.id.spinnerResolution)?.isVisible = showSelector
        if (showSelector) {
            updateResolutionSpinner(state.availableResolutions, state.selectedResolution)
        }

        // --- Atualizar Indicador de Loading ---
        findViewById<android.widget.ProgressBar>(R.id.progressBar)?.isVisible = state.isLoading

        // --- Atualizar Mensagem de Texto Auxiliar (Opcional) ---
        val helperMessage = getHelperMessage(state.connectionStatus) // Obter mensagem auxiliar
        findViewById<android.widget.TextView>(R.id.textViewMessage)?.apply {
            text = helperMessage
            isVisible = !state.isLoading && state.errorMessage == null && helperMessage.isNotEmpty() // Mostrar se não estiver carregando, sem erro e com mensagem
        }
    }

    /**
     * Atualiza o conteúdo e a seleção do Spinner de resoluções.
     */
    private fun updateResolutionSpinner(resolutions: List<Resolution>, selectedResolution: Resolution?) {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinnerResolution) ?: return
        // Usar um ArrayAdapter simples para exibir o toString() da Resolution
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter

        // Definir a seleção atual
        selectedResolution?.let { selected ->
            val position = resolutions.indexOf(selected)
            if (position >= 0 && spinner.selectedItemPosition != position) {
                 Log.d(TAG, "Definindo seleção do Spinner para: $selected (posição $position)")
                 spinner.setSelection(position, false) // false para não disparar onItemSelected
            }
        }
    }


    /**
     * Lança o Intent para solicitar a permissão MediaProjection.
     */
    private fun launchMediaProjectionRequest() {
        Log.i(TAG, "Lançando solicitação de permissão MediaProjection...")
        try {
            // Obter o intent do repositório para garantir consistência
            val intent = connectionRepository.createScreenCaptureIntent()
             mediaProjectionPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao tentar lançar MediaProjection Intent", e)
            showErrorSnackbar(getString(R.string.error_failed_start_projection_request)) // Usar string resource
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
             // A limpeza do repositório é chamada no onCleared do ViewModel
        }
    }
}

// Factory manual para MainViewModel (SUBSTITUIR POR HILT/DI)
class MainViewModelFactory(
    private val connectionRepository: ConnectionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(connectionRepository, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}