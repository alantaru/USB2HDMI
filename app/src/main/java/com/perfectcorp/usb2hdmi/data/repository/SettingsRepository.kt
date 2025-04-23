package com.perfectcorp.usb2hdmi.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.perfectcorp.usb2hdmi.data.model.Resolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.io.IOException

// Constante para Logging
private const val TAG = "SettingsRepository"

// Define a instância do DataStore a nível de top-level (recomendado)
// O nome "user_settings" será o nome do arquivo de preferências.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

/**
 * Repositório para gerenciar o armazenamento e recuperação de configurações
 * persistentes do usuário usando Jetpack DataStore.
 *
 * @param context Contexto da aplicação para acessar o DataStore.
 */
class SettingsRepository(private val context: Context) {

    // Chaves para as preferências que vamos armazenar
    private companion object PreferencesKeys {
        val PREFERRED_RESOLUTION_WIDTH = intPreferencesKey("preferred_resolution_width")
        val PREFERRED_RESOLUTION_HEIGHT = intPreferencesKey("preferred_resolution_height")
        val PREFERRED_RESOLUTION_REFRESH_RATE = intPreferencesKey("preferred_resolution_refresh_rate")
        val LAST_CONNECTION_STATE_ACTIVE = booleanPreferencesKey("last_connection_state_active")
        // Poderíamos usar uma string única para resolução, mas chaves separadas são mais robustas
        // val PREFERRED_RESOLUTION_STRING = stringPreferencesKey("preferred_resolution_string")
    }

    /**
     * Salva a resolução preferida do usuário.
     * Se a resolução for null, remove as chaves existentes.
     */
    suspend fun savePreferredResolution(resolution: Resolution?) {
        try {
            context.dataStore.edit { preferences ->
                if (resolution != null) {
                    preferences[PREFERRED_RESOLUTION_WIDTH] = resolution.width
                    preferences[PREFERRED_RESOLUTION_HEIGHT] = resolution.height
                    preferences[PREFERRED_RESOLUTION_REFRESH_RATE] = resolution.refreshRate
                    Log.d(TAG, "Resolução preferida salva: $resolution")
                } else {
                    // Remover chaves se a resolução for nula
                    preferences.remove(PREFERRED_RESOLUTION_WIDTH)
                    preferences.remove(PREFERRED_RESOLUTION_HEIGHT)
                    preferences.remove(PREFERRED_RESOLUTION_REFRESH_RATE)
                    Log.d(TAG, "Resolução preferida removida.")
                }
            }
        } catch (exception: IOException) {
            Log.e(TAG, "Erro ao salvar resolução preferida", exception)
            // Opcional: Relançar ou tratar o erro de forma específica
        }
    }

    /**
     * Recupera a resolução preferida do usuário como um Flow.
     * Emite null se nenhuma preferência estiver salva ou se faltar alguma chave.
     */
    val preferredResolution: Flow<Resolution?> = context.dataStore.data
        .catch { exception ->
            // Tratar erros de leitura (ex: IOException)
            if (exception is IOException) {
                Log.e(TAG, "Erro ao ler preferências de resolução", exception)
                emit(emptyPreferences()) // Emitir preferências vazias em caso de erro
            } else {
                throw exception // Relançar outros erros
            }
        }
        .map { preferences ->
            val width = preferences[PREFERRED_RESOLUTION_WIDTH]
            val height = preferences[PREFERRED_RESOLUTION_HEIGHT]
            val refreshRate = preferences[PREFERRED_RESOLUTION_REFRESH_RATE]

            if (width != null && height != null && refreshRate != null) {
                Resolution(width, height, refreshRate)
            } else {
                null // Retorna null se alguma parte da resolução não estiver salva
            }
        }

    /**
     * Salva o último estado conhecido da conexão (se estava ativa ou não).
     * Útil para RF013 (tentar restabelecer conexão).
     */
    suspend fun saveLastConnectionState(isActive: Boolean) {
        try {
            context.dataStore.edit { preferences ->
                preferences[LAST_CONNECTION_STATE_ACTIVE] = isActive
                Log.d(TAG, "Último estado da conexão salvo: $isActive")
            }
        } catch (exception: IOException) {
            Log.e(TAG, "Erro ao salvar último estado da conexão", exception)
        }
    }

    /**
     * Recupera o último estado conhecido da conexão como um Flow.
     * Emite false por padrão se nada estiver salvo.
     */
    val lastConnectionStateActive: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Erro ao ler último estado da conexão", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[LAST_CONNECTION_STATE_ACTIVE] ?: false // Retorna false se não definido
        }

    /**
     * Função utilitária para obter o valor mais recente da resolução preferida
     * de forma síncrona (usar com cuidado, preferir o Flow).
     */
     suspend fun getPreferredResolutionOnce(): Resolution? {
         return preferredResolution.firstOrNull()
     }
}