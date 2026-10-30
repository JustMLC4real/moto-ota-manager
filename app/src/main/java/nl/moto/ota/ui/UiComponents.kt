package nl.moto.ota.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.moto.ota.R
import java.text.NumberFormat

@Composable
fun PageHeader(@StringRes title: Int, @StringRes body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Panel(@StringRes title: Int? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(stringResource(it), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }) }
            content()
        }
    }
}

/** Status changes animate once; Compose honours Android's animator duration scale. */
@Composable
fun StatusPanel(attention: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val color by animateColorAsState(
        if (attention) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(220), label = "statusTone")
    Card(Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
fun StatusBadge(text: String, attention: Boolean = false) {
    Surface(color = if (attention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface.copy(alpha = .76f),
        contentColor = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(50)) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Notice(title: String, body: String, critical: Boolean = false) {
    Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(
        containerColor = if (critical) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
fun ExpandablePanel(@StringRes title: Int, initiallyExpanded: Boolean = false, summary: String? = null,
    tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val expandedLabel = stringResource(if (expanded) R.string.ui_expanded else R.string.ui_collapsed)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "disclosureArrow")
    val arrowColor = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            Row(Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .semantics { stateDescription = expandedLabel }
                .clickable(role = Role.Button) { expanded = !expanded }
                .heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Canvas(Modifier.size(16.dp).rotate(rotation)) {
                    drawLine(arrowColor, Offset(size.width * .2f, size.height * .35f), Offset(size.width * .5f, size.height * .65f), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(arrowColor, Offset(size.width * .5f, size.height * .65f), Offset(size.width * .8f, size.height * .35f), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically(tween(220)) + fadeIn(tween(160)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(120))) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    content()
                }
            }
        }
    }
}

@Composable
fun AnimatedProgress(fraction: Float, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "measuredProgress")
    LinearProgressIndicator(progress = { progress }, modifier = modifier.fillMaxWidth())
}

@Composable
fun displaySize(bytes: Long): String {
    if (bytes < 0) return stringResource(R.string.ui_unknown)
    val locale = LocalConfiguration.current.locales[0]
    val unit = if (bytes >= 1_073_741_824L) 1_073_741_824.0 else 1_048_576.0
    val value = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(bytes / unit)
    return stringResource(if (unit == 1_073_741_824.0) R.string.ui_gib else R.string.ui_mib, value)
}
