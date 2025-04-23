package com.perfectcorp.usb2hdmi.ui.main

import com.perfectcorp.usb2hdmi.data.model.ConnectionStatus
import com.perfectcorp.usb2hdmi.data.model.Resolution

/**
 * Representa o estado completo da interface do usuário para a tela principal.
 * Esta classe é imutável; novas instâncias são criadas para cada mudança de estado.
 *
 * @property connectionStatus O estado atual da conexão detectada.
 * @property currentResolution A resolução ativa na tela externa (null se não estiver transmitindo).
 * @property availableResolutions Lista de resoluções suportadas pela tela externa e adaptador.
 * @property selectedResolution A resolução que o usuário selecionou (pode ser diferente da atual se a transmissão não foi reiniciada).
 * @property isTransmitting Indica se a projeção de mídia está ativa. (Derivável de connectionStatus, mas explícito para clareza na UI).
 * @property isLoading Indica se alguma operação assíncrona está em andamento (ex: conectando, buscando resoluções).
 * @property errorMessage Mensagem de erro a ser exibida ao usuário (null se não houver erro).
 * @property requiresPermissionRequest Indica se uma solicitação de permissão MediaProjection é necessária.
 */
data class UiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val currentResolution: Resolution? = null,
    val availableResolutions: List<Resolution> = emptyList(),
    val selectedResolution: Resolution? = null, // Pode ser útil para manter a seleção do usuário
    val isTransmitting: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val requiresPermissionRequest: Boolean = false // Para sinalizar a necessidade de pedir permissão
) {
    /** Retorna true se o botão de iniciar/parar deve estar habilitado. */
    val isActionButtonEnabled: Boolean
        get() = !isLoading && (connectionStatus == ConnectionStatus.READY_TO_TRANSMIT || connectionStatus == ConnectionStatus.TRANSMITTING)

    /** Retorna true se a seção de seleção de resolução deve ser visível. */
    val showResolutionSelector: Boolean
        get() = !isLoading && availableResolutions.size > 1 &&
                (connectionStatus == ConnectionStatus.READY_TO_TRANSMIT || connectionStatus == ConnectionStatus.TRANSMITTING)
}