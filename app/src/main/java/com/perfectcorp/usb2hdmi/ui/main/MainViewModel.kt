package com.perfectcorp.usb2hdmi.ui.main

import android.content.Context // Necessário para iniciar serviço
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectcorp.usb2hdmi.data.model.ConnectionStatus
import com.perfectcorp.usb2hdmi.data.model.Resolution
import com.perfectcorp.usb2hdmi.data.repository.ConnectionRepository
import com.perfectcorp.usb2hdmi.data.repository.SettingsRepository
import com.perfectcorp.usb2hdmi.service.ProjectionService // Importar o serviço
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// Constante para Logging
private const val TAG = "MainViewModel"

/**
 * ViewModel para a MainActivity.
 * Gerencia o estado da UI (UiState), interage com os repositórios para dados de estado
 * e comanda o ProjectionService para iniciar/parar a transmissão.
 *
 * @param context Contexto da aplicação (necessário para iniciar o serviço).
 * @param connectionRepository Repositório para dados de estado da conexão.
 * @param settingsRepository Repositório para configurações persistentes.
 */
// @HiltViewModel // Descomentar se usar Hilt
// class MainViewModel @Inject constructor( // Descomentar se usar Hilt
//    @ApplicationContext private val context: Context, // Injetar contexto com Hilt
class MainViewModel( // Remover se usar Hilt
    private val context: Context, // Passar contexto manualmente (NÃO IDEAL, usar DI)
    private val connectionRepository: ConnectionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState(isLoading = true))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var isRequestingPermission = false
    // Flag para rastrear se o serviço de projeção está ativo (pode ser melhorado com comunicação bidirecional)
    private var isProjectionServiceRunning = false

    init {
        Log.d(TAG, "ViewModel inicializado.")
        observeDataChanges()
        observeErrorEvents()
        // TODO: Verificar estado inicial do serviço? Ou assumir que não está rodando?
    }

    /**
     * Combina os fluxos de dados dos repositórios para atualizar o UiState.
     */
    private fun observeDataChanges() {
        viewModelScope.launch {
            combine(
                connectionRepository.connectionStatus,
                // connectionRepository.currentResolution, // Removido
                connectionRepository.availableResolutions,
                settingsRepository.preferredResolution
            ) { status, availableRes, preferredRes -> // Removido currentRes
                Log.d(TAG, "Dados combinados: Status=$status, AvailableRes=${availableRes.size}, PreferredRes=$preferredRes")

                val selected = preferredRes ?: availableRes.firstOrNull()

                // Inferir isTransmitting baseado no status READY e se o serviço está rodando
                // Isso é uma simplificação, idealmente o serviço comunicaria seu estado real.
                val isTransmittingNow = status == ConnectionStatus.READY_TO_TRANSMIT && isProjectionServiceRunning

                _uiState.update { currentState ->
                    val errorMsg = if (status != ConnectionStatus.ERROR) null else currentState.errorMessage

                    currentState.copy(
                        connectionStatus = status,
                        // currentResolution = null, // Não temos mais essa info aqui diretamente
                        availableResolutions = availableRes,
                        selectedResolution = selected,
                        isTransmitting = isTransmittingNow, // Atualizado
                        isLoading = false,
                        errorMessage = errorMsg,
                        requiresPermissionRequest = false
                    )
                }
            }.catch { e ->
                Log.e(TAG, "Erro ao combinar fluxos de dados", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = "Erro interno ao carregar dados.") }
            }.collect()
        }
    }

    /**
     * Observa eventos de erro emitidos pelo ConnectionRepository.
     */
    private fun observeErrorEvents() {
        viewModelScope.launch {
            connectionRepository.errorEvents.collect { errorMessage ->
                Log.e(TAG, "Evento de erro recebido: $errorMessage")
                // Se receber erro, garantir que o estado de transmissão seja falso
                isProjectionServiceRunning = false
                _uiState.update { it.copy(errorMessage = errorMessage, isLoading = false, isTransmitting = false) }
            }
        }
    }


    /**
     * Chamado pela UI para iniciar a transmissão. Pede permissão se necessário,
     * senão, inicia o ProjectionService.
     */
    fun startTransmissionRequest() {
        val currentState = _uiState.value
        // Agora verificamos READY_TO_TRANSMIT, pois o serviço que muda para TRANSMITTING
        if (currentState.isLoading || isRequestingPermission || currentState.connectionStatus != ConnectionStatus.READY_TO_TRANSMIT) {
            Log.w(TAG, "Pedido para iniciar transmissão ignorado: Estado inválido (loading=${currentState.isLoading}, requestingPerm=$isRequestingPermission, status=${currentState.connectionStatus})")
            return
        }

        Log.i(TAG, "Pedido para iniciar transmissão recebido.")
        // TODO: Verificar permissão MediaProjection de forma mais robusta se possível
        // Por enquanto, sempre pedimos via Activity
        isRequestingPermission = true
        _uiState.update { it.copy(requiresPermissionRequest = true, isLoading = true) }
        Log.d(TAG, "Atualizando estado para solicitar permissão.")
    }

    /**
     * Chamado pela UI após o usuário responder ao diálogo de permissão MediaProjection.
     * Inicia o ProjectionService se a permissão foi concedida.
     */
    fun permissionResultReceived(resultCode: Int, data: Intent?) {
        isRequestingPermission = false
        _uiState.update { it.copy(isLoading = true, requiresPermissionRequest = false) }

        // TODO: Usar Activity.RESULT_OK
        if (resultCode != -1 || data == null) {
             Log.e(TAG, "Permissão negada ou cancelada.")
             _uiState.update { it.copy(isLoading = false) } // Mensagem de erro já definida na Activity
             return
        }

        Log.i(TAG, "Permissão concedida. Iniciando ProjectionService...")
        val targetResolution = _uiState.value.selectedResolution
        val serviceIntent = Intent(context, ProjectionService::class.java).apply {
            action = ProjectionService.ACTION_START
            putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ProjectionService.EXTRA_RESULT_DATA, data)
            putExtra(ProjectionService.EXTRA_TARGET_RESOLUTION, targetResolution) // Passar resolução
        }
        // Iniciar como Foreground Service
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        isProjectionServiceRunning = true // Assume que iniciou (simplificação)
        // Atualizar UI para refletir que está tentando transmitir (loading)
        // O status real virá do observeDataChanges quando o display for detectado
         _uiState.update { it.copy(isLoading = false, isTransmitting = true) } // Otimista
    }

    /**
     * Chamado pela UI para parar a transmissão. Para o ProjectionService.
     */
    fun stopTransmissionRequest() {
        val currentState = _uiState.value
        // Parar se o serviço estiver rodando (ou se achamos que está)
        if (currentState.isLoading || !isProjectionServiceRunning) {
             Log.w(TAG, "Pedido para parar transmissão ignorado: Estado inválido (loading=${currentState.isLoading}, serviceRunning=${isProjectionServiceRunning})")
            return
        }

        Log.i(TAG, "Pedido para parar transmissão recebido. Parando ProjectionService...")
        _uiState.update { it.copy(isLoading = true) } // Indica carregamento
        val serviceIntent = Intent(context, ProjectionService::class.java).apply {
            action = ProjectionService.ACTION_STOP
        }
        context.startService(serviceIntent) // Envia comando para parar
        isProjectionServiceRunning = false // Assume que parou (simplificação)
        // O estado será atualizado pelo observeDataChanges
        _uiState.update { it.copy(isLoading = false, isTransmitting = false) } // Otimista
    }

    /**
     * Chamado pela UI quando o usuário seleciona uma nova resolução.
     */
    fun updateSelectedResolution(resolution: Resolution) {
        val currentState = _uiState.value
        if (currentState.selectedResolution == resolution) return

        Log.i(TAG, "Nova resolução selecionada: $resolution")
        _uiState.update { it.copy(selectedResolution = resolution) }

        viewModelScope.launch {
            settingsRepository.savePreferredResolution(resolution)
            // Se estiver transmitindo, precisa parar e iniciar o serviço novamente com a nova resolução
            if (isProjectionServiceRunning) {
                 Log.d(TAG, "Resolução alterada durante transmissão. Reiniciando serviço (requer nova permissão!).")
                 // Parar o serviço atual
                 stopTransmissionRequest()
                 // Indicar que precisa de permissão novamente para reiniciar
                 // Isso é complexo, pois a Activity precisa pedir permissão de novo.
                 // Talvez seja melhor apenas aplicar na próxima vez.
                 // Por ora, apenas paramos.
                 _uiState.update { it.copy(errorMessage = "Selecione 'Iniciar Transmissão' novamente para aplicar a nova resolução.") }
            }
        }
    }

    /**
     * Limpa a mensagem de erro atual no estado da UI.
     */
    fun clearError() {
        if (_uiState.value.errorMessage != null) {
             Log.d(TAG, "Limpando mensagem de erro.")
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    /**
     * Chamado quando o ViewModel está prestes a ser destruído.
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel destruído. Limpando recursos...")
        connectionRepository.cleanup()
        // Parar o serviço se ainda estiver rodando? Boa prática.
        if (isProjectionServiceRunning) {
             Log.w(TAG, "ViewModel destruído, parando ProjectionService.")
             val serviceIntent = Intent(context, ProjectionService::class.java).apply {
                 action = ProjectionService.ACTION_STOP
             }
             context.startService(serviceIntent)
        }
    }
}