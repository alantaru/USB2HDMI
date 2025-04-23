package com.perfectcorp.usb2hdmi.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Representa uma resolução de tela suportada ou ativa.
 * Inclui largura, altura e taxa de atualização.
 *
 * @property width Largura da resolução em pixels.
 * @property height Altura da resolução em pixels.
 * @property refreshRate Taxa de atualização em Hertz (Hz).
 */
@Parcelize // Permite passar instâncias entre componentes Android (ex: via Intents ou Bundles)
data class Resolution(
    val width: Int,
    val height: Int,
    val refreshRate: Int
) : Parcelable {
    /**
     * Fornece uma representação textual amigável da resolução.
     * Exemplo: "1920x1080 @ 60Hz"
     */
    override fun toString(): String {
        // Usar Locale.US para garantir ponto decimal se refreshRate for float no futuro
        // return String.format(java.util.Locale.US, "%dx%d @ %.0fHz", width, height, refreshRate)
        // Como refreshRate é Int por enquanto:
        return "${width}x${height} @ ${refreshRate}Hz"
    }
}