package com.trickhook.il2cppdumper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DumperScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DumperScreen(model: DumperViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { model.scan() }

    Scaffold(topBar = { TopAppBar(title = { Text("Il2CppDumper") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when {
                state.scanning -> Centered { CircularProgressIndicator() }
                state.running -> RunPanel(state, onCancel = model::cancel)
                state.selected != null -> TargetPanel(
                    state = state,
                    onBack = model::clearSelection,
                    onRun = model::run
                )
                else -> TargetList(state, onPick = model::select, onRescan = model::scan)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) { content() }
}

@Composable
private fun TargetList(state: DumperState, onPick: (Il2CppTarget) -> Unit, onRescan: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${state.targets.size} apps IL2CPP", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onRescan) { Text("Reescanear") }
    }
    Spacer(Modifier.height(12.dp))
    if (state.targets.isEmpty()) {
        Centered { Text("Nenhum app com libil2cpp.so encontrado") }
        return
    }
    LazyColumn(
        state = rememberLazyListState(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.targets, key = { it.packageName }) { target ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)) {
                    Text(target.label, style = MaterialTheme.typography.titleSmall)
                    Text(target.packageName, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${target.abi}  ${target.versionName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onPick(target) }) { Text("Selecionar") }
                }
            }
        }
    }
}

@Composable
private fun TargetPanel(state: DumperState, onBack: () -> Unit, onRun: () -> Unit) {
    val target = state.selected ?: return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(target.label, style = MaterialTheme.typography.titleMedium)
        Text(target.packageName, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Field("ABI", target.abi)
        Field("Versao", target.versionName)
        Field("Biblioteca", target.libraryPath)
        Field("APK da lib", target.librarySource.ifEmpty { "extraida" })
        Field("APK do metadata", target.metadataSource)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRun) { Text("Dumpar") }
            OutlinedButton(onClick = onBack) { Text("Voltar") }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun RunPanel(state: DumperState, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(state.stage, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            state.log.forEach { line ->
                Text(
                    line,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancelar") }
    }
}
