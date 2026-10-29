package nl.moto.ota.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.moto.ota.R

/** The tour explains permissions; it never requests root or starts an update. */
@Composable
fun SetupTour(settings: AppSettings, onFinished: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(R.string.ui_setup_welcome_title, R.string.ui_setup_route_title, R.string.ui_setup_root_title, R.string.ui_setup_safety_title)
    val bodies = listOf(R.string.ui_setup_welcome_body, R.string.ui_setup_route_body, R.string.ui_setup_root_body, R.string.ui_setup_safety_body)
    val features = listOf(
        listOf(R.string.ui_setup_welcome_feature1, R.string.ui_setup_welcome_feature2, R.string.ui_setup_welcome_feature3),
        listOf(R.string.ui_setup_route_feature1, R.string.ui_setup_route_feature2, R.string.ui_setup_route_feature3),
        listOf(R.string.ui_setup_root_feature1, R.string.ui_setup_root_feature2, R.string.ui_setup_root_feature3),
        listOf(R.string.ui_setup_safety_feature1, R.string.ui_setup_safety_feature2, R.string.ui_setup_safety_feature3)
    )
    BackHandler(enabled = page > 0) { page-- }
    Surface(Modifier.fillMaxSize().testTag("setup_tour"), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.ui_setup_step, page + 1, titles.size), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { settings.completeTour(); onFinished() }, modifier = Modifier.testTag("setup_skip")) {
                        Text(stringResource(R.string.ui_setup_skip))
                    }
                }
                LinearProgressIndicator(progress = { (page + 1f) / titles.size }, modifier = Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(80.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_moto_ota), contentDescription = null, modifier = Modifier.size(48.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                    }
                    Text(stringResource(titles[page]), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                    Text(stringResource(bodies[page]), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    features[page].forEach { label ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (page == 0) LanguageChoices(settings)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page > 0) OutlinedButton(onClick = { page-- }, modifier = Modifier.testTag("setup_back")) { Text(stringResource(R.string.ui_back)) }
                    Button(onClick = { if (page < titles.lastIndex) page++ else { settings.completeTour(); onFinished() } },
                        modifier = Modifier.weight(1f).testTag("setup_next")) {
                        Text(stringResource(if (page < titles.lastIndex) R.string.ui_continue else R.string.ui_setup_start))
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageChoices(settings: AppSettings) {
    // Reading configuration makes a change made in Android's app-language screen visible here.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val selected = remember(configuration) { settings.languageTag() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.ui_language), style = MaterialTheme.typography.titleSmall)
        listOf("" to R.string.ui_system_default, "en" to R.string.ui_english, "nl" to R.string.ui_dutch).forEach { (tag, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == tag, onClick = { settings.selectLanguage(tag) }, modifier = Modifier.testTag("language_${tag.ifEmpty { "system" }}"))
                TextButton(onClick = { settings.selectLanguage(tag) }) { Text(stringResource(label)) }
            }
        }
    }
}
