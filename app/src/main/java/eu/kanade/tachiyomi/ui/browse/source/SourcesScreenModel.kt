package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.coroutineScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.interactor.GetShowLatest
import eu.kanade.domain.source.interactor.GetSourceCategories
import eu.kanade.domain.source.interactor.SetSourceCategories
import eu.kanade.domain.source.interactor.ToggleExcludeFromDataSaver
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.domain.source.model.Pin
import eu.kanade.domain.source.model.Source
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.browse.SourceUiModel
import eu.kanade.tachiyomi.util.system.logcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.TreeMap

class SourcesScreenModel(
    private val preferences: BasePreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val toggleSource: ToggleSource = Injekt.get(),
    private val toggleSourcePin: ToggleSourcePin = Injekt.get(),
    // SY -->
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val getSourceCategories: GetSourceCategories = Injekt.get(),
    private val getShowLatest: GetShowLatest = Injekt.get(),
    private val toggleExcludeFromDataSaver: ToggleExcludeFromDataSaver = Injekt.get(),
    private val setSourceCategories: SetSourceCategories = Injekt.get(),
    val smartSearchConfig: SourcesScreen.SmartSearchConfig?,
    // SY <--
) : StateScreenModel<SourcesState>(SourcesState()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    val useNewSourceNavigation = uiPreferences.useNewSourceNavigation().get()

    init {
        // SY -->
        combine(
            getEnabledSources.subscribe(),
            getSourceCategories.subscribe(),
            getShowLatest.subscribe(smartSearchConfig != null),
            flowOf(smartSearchConfig == null),
            ::collectLatestSources,
        )
            .catch {
                logcat(LogPriority.ERROR, it)
                _events.send(Event.FailedFetchingSources)
            }
            .flowOn(Dispatchers.IO)
            .launchIn(coroutineScope)

        sourcePreferences.dataSaver().changes()
            .onEach { enabled ->
                mutableState.update { it.copy(dataSaverEnabled = enabled) }
            }
            .launchIn(coroutineScope)
        // SY <--
    }

    private fun collectLatestSources(sources: List<Source>, categories: List<String>, showLatest: Boolean, showPin: Boolean) {
        mutableState.update { state ->
            val map = TreeMap<String, MutableList<Source>> { d1, d2 ->
                // Sources without a lang defined will be placed at the end
                when {
                    d1 == LAST_USED_KEY && d2 != LAST_USED_KEY -> -1
                    d2 == LAST_USED_KEY && d1 != LAST_USED_KEY -> 1
                    d1 == PINNED_KEY && d2 != PINNED_KEY -> -1
                    d2 == PINNED_KEY && d1 != PINNED_KEY -> 1
                    // SY -->
                    d1.startsWith(CATEGORY_KEY_PREFIX) && !d2.startsWith(CATEGORY_KEY_PREFIX) -> -1
                    d2.startsWith(CATEGORY_KEY_PREFIX) && !d1.startsWith(CATEGORY_KEY_PREFIX) -> 1
                    // SY <--
                    d1 == "" && d2 != "" -> 1
                    d2 == "" && d1 != "" -> -1
                    else -> d1.compareTo(d2)
                }
            }
            val byLang = sources.groupByTo(map) {
                when {
                    // SY -->
                    it.category != null -> "$CATEGORY_KEY_PREFIX${it.category}"
                    // SY <--
                    it.isUsedLast -> LAST_USED_KEY
                    Pin.Actual in it.pin -> PINNED_KEY
                    else -> it.lang
                }
            }

            state.copy(
                isLoading = false,
                items = byLang.flatMap {
                    listOf(
                        SourceUiModel.Header(it.key.removePrefix(CATEGORY_KEY_PREFIX), it.value.firstOrNull()?.category != null),
                        *it.value.map { source ->
                            SourceUiModel.Item(source)
                        }.toTypedArray(),
                    )
                },
                // SY -->
                categories = categories.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it }),
                showPin = showPin,
                showLatest = showLatest,
                // SY <--
            )
        }
    }

    fun onOpenSource(source: Source) {
        if (!preferences.incognitoMode().get()) {
            sourcePreferences.lastUsedSource().set(source.id)
        }
    }

    fun toggleSource(source: Source) {
        toggleSource.await(source)
    }

    fun togglePin(source: Source) {
        toggleSourcePin.await(source)
    }

    // SY -->
    fun toggleExcludeFromDataSaver(source: Source) {
        toggleExcludeFromDataSaver.await(source)
    }

    fun setSourceCategories(source: Source, categories: List<String>) {
        setSourceCategories.await(source, categories)
    }

    fun showSourceCategoriesDialog(source: Source) {
        mutableState.update { it.copy(dialog = Dialog.SourceCategories(source)) }
    }
    // SY <--

    fun showSourceDialog(source: Source) {
        mutableState.update { it.copy(dialog = Dialog.SourceLongClick(source)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed class Event {
        object FailedFetchingSources : Event()
    }

    sealed class Dialog {
        data class SourceLongClick(val source: Source) : Dialog()
        data class SourceCategories(val source: Source) : Dialog()
    }

    companion object {
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"

        // SY -->
        const val CATEGORY_KEY_PREFIX = "category-"
        // SY <--
    }
}

@Immutable
data class SourcesState(
    val dialog: SourcesScreenModel.Dialog? = null,
    val isLoading: Boolean = true,
    val items: List<SourceUiModel> = emptyList(),
    // SY -->
    val categories: List<String> = emptyList(),
    val showPin: Boolean = true,
    val showLatest: Boolean = false,
    val dataSaverEnabled: Boolean = false,
    // SY <--
) {
    val isEmpty = items.isEmpty()
}
