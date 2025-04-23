package com.perfectcorp.usb2hdmi.ui.main

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.perfectcorp.usb2hdmi.data.model.ConnectionStatus
import com.perfectcorp.usb2hdmi.data.model.Resolution
import com.perfectcorp.usb2hdmi.data.repository.ConnectionRepository
import com.perfectcorp.usb2hdmi.data.repository.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// Constante para Logging
private const val TAG = "MainViewModel"

/**
 * ViewModel para a MainActivity.
 * Gerencia o estado da UI (UiState) e interage com os repositórios.
 *
 * @param connectionRepository Repositório para dados de conexão e projeção.
 * @param settingsRepository Repositório para configurações persistentes.
 */
// @HiltViewModel // Descomentar se usar Hilt
// class MainViewModel @Inject constructor( // Descomentar se usar Hilt
class MainViewModel( // Remover se usar Hilt
    private val connectionRepository: ConnectionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // Estado interno mutável da UI
    private val _uiState = MutableStateFlow(UiState(isLoading = true))
    // Estado público imutável da UI exposto para a Activity
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Flag para evitar múltiplas solicitações de permissão concorrentes
    private var isRequestingPermission = false

    init {
        Log.d(TAG, "ViewModel inicializado.")
        observeDataChanges()
        observeErrorEvents() // ADICIONADO: Observar eventos de erro
        // TODO: Considerar lógica inicial, como verificar RF013 (último estado)
    }

    /**
     * Combina os fluxos de dados dos repositórios para atualizar o UiState.
     */
    private fun observeDataChanges() {
        viewModelScope.launch {
            combine(
                connectionRepository.connectionStatus,
                connectionRepository.currentResolution,
                connectionRepository.availableResolutions,
                settingsRepository.preferredResolution
                // Não combinar errorEvents aqui, pois são eventos pontuais
            ) { status, currentRes, availableRes, preferredRes ->
                Log.d(TAG, "Dados combinados: Status=$status, CurrentRes=$currentRes, AvailableRes=${availableRes.size}, PreferredRes=$preferredRes")

                // Determinar a resolução selecionada (preferida ou a primeira disponível)
                val selected = preferredRes ?: availableRes.firstOrNull()

                // Atualizar o estado da UI
                _uiState.update { currentState ->
                    // Limpar erro anterior se o status não for mais ERROR
                    val errorMsg = if (status != ConnectionStatus.ERROR) null else currentState.errorMessage

                    currentState.copy(
                        connectionStatus = status,
                        currentResolution = currentRes,
                        availableResolutions = availableRes,
                        selectedResolution = selected,
                        isTransmitting = status == ConnectionStatus.TRANSMITTING,
                        isLoading = false, // Assume não carregando após primeira emissão
                        errorMessage = errorMsg, // Limpa erro se status mudou
                        requiresPermissionRequest = false // Reseta flag de permissão
                    )
                }
            }.catch { e ->
                // Tratar erros na combinação dos fluxos, se necessário
                Log.e(TAG, "Erro ao combinar fluxos de dados", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = "Erro interno ao carregar dados.") }
            }.collect() // Inicia a coleta do fluxo combinado
        }
    }

    /**
     * Observa eventos de erro emitidos pelo ConnectionRepository.
     */
    private fun observeErrorEvents() {
        viewModelScope.launch {
            connectionRepository.errorEvents.collect { errorMessage ->
                Log.e(TAG, "Evento de erro recebido: $errorMessage")
                _uiState.update { it.copy(errorMessage = errorMessage, isLoading = false) } // Define a mensagem de erro e para o loading
            }
        }
    }


    /**
     * Chamado pela UI quando o usuário clica para iniciar a transmissão.
     */
    fun startTransmissionRequest() {
        val currentState = _uiState.value
        if (currentState.isLoading || isRequestingPermission || currentState.connectionStatus != ConnectionStatus.READY_TO_TRANSMIT) {
            Log.w(TAG, "Pedido para iniciar transmissão ignorado: Estado inválido (loading=${currentState.isLoading}, requestingPerm=$isRequestingPermission, status=${currentState.connectionStatus})")
            return
        }

        Log.i(TAG, "Pedido para iniciar transmissão recebido.")
        // TODO: Verificar se a permissão MediaProjection já foi concedida.
        // Se não, atualizar o estado para solicitar permissão.
        // Se sim, chamar connectionRepository.startProjection diretamente.

        // Simulação: Assumir que a permissão é necessária por enquanto
        isRequestingPermission = true
        _uiState.update { it.copy(requiresPermissionRequest = true, isLoading = true) } // Indica que precisa pedir permissão e está carregando
        Log.d(TAG, "Atualizando estado para solicitar permissão.")
    }

    /**
     * Chamado pela UI após o usuário responder ao diálogo de permissão MediaProjection.
     *
     * @param resultCode O resultado da Activity.
     * @param data O Intent com os dados da permissão.
     */
    fun permissionResultReceived(resultCode: Int, data: Intent?) {
        isRequestingPermission = false // Reseta a flag
        _uiState.update { it.copy(isLoading = true, requiresPermissionRequest = false) } // Mantém carregando, reseta flag de pedido

        // TODO: Usar Activity.RESULT_OK em vez de hardcoded -1 (embora geralmente seja -1)
        if (resultCode != -1 || data == null) {
             Log.e(TAG, "Permissão negada ou cancelada (resultCode=$resultCode, dataIsNull=${data == null}).")
             // A mensagem de erro já deve ter sido definida na Activity ao chamar esta função
             // Apenas garantimos que o loading pare e o status seja reavaliado (o que observeDataChanges fará)
             _uiState.update { it.copy(isLoading = false) }
             return
        }

        Log.i(TAG, "Permissão concedida. Iniciando projeção...")
        val targetResolution = _uiState.value.selectedResolution // Usa a resolução selecionada
        connectionRepository.startProjection(resultCode, data, targetResolution)
        // O estado será atualizado pelo observeDataChanges ou observeErrorEvents
    }

    /**
     * Chamado pela UI quando o usuário clica para parar a transmissão.
     */
    fun stopTransmissionRequest() {
        val currentState = _uiState.value
        if (currentState.isLoading || currentState.connectionStatus != ConnectionStatus.TRANSMITTING) {
             Log.w(TAG, "Pedido para parar transmissão ignorado: Estado inválido (loading=${currentState.isLoading}, status=${currentState.connectionStatus})")
            return
        }

        Log.i(TAG, "Pedido para parar transmissão recebido.")
        _uiState.update { it.copy(isLoading = true) } // Indica carregamento
        connectionRepository.stopProjection()
        // O estado será atualizado pelo observeDataChanges
    }

    /**
     * Chamado pela UI quando o usuário seleciona uma nova resolução.
     * Salva a preferência e, se estiver transmitindo, pode reiniciar a projeção (opcional).
     */
    fun updateSelectedResolution(resolution: Resolution) {
        val currentState = _uiState.value
        if (currentState.selectedResolution == resolution) return // Sem mudança

        Log.i(TAG, "Nova resolução selecionada: $resolution")
        _uiState.update { it.copy(selectedResolution = resolution) }

        viewModelScope.launch {
            settingsRepository.savePreferredResolution(resolution)
            // TODO: Decidir se deve reiniciar a projeção automaticamente ao mudar a resolução
        }
    }

    /**
     * Limpa a mensagem de erro atual no estado da UI. Chamado pela UI após exibir o erro.
     */
    fun clearError() {
        // Apenas limpa se houver uma mensagem de erro atualmente
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
        connectionRepository.cleanup() // Delega a limpeza para o repositório
    }
}