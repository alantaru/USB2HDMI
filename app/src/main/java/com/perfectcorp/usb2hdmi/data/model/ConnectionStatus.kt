package com.perfectcorp.usb2hdmi.data.model

/**
 * Representa os possíveis estados da conexão USB-C/HDMI.
 * Baseado em RF005.
 */
enum class ConnectionStatus {
    /** Nenhum adaptador USB-C detectado. */
    DISCONNECTED,

    /** Adaptador USB-C detectado, mas nenhum monitor HDMI conectado a ele. */
    ADAPTER_CONNECTED,

    /** Adaptador USB-C e monitor HDMI detectados e prontos para iniciar a transmissão. */
    READY_TO_TRANSMIT,

    /** A transmissão (espelhamento/extensão) para o monitor HDMI está ativa. */
    TRANSMITTING,

    /** Ocorreu um erro durante a detecção ou transmissão. */
    ERROR
}