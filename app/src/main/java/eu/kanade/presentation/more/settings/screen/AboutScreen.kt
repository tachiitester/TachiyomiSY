package eu.kanade.presentation.more.settings.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import compose.icons.SimpleIcons
import compose.icons.simpleicons.Discord
import compose.icons.simpleicons.Facebook
import compose.icons.simpleicons.Github
import compose.icons.simpleicons.Reddit
import compose.icons.simpleicons.Twitter
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.toast
import exh.syDebugVersion
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.util.lang.withIOContext
import tachiyomi.core.util.lang.withUIContext
import tachiyomi.core.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.presentation.core.components.LinkIcon
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object AboutScreen : Screen() {

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow

        // SY -->
        var showWhatsNewDialog by remember { mutableStateOf(false) }
        // SY <--

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(R.string.pref_category_about),
                    navigateUp = if (handleBack != null) handleBack::invoke else null,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(
                contentPadding = contentPadding,
            ) {
                item {
                    LogoHeader()
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(R.string.version),
                        subtitle = getVersionName(withBuildDate = true),
                        onPreferenceClick = {
                            val deviceInfo = CrashLogUtil(context).getDebugInfo()
                            context.copyToClipboard("Debug information", deviceInfo)
                        },
                    )
                }

                if (BuildConfig.INCLUDE_UPDATER) {
                    item {
                        TextPreferenceWidget(
                            title = stringResource(R.string.check_for_updates),
                            onPreferenceClick = {
                                scope.launch {
                                    checkVersion(context) { result ->
                                        val updateScreen = NewUpdateScreen(
                                            versionName = result.release.version,
                                            changelogInfo = result.release.info,
                                            releaseLink = result.release.releaseLink,
                                            downloadLink = result.release.getDownloadLink(),
                                        )
                                        navigator.push(updateScreen)
                                    }
                                }
                            },
                        )
                    }
                }
                if (!BuildConfig.DEBUG) {
                    item {
                        TextPreferenceWidget(
                            title = stringResource(R.string.whats_new),
                            // SY -->
                            onPreferenceClick = { showWhatsNewDialog = true },
                            // SY <--
                        )
                    }
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(R.string.help_translate),
                        onPreferenceClick = { uriHandler.openUri("https://tachiyomi.org/help/contribution/#translation") },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(R.string.licenses),
                        onPreferenceClick = { navigator.push(LicensesScreen()) },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(R.string.privacy_policy),
                        onPreferenceClick = { uriHandler.openUri("https://tachiyomi.org/privacy") },
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LinkIcon(
                            label = stringResource(R.string.website),
                            icon = Icons.Outlined.Public,
                            url = "https://tachiyomi.org",
                        )
                        LinkIcon(
                            label = "Discord",
                            icon = SimpleIcons.Discord,
                            url = "https://discord.gg/tachiyomi",
                        )
                        LinkIcon(
                            label = "Twitter",
                            icon = SimpleIcons.Twitter,
                            url = "https://twitter.com/tachiyomiorg",
                        )
                        LinkIcon(
                            label = "Facebook",
                            icon = SimpleIcons.Facebook,
                            url = "https://facebook.com/tachiyomiorg",
                        )
                        LinkIcon(
                            label = "Reddit",
                            icon = SimpleIcons.Reddit,
                            url = "https://www.reddit.com/r/Tachiyomi",
                        )
                        LinkIcon(
                            label = "GitHub",
                            icon = SimpleIcons.Github,
                            // SY -->
                            url = "https://github.com/jobobby04/tachiyomisy",
                            // SY <--
                        )
                    }
                }
            }
        }

        // SY -->
        if (showWhatsNewDialog) {
            WhatsNewDialog(onDismissRequest = { showWhatsNewDialog = false })
        }
        // SY <--
    }

    /**
     * Checks version and shows a user prompt if an update is available.
     */
    private suspend fun checkVersion(context: Context, onAvailableUpdate: (GetApplicationRelease.Result.NewUpdate) -> Unit) {
        val updateChecker = AppUpdateChecker()
        withUIContext {
            context.toast(R.string.update_check_look_for_updates)
            try {
                when (val result = withIOContext { updateChecker.checkForUpdate(context, forceCheck = true) }) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        onAvailableUpdate(result)
                    }
                    is GetApplicationRelease.Result.NoNewUpdate -> {
                        context.toast(R.string.update_check_no_new_updates)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                context.toast(e.message)
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun getVersionName(withBuildDate: Boolean): String {
        return when {
            BuildConfig.DEBUG -> {
                "Debug ${BuildConfig.COMMIT_SHA}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
            // SY -->
            isPreviewBuildType -> {
                "Preview r$syDebugVersion".let {
                    if (withBuildDate) {
                        "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                    } else {
                        "$it (${BuildConfig.COMMIT_SHA})"
                    }
                }
            }
            // SY <--
            else -> {
                "Stable ${BuildConfig.VERSION_NAME}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
        }
    }

    internal fun getFormattedBuildTime(): String {
        return try {
            val inputDf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US)
            inputDf.timeZone = TimeZone.getTimeZone("UTC")
            val buildTime = inputDf.parse(BuildConfig.BUILD_TIME)

            val outputDf = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
                Locale.getDefault(),
            )
            outputDf.timeZone = TimeZone.getDefault()

            buildTime!!.toDateTimestampString(UiPreferences.dateFormat(Injekt.get<UiPreferences>().dateFormat().get()))
        } catch (e: Exception) {
            BuildConfig.BUILD_TIME
        }
    }
}
