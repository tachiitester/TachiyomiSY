package eu.kanade.tachiyomi.ui.category.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.SourceCategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.components.LoadingScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest

class SourceCategoryScreen : Screen {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { SourceCategoryScreenModel() }

        val state by screenModel.state.collectAsState()

        if (state is SourceCategoryScreenState.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as SourceCategoryScreenState.Success

        SourceCategoryScreen(
            state = successState,
            onClickCreate = { screenModel.showDialog(SourceCategoryDialog.Create) },
            onClickRename = { screenModel.showDialog(SourceCategoryDialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(SourceCategoryDialog.Delete(it)) },
            navigateUp = navigator::pop,
        )

        when (val dialog = successState.dialog) {
            null -> {}
            SourceCategoryDialog.Create -> {
                CategoryCreateDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onCreate = { screenModel.createCategory(it) },
                    // SY -->
                    title = stringResource(R.string.action_add_category),
                    // SY <--
                )
            }
            is SourceCategoryDialog.Rename -> {
                CategoryRenameDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onRename = { screenModel.renameCategory(dialog.category, it) },
                    // SY -->
                    category = dialog.category,
                    // SY <--
                )
            }
            is SourceCategoryDialog.Delete -> {
                CategoryDeleteDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onDelete = { screenModel.deleteCategory(dialog.category) },
                    // SY -->
                    title = stringResource(R.string.delete_category),
                    text = stringResource(R.string.delete_category_confirmation, dialog.category),
                    // SY <--
                )
            }
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                if (event is SourceCategoryEvent.LocalizedMessage) {
                    context.toast(event.stringRes)
                }
            }
        }
    }
}
