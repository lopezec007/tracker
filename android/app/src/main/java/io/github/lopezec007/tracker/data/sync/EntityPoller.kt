// GET /api/entities on a timer (the service loop) or on demand: the latest
// fix of every entity, kept in memory for the Main screen and for OsmAnd.
package io.github.lopezec007.tracker.data.sync

import io.github.lopezec007.tracker.data.api.ApiException
import io.github.lopezec007.tracker.data.api.EntityDto
import io.github.lopezec007.tracker.data.api.TrackerApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

data class PollState(
    val entities: List<EntityDto> = emptyList(),
    val lastSuccess: Long? = null,
    val lastError: String? = null,
    val polling: Boolean = false,
)

class EntityPoller(
    private val apiProvider: () -> TrackerApi?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(PollState())
    val state: StateFlow<PollState> = _state

    /** Fetches every entity; false (with `lastError` set) when the server could not be asked. */
    suspend fun poll(): Boolean = mutex.withLock {
        val api = apiProvider() ?: run {
            _state.update { it.copy(lastError = "not configured") }
            return false
        }
        _state.update { it.copy(polling = true) }
        try {
            val list = api.listEntities().sortedBy { it.name.lowercase() }
            _state.update { it.copy(entities = list, lastSuccess = now(), lastError = null, polling = false) }
            true
        } catch (e: IOException) {
            _state.update { it.copy(lastError = "server not reachable", polling = false) }
            false
        } catch (e: ApiException) {
            _state.update { it.copy(lastError = e.message ?: "HTTP ${e.code}", polling = false) }
            false
        } catch (e: Exception) {
            _state.update { it.copy(lastError = e.toString(), polling = false) }
            false
        }
    }

    fun reset() {
        _state.value = PollState()
    }
}
