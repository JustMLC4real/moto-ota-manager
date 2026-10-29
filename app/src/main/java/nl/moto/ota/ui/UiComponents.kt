package nl.moto.ota.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.moto.ota.R
import java.text.NumberFormat

@Composable
fun PageHeader(@StringRes title: Int, @StringRes body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Panel(@StringRes title: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(stringResource(it), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }) }
            content()
        }
    }
}

@Composable
fun Notice(title: String, body: String, critical: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = if (critical) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun Detail(@StringRes label: Int, value: String) = Detail(stringResource(label), value)

@Composable
fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { stringResource(R.string.ui_unknown) }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ExpandablePanel(@StringRes title: Int, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Panel {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(title), modifier = Modifier.weight(1f))
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge)
        }
        if (expanded) content()
    }
}

@Composable
fun displaySize(bytes: Long): String {
    if (bytes < 0) return stringResource(R.string.ui_unknown)
    val locale = LocalConfiguration.current.locales[0]
    val unit = if (bytes >= 1_073_741_824L) 1_073_741_824.0 else 1_048_576.0
    val value = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(bytes / unit)
    return stringResource(if (unit == 1_073_741_824.0) R.string.ui_gib else R.string.ui_mib, value)
}
