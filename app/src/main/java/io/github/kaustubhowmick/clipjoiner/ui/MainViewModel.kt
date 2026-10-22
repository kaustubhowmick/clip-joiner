package io.github.kaustubhowmick.clipjoiner.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kaustubhowmick.clipjoiner.media.ClipInfo
import io.github.kaustubhowmick.clipjoiner.media.ClipProbe
import io.github.kaustubhowmick.clipjoiner.media.MergeAnalysis
import io.github.kaustubhowmick.clipjoiner.media.MergeEngine
import io.github.kaustubhowmick.clipjoiner.media.MergePlan
import io.github.kaustubhowmick.clipjoiner.media.MergeProgress
import io.github.kaustubhowmick.clipjoiner.media.Orientation
import io.github.kaustubhowmick.clipjoiner.media.SavedVideo
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ClipItem(
    val key: Long,
    val uri: Uri,
    val info: ClipInfo? = null,
    val error: String? = null,
)

sealed interface Phase {
    data object Idle : Phase
    data class Working(val progress: MergeProgress?) : Phase
    data class Done(val saved: SavedVideo) : Phase
    data class Failed(val message: String) : Phase
}

data class UiState(
    val clips: List<ClipItem> = emptyList(),
    val phase: Phase = Phase.Idle,
    val showOrientationSheet: Boolean = false,
) {
    val probing: Boolean get() = clips.any { it.info == null && it.error == null }
    val ready: List<ClipInfo>? by lazy { if (clips.isNotEmpty() && clips.all { it.info != null }) clips.map { it.info!! } else null }
    val analysis: MergeAnalysis? by lazy { ready?.let { MergeAnalysis(it) } }
    val canMerge: Boolean get() = clips.size >= 2 && ready != null && phase !is Phase.Working
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val nextKey = AtomicLong()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var mergeJob: Job? = null

    init {
        MergeEngine.cleanStaleWork(app)
    }

    fun addClips(uris: List<Uri>) {
        val items = uris.map { ClipItem(nextKey.incrementAndGet(), it) }
        _state.update { it.copy(clips = it.clips + items, phase = if (it.phase is Phase.Working) it.phase else Phase.Idle) }
        for (item in items) {
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ClipProbe.probe(getApplication(), item.uri) }
                }
                _state.update { s ->
                    s.copy(clips = s.clips.map { c ->
                        if (c.key != item.key) c
                        else result.fold(
                            { c.copy(info = it) },
                            { e -> Log.w(TAG, "Probe failed for ${item.uri}", e); c.copy(error = "Can't read this video") },
                        )
                    })
                }
            }
        }
    }

    fun remove(key: Long) = _state.update { s -> s.copy(clips = s.clips.filterNot { it.key == key }) }

    fun move(from: Int, to: Int) = _state.update { s ->
        if (from !in s.clips.indices || to !in s.clips.indices) return@update s
        s.copy(clips = s.clips.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun clearAll() = _state.update { UiState() }

    fun onMergeClicked() {
        val analysis = _state.value.analysis ?: return
        if (analysis.needsOrientationChoice) {
            _state.update { it.copy(showOrientationSheet = true) }
        } else {
            startMerge(analysis.clips, analysis.defaultPlan())
        }
    }

    fun dismissSheet() = _state.update { it.copy(showOrientationSheet = false) }

    fun mergeAs(orientation: Orientation) {
        val analysis = _state.value.analysis ?: return
        _state.update { it.copy(showOrientationSheet = false) }
        startMerge(analysis.clips, analysis.planFor(orientation))
    }

    private fun startMerge(clips: List<ClipInfo>, plan: MergePlan) {
        if (mergeJob?.isActive == true) return
        _state.update { it.copy(phase = Phase.Working(null)) }
        mergeJob = viewModelScope.launch {
            try {
                val saved = MergeEngine(getApplication()).merge(clips, plan) { p ->
                    _state.update { s -> if (s.phase is Phase.Working) s.copy(phase = Phase.Working(p)) else s }
                }
                _state.update { it.copy(phase = Phase.Done(saved)) }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.Idle) }
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Merge failed", e)
                _state.update { it.copy(phase = Phase.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun cancelMerge() {
        mergeJob?.cancel()
    }

    fun dismissResult() = _state.update { it.copy(phase = Phase.Idle) }

    private companion object {
        const val TAG = "ClipJoiner"
    }
}
