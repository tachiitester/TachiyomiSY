package eu.kanade.tachiyomi.ui.browse.migration.advanced.process

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.MigrationListScreen
import eu.kanade.presentation.browse.components.MigrationExitDialog
import eu.kanade.presentation.browse.components.MigrationMangaDialog
import eu.kanade.presentation.util.LocalRouter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.changehandler.OneWayFadeChangeHandler
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.browse.migration.advanced.design.PreMigrationController
import eu.kanade.tachiyomi.ui.browse.migration.advanced.design.PreMigrationScreen
import eu.kanade.tachiyomi.ui.browse.migration.search.MigrateSearchScreen
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.util.lang.withUIContext
import eu.kanade.tachiyomi.util.system.toast

class MigrationListScreen(private val config: MigrationProcedureConfig) : Screen {

    var newSelectedItem by mutableStateOf<Pair<Long, Long>?>(null)

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MigrationListScreenModel(config) }
        val items by screenModel.migratingItems.collectAsState()
        val migrationDone by screenModel.migrationDone.collectAsState()
        val unfinishedCount by screenModel.unfinishedCount.collectAsState()
        val dialog by screenModel.dialog.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val router = LocalRouter.currentOrThrow
        val context = LocalContext.current
        LaunchedEffect(items) {
            if (items.isEmpty()) {
                val manualMigrations = screenModel.manualMigrations.value
                context.toast(
                    context.resources.getQuantityString(
                        R.plurals.entry_migrated,
                        manualMigrations,
                        manualMigrations,
                    ),
                )
                if (!screenModel.hideNotFound) {
                    if (navigator.canPop) {
                        navigator.pop()
                    } else {
                        router.popCurrentController()
                    }
                }
            }
        }

        LaunchedEffect(newSelectedItem) {
            if (newSelectedItem != null) {
                val (oldId, newId) = newSelectedItem!!
                screenModel.useMangaForMigration(context, newId, oldId)
                newSelectedItem = null
            }
        }

        LaunchedEffect(screenModel) {
            screenModel.navigateOut.collect {
                if (navigator.canPop) {
                    if (items.size == 1) {
                        val hasDetails = navigator.items.any { it is MangaScreen }
                        if (hasDetails) {
                            val manga = (items.firstOrNull()?.searchResult?.value as? MigratingManga.SearchResult.Result)?.let {
                                screenModel.getManga(it.id)
                            }
                            withUIContext {
                                if (manga != null) {
                                    val newStack = navigator.items.filter {
                                        it !is MangaScreen &&
                                            it !is MigrationListScreen &&
                                            it !is PreMigrationScreen
                                    } + MangaScreen(manga.id)
                                    navigator replaceAll newStack.first()
                                    navigator.push(newStack.drop(1))
                                } else {
                                    navigator.pop()
                                }
                            }
                        }
                    } else {
                        withUIContext {
                            navigator.pop()
                        }
                    }
                } else {
                    if (items.size == 1) {
                        val hasDetails = router.backstack.any { it.controller is MangaController }
                        if (hasDetails) {
                            val manga = (items.firstOrNull()?.searchResult?.value as? MigratingManga.SearchResult.Result)?.let {
                                screenModel.getManga(it.id)
                            }
                            withUIContext {
                                if (manga != null) {
                                    val newStack = router.backstack.filter {
                                        it.controller !is MangaController &&
                                            it.controller !is MigrationListController &&
                                            it.controller !is PreMigrationController
                                    } + MangaController(manga.id).withFadeTransaction()
                                    router.setBackstack(newStack, OneWayFadeChangeHandler())
                                } else {
                                    router.popCurrentController()
                                }
                            }
                        }
                    } else {
                        withUIContext {
                            router.popCurrentController()
                        }
                    }
                }
            }
        }
        MigrationListScreen(
            items = items,
            migrationDone = migrationDone,
            unfinishedCount = unfinishedCount,
            getManga = screenModel::getManga,
            getChapterInfo = screenModel::getChapterInfo,
            getSourceName = screenModel::getSourceName,
            onMigrationItemClick = {
                navigator.push(MangaScreen(it.id, true))
            },
            openMigrationDialog = screenModel::openMigrateDialog,
            skipManga = { screenModel.removeManga(it) },
            searchManually = { migrationItem ->
                val sources = screenModel.getMigrationSources()
                val validSources = if (sources.size == 1) {
                    sources
                } else {
                    sources.filter { it.id != migrationItem.manga.source }
                }
                val searchScreen = MigrateSearchScreen(migrationItem.manga.id, validSources.map { it.id })
                navigator push searchScreen
            },
            migrateNow = { screenModel.migrateManga(it, false) },
            copyNow = { screenModel.migrateManga(it, true) },
        )

        val onDismissRequest = { screenModel.dialog.value = null }
        when (val dialog = dialog) {
            is MigrationListScreenModel.Dialog.MigrateMangaDialog -> {
                MigrationMangaDialog(
                    onDismissRequest = onDismissRequest,
                    copy = dialog.copy,
                    mangaSet = dialog.mangaSet,
                    mangaSkipped = dialog.mangaSkipped,
                    copyManga = screenModel::copyMangas,
                    migrateManga = screenModel::migrateMangas,
                )
            }
            MigrationListScreenModel.Dialog.MigrationExitDialog -> {
                MigrationExitDialog(
                    onDismissRequest = onDismissRequest,
                    exitMigration = {
                        if (navigator.canPop) {
                            navigator.pop()
                        } else {
                            router.popCurrentController()
                        }
                    },
                )
            }
            null -> Unit
        }

        BackHandler(true) {
            screenModel.dialog.value = MigrationListScreenModel.Dialog.MigrationExitDialog
        }
    }
}
