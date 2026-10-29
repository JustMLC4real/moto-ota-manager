package nl.moto.ota.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import nl.moto.ota.BuildConfig
import nl.moto.ota.R

@Composable
fun SettingsScreen(settings: AppSettings,onlineConsent:Boolean=false,canChangeNetwork:Boolean=true,
                   onOnlineConsent:(Boolean)->Unit={},onTour: () -> Unit) {
    val context=LocalContext.current
    var notices by remember {mutableStateOf<String?>(null)}
    PageHeader(R.string.ui_settings_title, R.string.ui_settings_body)
    Panel {
        LanguageChoices(settings)
        Text(stringResource(R.string.ui_language_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Panel(R.string.ui_appearance) {
        listOf(AppAppearance.SYSTEM to R.string.ui_appearance_system, AppAppearance.LIGHT to R.string.ui_appearance_light,
            AppAppearance.DARK to R.string.ui_appearance_dark).forEach { (appearance, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.appearance == appearance, onClick = { settings.selectAppearance(appearance) }, modifier = Modifier.testTag("theme_${appearance.name.lowercase()}"))
                TextButton(onClick = { settings.selectAppearance(appearance) }) { Text(stringResource(label)) }
            }
        }
    }
    Panel(R.string.ui_help) {
        Text(stringResource(R.string.ui_setup_again_body))
        OutlinedButton(onClick = onTour, modifier = Modifier.testTag("review_setup")) { Text(stringResource(R.string.ui_setup_again)) }
    }
    Panel(R.string.ui_online_privacy_title) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(stringResource(R.string.ui_online_privacy_switch),modifier=Modifier.weight(1f))
            Switch(checked=onlineConsent,onCheckedChange=onOnlineConsent,enabled=canChangeNetwork,modifier=Modifier.testTag("online_privacy_switch"))
        }
        Text(stringResource(R.string.ui_online_privacy_body),style=MaterialTheme.typography.bodySmall)
    }
    Panel(R.string.ui_about) {
        Text(stringResource(R.string.ui_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ui_about_body))
        TextButton(onClick={notices=listOf("NOTICE.txt","project-mit.txt","apache-2.0.txt","skia-bsd.txt").joinToString("\n\n") {file->
            context.assets.open("licenses/$file").bufferedReader().use {it.readText()}
        }}) {Text(stringResource(R.string.ui_open_source_notices))}
        HorizontalDivider()
        Text(stringResource(R.string.ui_local_privacy), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ui_local_privacy_body), style = MaterialTheme.typography.bodySmall)
    }
    notices?.let {body->AlertDialog(onDismissRequest={notices=null},title={Text(stringResource(R.string.ui_open_source_notices))},
        text={Text(body,modifier=Modifier.verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)},
        confirmButton={TextButton(onClick={notices=null}){Text(stringResource(R.string.ui_close))}})}
}
