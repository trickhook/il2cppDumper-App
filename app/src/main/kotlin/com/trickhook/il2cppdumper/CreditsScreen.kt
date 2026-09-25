package com.trickhook.il2cppdumper

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CreditsScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        Text("trickzqw", fontSize = 34.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(32.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Link("github.com/trickhook")
                Spacer(Modifier.height(10.dp))
                Link("t.me/rootlocalhostt")
            }
        }

        Spacer(Modifier.height(28.dp))

        Text("Atualizacoes", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Link("github.com/trickhook/il2cppDumper-App")

        Spacer(Modifier.height(36.dp))
        Text("v1.0", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Link(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}
