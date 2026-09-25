package com.trickhook.il2cppdumper

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DumperState(
    val scanning: Boolean = false,
    val running: Boolean = false,
    val targets: List<Il2CppTarget> = emptyList(),
    val selected: Il2CppTarget? = null,
    val stage: String = "",
    val log: List<String> = emptyList(),
    val outputDir: String = ""
)

class DumperViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(DumperState())
    val state: StateFlow<DumperState> = _state.asStateFlow()

    private var job: Job? = null

    fun scan() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { ApkSource.scan(getApplication()) }
            _state.update { it.copy(scanning = false, targets = found) }
        }
    }

    fun select(target: Il2CppTarget) = _state.update { it.copy(selected = target) }

    fun clearSelection() = _state.update { it.copy(selected = null) }

    fun reset() = _state.update { it.copy(log = emptyList(), stage = "", selected = null) }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, stage = "Cancelado") }
    }

    fun run() {
        val target = _state.value.selected ?: return
        if (_state.value.running) return
        _state.update { it.copy(running = true, log = emptyList(), stage = "Preparando") }

        job = viewModelScope.launch {
            val output = outputDirFor(target)
            _state.update { it.copy(outputDir = output.absolutePath) }
            runCatching {
                withContext(Dispatchers.Default) {
                    DumpPipeline.run(
                        target = target,
                        outputDir = output,
                        onStage = { stage ->
                            Log.i(TAG, "stage: $stage")
                            _state.update { it.copy(stage = stage) }
                        },
                        onLog = { line ->
                            Log.i(TAG, line)
                            _state.update { it.copy(log = it.log + line) }
                        }
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "falhou", error)
                _state.update {
                    it.copy(
                        stage = "Falhou",
                        log = it.log + (error.message ?: error.toString())
                    )
                }
            }.onSuccess {
                _state.update { it.copy(stage = "Concluido") }
            }
            _state.update { it.copy(running = false) }
        }
    }

    private companion object {
        const val TAG = "Il2CppDumper"
    }

    private fun outputDirFor(target: Il2CppTarget): File {
        val base = getApplication<Application>().getExternalFilesDir(null)
            ?: getApplication<Application>().filesDir
        return File(base, target.packageName).apply { mkdirs() }
    }
}
