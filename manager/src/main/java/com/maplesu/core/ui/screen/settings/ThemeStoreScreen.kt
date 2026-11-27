package com.maplesu.core.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.maplesu.core.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maplesu.core.ksuApp
import com.maplesu.core.theme.*
import com.maplesu.core.ui.LocalUiMode
import com.maplesu.core.ui.UiMode
import com.maplesu.core.ui.navigation3.LocalNavigator
import com.maplesu.core.ui.navigation3.Route
import com.maplesu.core.ui.util.AppearancePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort

data class ThemeStoreState(
    val items: List<StoreTheme> = emptyList(), val local: List<StoreTheme> = emptyList(),
    val loading: Boolean = true, val error: String? = null, val downloading: String? = null,
    val done: Long = 0, val total: Long = -1, val completed: StoreTheme? = null,
    val paused: StoreTheme? = null,
    val failedDownload: StoreTheme? = null,
    val catalog: ThemeStoreSource = ThemeStoreSource.Fina,
)

class ThemeStoreViewModel internal constructor(val repository: ThemeStore = ThemeStore(ksuApp)) : ViewModel() {
    private val mutable = MutableStateFlow(ThemeStoreState(catalog=repository.source()))
    val state = mutable.asStateFlow()
    private var download: Job? = null
    private var refreshJob: Job? = null
    fun refresh(localOnly: Boolean = false, source: ThemeStoreSource? = null) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
        mutable.update { it.copy(loading = true, error = null, failedDownload = null) }
        try {
            if (source != null) repository.source(source)
            val catalog = repository.source()
            mutable.update { it.copy(catalog=catalog,items=if (it.catalog == catalog) it.items else emptyList()) }
            val locals = repository.local()
            val paused = repository.paused()
            mutable.update { it.copy(local=locals,paused=if (it.downloading == null) paused else it.paused) }
            if (localOnly) { mutable.update { it.copy(loading = false) }; return@launch }
            val cached = runCatching { repository.index(false,catalog) }.getOrNull()
            mutable.update { it.copy(local = locals, items = cached ?: it.items) }
            val index = repository.index(source=catalog)
            mutable.update { it.copy(items = index, loading = false) }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName, loading = false) } }
        }
    }
    fun download(theme: StoreTheme) {
        if (download?.isActive == true) return
        mutable.update { it.copy(downloading = theme.id, paused = null, done = 0, total = -1, error = null, failedDownload = null) }
        download = viewModelScope.launch {
            try {
                repository.download(theme) { done,total -> mutable.update { it.copy(done = done,total = total) } }
                val locals = repository.local()
                mutable.update { it.copy(local = locals, completed = theme) }
            } catch (e: CancellationException) { mutable.update { it.copy(paused = theme) }; throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName, failedDownload = theme) } }
            finally { mutable.update { it.copy(downloading = null) } }
        }
    }
    fun pause() { download?.cancel() }
    fun discardPaused() = viewModelScope.launch {
        try {
            mutable.value.paused?.let { repository.discardDownload(it) }
            mutable.update { it.copy(paused = null) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
    }
    fun dismissCompleted() { mutable.update { it.copy(completed = null) } }
    fun delete(theme: StoreTheme) = viewModelScope.launch {
        try { repository.delete(theme); val locals = repository.local(); mutable.update { it.copy(local = locals) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(error = e.message ?: e.javaClass.simpleName) } }
    }
}

@Composable
private fun ThemeDownloadChoices(selected: String, onSelect: (String) -> Unit) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    val primary = if (miuix) top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary
        else androidx.compose.material3.MaterialTheme.colorScheme.primary
    val surface = if (miuix) top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surfaceContainer
        else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = RoundedCornerShape(if (miuix) 20.dp else 16.dp)
    Row(Modifier.fillMaxWidth().selectableGroup().height(IntrinsicSize.Min), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        listOf("builtin" to R.string.theme_download_builtin,"compat" to R.string.theme_download_compat).forEach { (id,label) ->
            val chosen = selected == id
            Column(Modifier.weight(1f).fillMaxHeight().testTag("theme-download-$id").clip(shape).background(if (chosen) primary.copy(alpha=.1f) else surface)
                .border(if (chosen) 1.5.dp else 1.dp,if (chosen) primary else primary.copy(alpha=.12f),shape)
                .selectable(chosen,enabled=!LocalThemeBusy.current,role=Role.RadioButton,onClick={ onSelect(id) })
                .heightIn(min=124.dp).padding(14.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    val icon = if (id == "builtin") Icons.Rounded.Download else Icons.Rounded.OpenInBrowser
                    if (miuix) {
                        top.yukonga.miuix.kmp.basic.Icon(icon,null,tint=primary)
                        top.yukonga.miuix.kmp.basic.RadioButton(selected=chosen,onClick=null)
                    } else {
                        androidx.compose.material3.Icon(icon,null,tint=primary)
                        androidx.compose.material3.RadioButton(selected=chosen,onClick=null)
                    }
                }
                ThemeText(stringResource(label))
            }
        }
    }
}

@Composable
fun ThemeStoreScreen(localOnly: Boolean = false, storeViewModel: ThemeStoreViewModel = viewModel()) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val operations = LocalThemeOperations.current
    val busy = LocalThemeBusy.current
    val viewModel = storeViewModel
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val json = ThemeRuntime.observe()
    val prefs = AppearancePreferences.prefs(context)
    LaunchedEffect(localOnly) { viewModel.refresh(localOnly) }
    var mode by rememberSaveable { mutableStateOf(prefs.getString("theme_store_mode", "builtin") ?: "builtin") }
    var intro by rememberSaveable { mutableStateOf(!localOnly && !prefs.getBoolean("theme_store_intro", false)) }
    var chooseMode by rememberSaveable { mutableStateOf(false) }
    var export by rememberSaveable { mutableStateOf(false) }
    var modeDraft by rememberSaveable { mutableStateOf(mode) }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var sorted by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var typeDraft by rememberSaveable { mutableStateOf("") }
    var filterSourceDraft by rememberSaveable { mutableStateOf("") }
    var authorDraft by rememberSaveable { mutableStateOf("") }
    var sortedDraft by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseSource by rememberSaveable { mutableStateOf(false) }
    var sourceDraft by rememberSaveable { mutableStateOf(state.catalog.id) }
    var customDraft by rememberSaveable { mutableStateOf(prefs.getString(ThemeStoreSource.CUSTOM_URL,"").orEmpty()) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(operations::prepare) }
    fun requestDownload(theme: StoreTheme) {
        val external = ThemeStore.browserDownload(theme, mode)
        if (external == null) viewModel.download(theme)
        else runCatching { uriHandler.openUri(external) }.onFailure(operations::error)
    }
    val all = if (localOnly) state.local else state.items
    val list = remember(all, query, type, source, author, sorted) {
        all.filter { (type.isEmpty() || it.type == type) && (source.isEmpty() ||
            if (source == "third_party") it.source != "official" else it.source == source) &&
            (author.isEmpty() || it.author == author) && (query.isBlank() || "${it.name} ${it.author} ${it.description}".contains(query,true)) }
            .let { if (sorted) it.sortedWith(compareBy<StoreTheme> { it.name.lowercase() }.thenBy { it.id }) else it }
    }
    ThemePage(if (localOnly) stringResource(R.string.theme_library) else stringResource(R.string.theme_store), { navigator.pop() }, actions = {
        if (!localOnly) ThemeStoreAction(Icons.Rounded.FolderOpen,stringResource(R.string.theme_library)) { navigator.push(Route.MyThemes) }
        ThemeStoreAction(if (LocalUiMode.current == UiMode.Miuix) MiuixIcons.Sort else Icons.AutoMirrored.Filled.Sort,
            stringResource(R.string.theme_filter_sort)) {
            typeDraft = type; filterSourceDraft = source; authorDraft = author; sortedDraft = sorted; filters = true
        }
        if (!localOnly) ThemeStoreAction(Icons.Rounded.Refresh,stringResource(R.string.theme_refresh),!state.loading) { viewModel.refresh() }
    }, floatingActionButton = {
        if (!busy) ThemeImportFab { file.launch(arrayOf("*/*")) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()+96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (localOnly) item { ThemeRow(stringResource(R.string.theme_package_export),
                "Download/MapleSU/Themes · .fpt",Icons.Rounded.UploadFile) { export=true } }
            if (!localOnly) item { ThemeRow(stringResource(R.string.theme_catalog_source),
                catalogSourceLabel(state.catalog.id),Icons.Rounded.Dns) {
                sourceDraft = state.catalog.id
                customDraft = prefs.getString(ThemeStoreSource.CUSTOM_URL,"").orEmpty()
                chooseSource = true
            } }
            item { ThemeInput(stringResource(R.string.theme_search), query) { query = it } }
            if (!localOnly) item { ThemeRow(stringResource(R.string.theme_download_mode), if (mode == "builtin") stringResource(R.string.theme_download_builtin) else stringResource(R.string.theme_download_compat), Icons.Rounded.Download) { modeDraft = mode; chooseMode = true } }
            if (state.loading && !localOnly) item { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeProgress(); ThemeText(stringResource(R.string.theme_store_loading), secondary = true)
            } }
            state.error?.let { message -> item { ThemeCard { Column(Modifier.padding(16.dp)) {
                ThemeText(message); ThemeButton(stringResource(R.string.theme_retry)) {
                    state.failedDownload?.let(viewModel::download) ?: viewModel.refresh(localOnly)
                }
            } } } }
            state.paused?.let { theme -> item { ThemeCard { Column(Modifier.padding(16.dp)) {
                ThemeText(theme.name)
                FlowRow { ThemeButton(stringResource(R.string.theme_download_resume)) { viewModel.download(theme) }; ThemeButton(stringResource(R.string.theme_download_cancel)) { viewModel.discardPaused() } }
            } } } }
            if (list.isEmpty() && (localOnly || !state.loading)) item { ThemeText(if (localOnly) stringResource(R.string.theme_library_empty) else stringResource(R.string.theme_store_empty), Modifier.padding(24.dp), true) }
            items(list, key = { viewModel.repository.identity(it) }) { theme ->
                val preview by produceState<File?>(null, theme.preview) { value = viewModel.repository.preview(theme.preview, cachedOnly = localOnly) }
                ThemeCard(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp))) {
                        if (preview != null) ThemeImage(preview,Modifier.fillMaxSize(),theme.name)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.65f)))))
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                            // Cover overlays intentionally use white; component chrome uses the current skin.
                            when (com.maplesu.core.ui.LocalUiMode.current) {
                                com.maplesu.core.ui.UiMode.Miuix -> top.yukonga.miuix.kmp.basic.Text(theme.name,color=Color.White)
                                com.maplesu.core.ui.UiMode.Material -> androidx.compose.material3.Text(theme.name,color=Color.White)
                            }
                        }
                    }
                    ThemeRow(theme.name, "${theme.author} · ${theme.version} · ${theme.type}", Icons.Rounded.Palette) { selectedId = viewModel.repository.identity(theme) }
                    if (prefs.getString("theme_identity", "") == viewModel.repository.identity(theme)) ThemeText(
                        if (prefs.getBoolean("theme_modified", false)) stringResource(R.string.theme_current_modified) else stringResource(R.string.theme_current), Modifier.padding(horizontal=16.dp), true)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),horizontalArrangement = Arrangement.End) {
                        val downloaded = state.local.any { viewModel.repository.samePackage(it, theme) }
                        ThemeButton(if (downloaded) stringResource(R.string.theme_apply) else stringResource(R.string.theme_download), state.downloading == null) {
                            if (downloaded) operations.prepare(Uri.fromFile(viewModel.repository.packageFile(theme)), viewModel.repository.identity(theme), theme.metadata())
                            else requestDownload(theme)
                        }
                        if (localOnly) ThemeButton(stringResource(R.string.theme_delete)) { deletingId = viewModel.repository.identity(theme) }
                    }
                }
            }
        }
    }
    if (chooseSource) {
        val selectedSource = runCatching { ThemeStoreSource.resolve(sourceDraft,customDraft) }.getOrNull()
        ThemeDialog(stringResource(R.string.theme_catalog_source), { chooseSource = false },
            enabled=selectedSource != null,onConfirm = {
                runCatching { ThemeStoreSource.resolve(sourceDraft,customDraft) }.getOrNull()?.let {
                    chooseSource = false; selectedId = null
                    query = ""; type = ""; source = ""; author = ""
                    viewModel.refresh(source=it)
                }
            }) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("maplesu","finasu","sukisu","custom").forEach { id ->
                    ThemeChoice(catalogSourceLabel(id),sourceDraft == id) { sourceDraft = id }
                }
            }
            if (sourceDraft == "custom") {
                ThemeInput(stringResource(R.string.theme_catalog_url),customDraft) { customDraft = it }
                ThemeText(stringResource(if (customDraft.isNotBlank() && selectedSource == null)
                    R.string.theme_catalog_url_invalid else R.string.theme_catalog_url_hint),secondary=true)
            } else ThemeText(stringResource(if (sourceDraft == "maplesu")
                R.string.theme_catalog_maple_summary else if (sourceDraft == "sukisu")
                R.string.theme_catalog_sukisu_summary else R.string.theme_catalog_fina_summary),secondary=true)
        }
    }
    if (intro || chooseMode) ThemeDialog(stringResource(R.string.theme_download_mode_title), { intro = false; chooseMode = false; modeDraft = mode }, onConfirm = {
        mode = modeDraft
        prefs.edit().putString("theme_store_mode",mode).putBoolean("theme_store_intro",true).apply(); intro = false; chooseMode = false
    }) {
        ThemeText(stringResource(R.string.theme_download_mode_message))
        ThemeDownloadChoices(modeDraft) { modeDraft=it }
    }
    if (export) ThemeMetadataDialog(ThemeMetadata.read(json), { export=false }) {
        export=false; operations.export(it)
    }
    if (filters) ThemeDialog(stringResource(R.string.theme_filter_sort), { filters = false }, onConfirm = {
        type = typeDraft; source = filterSourceDraft; author = authorDraft; sorted = sortedDraft; filters = false
    }) {
        ThemeChoice(stringResource(R.string.theme_sort_default),!sortedDraft) { sortedDraft = false }
        ThemeChoice(stringResource(R.string.theme_sort_name),sortedDraft) { sortedDraft = true }
        FlowRow { listOf("" to stringResource(R.string.theme_all), "phone" to stringResource(R.string.theme_phone), "tablet" to stringResource(R.string.theme_tablet)).forEach { (key,label) -> ThemeButton((if (typeDraft == key) "✓ " else "")+label) { typeDraft = key } } }
        FlowRow { listOf("" to stringResource(R.string.theme_source_all), "official" to stringResource(R.string.theme_source_official), "third_party" to stringResource(R.string.theme_source_third_party)).forEach { (key,label) -> ThemeButton((if (filterSourceDraft == key) "✓ " else "")+label) { filterSourceDraft = key } } }
        ThemeButton(stringResource(R.string.theme_author_all)) { authorDraft = "" }
        all.map { it.author }.distinct().sorted().forEach { value -> ThemeButton((if (authorDraft == value) "✓ " else "")+value) { authorDraft = value } }
    }
    selectedId?.let { id -> all.firstOrNull { viewModel.repository.identity(it) == id }?.let { theme ->
        ThemeDialog(theme.name, { selectedId = null }, confirm = if (state.local.any { viewModel.repository.samePackage(it,theme) }) stringResource(R.string.theme_apply) else stringResource(R.string.theme_download), onConfirm = {
            selectedId = null
            if (state.local.any { viewModel.repository.samePackage(it,theme) }) operations.prepare(Uri.fromFile(viewModel.repository.packageFile(theme)), viewModel.repository.identity(theme), theme.metadata())
            else requestDownload(theme)
        }) { ThemeText("${theme.author} · ${theme.version} · ${theme.type}"); ThemeText(theme.description) }
    } }
    deletingId?.let { id -> state.local.firstOrNull { viewModel.repository.identity(it) == id }?.let { theme ->
        ThemeDialog(stringResource(R.string.theme_delete_title), { deletingId = null }, confirm = stringResource(R.string.theme_delete), onConfirm = { deletingId = null; viewModel.delete(theme) }) {
            ThemeText(stringResource(R.string.theme_delete_message, theme.name))
        }
    } }
    state.downloading?.let {
        ThemeDialog(stringResource(R.string.theme_downloading), viewModel::pause, confirm = stringResource(R.string.theme_pause), onConfirm = viewModel::pause) {
            ThemeProgress(if (state.total > 0) state.done.toFloat()/state.total else null)
            ThemeText(if (state.total > 0) "${state.done * 100 / state.total}% · ${state.done/1024} / ${state.total/1024} KiB" else "${state.done/1024} KiB")
            ThemeText(stringResource(R.string.theme_download_pause_hint), secondary = true)
        }
    }
    state.completed?.let { theme ->
        ThemeDialog(stringResource(R.string.theme_download_done), viewModel::dismissCompleted, confirm = stringResource(R.string.theme_apply), onConfirm = {
            viewModel.dismissCompleted(); operations.prepare(Uri.fromFile(viewModel.repository.packageFile(theme)), viewModel.repository.identity(theme), theme.metadata())
        }) {
            Column(Modifier.fillMaxWidth().testTag("theme-download-result"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                ThemeText(theme.name)
                val openLibrary = { viewModel.dismissCompleted(); navigator.push(Route.MyThemes) }
                val button = Modifier.fillMaxWidth().testTag("theme-open-library")
                when (LocalUiMode.current) {
                    UiMode.Miuix -> top.yukonga.miuix.kmp.basic.TextButton(
                        text=stringResource(R.string.theme_open_library),modifier=button,enabled=!busy,onClick=openLibrary)
                    UiMode.Material -> androidx.compose.material3.FilledTonalButton(modifier=button,enabled=!busy,onClick=openLibrary) {
                        androidx.compose.material3.Text(stringResource(R.string.theme_open_library))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeStoreAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val available = enabled && !LocalThemeBusy.current
    when (LocalUiMode.current) {
        UiMode.Miuix -> top.yukonga.miuix.kmp.basic.IconButton(onClick=onClick,enabled=available) {
            top.yukonga.miuix.kmp.basic.Icon(icon,label)
        }
        UiMode.Material -> androidx.compose.material3.IconButton(onClick=onClick,enabled=available) {
            androidx.compose.material3.Icon(icon,label)
        }
    }
}

@Composable
private fun catalogSourceLabel(id: String): String = when (id) {
    "maplesu" -> "MapleSU"
    "finasu" -> "FinaSU"
    "sukisu" -> "SukiSU / Nova"
    else -> stringResource(R.string.theme_catalog_custom)
}


