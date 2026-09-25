package com.trickhook.il2cppdumper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CreditsScreen() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)) {
                Text("trickzqw", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Il2CppDumper mobile",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Dump completo de IL2CPP direto do APK, sem root",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Section("O que este app faz") {
            Line("Varre os apps instalados atras de libil2cpp.so")
            Line("Le a biblioteca e o global-metadata.dat dos split APKs")
            Line("Desempacota o protector: XOR com permutacao de janelas mais AES-128-CBC na primeira")
            Line("Confere o resultado pelo CRC32 que o proprio descritor carrega")
            Line("Detecta sozinho layouts de metadata fora do padrao")
            Line("Gera dump.cs, script.json, stringliteral.json e il2cpp.h")
        }

        Spacer(Modifier.height(20.dp))
        Section("Baseado em") {
            Line("Perfare/Il2CppDumper, licenca MIT")
            Line("github.com/Perfare/Il2CppDumper")
            Spacer(Modifier.height(8.dp))
            Line("Port para Kotlin, desempacotador do protector e separacao")
            Line("do ScriptTypeInfo escritos para este projeto.")
        }

        Spacer(Modifier.height(20.dp))
        Section("Saida") {
            Line("Android/data/com.trickhook.il2cppdumper/files/<pacote>/")
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "v1.0",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Line(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
    )
}
