package eu.kanade.presentation.more.settings.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogE
import exh.uconfig.EHConfigurator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchUI
import tachiyomi.domain.UnsortedPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

@Composable
fun ConfigureExhDialog(run: Boolean, onRunning: () -> Unit) {
    val unsortedPreferences = remember {
        Injekt.get<UnsortedPreferences>()
    }
    var warnDialogOpen by remember { mutableStateOf(false) }
    var configureDialogOpen by remember { mutableStateOf(false) }
    var configureFailedDialogOpen by remember { mutableStateOf<Exception?>(null) }

    LaunchedEffect(run) {
        if (run) {
            if (unsortedPreferences.exhShowSettingsUploadWarning().get()) {
                warnDialogOpen = true
            } else {
                configureDialogOpen = true
            }
            onRunning()
        }
    }

    if (warnDialogOpen) {
        AlertDialog(
            onDismissRequest = { warnDialogOpen = false },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
            confirmButton = {
                TextButton(
                    onClick = {
                        unsortedPreferences.exhShowSettingsUploadWarning().set(false)
                        configureDialogOpen = true
                        warnDialogOpen = false
                    },
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            title = {
                Text(text = stringResource(R.string.settings_profile_note))
            },
            text = {
                Text(text = stringResource(R.string.settings_profile_note_message))
            },
        )
    }
    if (configureDialogOpen) {
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    delay(0.2.seconds)
                    EHConfigurator(context).configureAll()
                    launchUI {
                        context.toast(R.string.eh_settings_successfully_uploaded)
                    }
                } catch (e: Exception) {
                    configureFailedDialogOpen = e
                    xLogE("Configuration error!", e)
                } finally {
                    configureDialogOpen = false
                }
            }
        }
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
            confirmButton = {},
            title = {
                Text(text = stringResource(R.string.eh_settings_uploading_to_server))
            },
            text = {
                Text(text = stringResource(R.string.eh_settings_uploading_to_server_message))
            },
        )
    }
    if (configureFailedDialogOpen != null) {
        AlertDialog(
            onDismissRequest = { configureFailedDialogOpen = null },
            confirmButton = {
                TextButton(onClick = { configureFailedDialogOpen = null }) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            title = {
                Text(text = stringResource(R.string.eh_settings_configuration_failed))
            },
            text = {
                Text(text = stringResource(R.string.eh_settings_configuration_failed_message, configureFailedDialogOpen?.message.orEmpty()))
            },
        )
    }
}
