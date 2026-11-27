package com.maplesu.core.ui.screen.settings

import android.net.Uri
import androidx.compose.ui.res.stringResource
import com.maplesu.core.R
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maplesu.core.ksuApp
import com.maplesu.core.theme.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ThemeOperationState(
    val busy: Boolean = false, val prepared: PreparedTheme? = null, val error: String? = null,
    val localeToApply: String? = null, val localeRequest: Long = 0L, val exportedUri: String? = null,
    val exportedName: String = "MapleSU.fpt",
)

class ThemeOperations(private val saved: SavedStateHandle) : ViewModel() {
    private val repository = ThemeRepository(ksuApp)
    private val mutable = MutableStateFlow(ThemeOperationState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var editOperation: Job? = null
    private var pendingPreparation: Job? = null
    private var preparation: Job? = null
    private var previewRequest = 0L
    init {
        runOperation {
            repository.recover()
            saved.get<String>("prepared")?.let { path ->
                val directory = java.io.File(path)
                val restored = withContext(Dispatchers.IO) {
                    if (!java.io.File(directory, "original.json").isFile) null else {
                        val json = org.json.JSONObject(java.io.File(directory, "original.json").readText())
                        val files = java.io.File(directory, "assets").listFiles()?.associateBy { it.name }.orEmpty()
                        val normalized = ThemeDocument.normalize(json, files)
                        PreparedTheme(path, ThemeMetadata.read(json), normalized.second + ThemeAssetValidation.warnings(files))
                    }
                }
                if (restored != null) mutable.value = mutable.value.copy(prepared = restored)
                else { saved.remove<String>("prepared"); saved.remove<String>("identity") }
            }
        }
    }
    fun prepare(uri: Uri, identity: String = "", metadata: ThemeMetadata? = null) {
        val request = ++previewRequest
        pendingPreparation?.cancel()
        preparation?.cancel()
        val previous = operation
        if (!mutable.value.busy || previous === preparation) clearPrepared()?.let { discard(it, request) }
        pendingPreparation = viewModelScope.launch {
            previous?.join()
            if (request != previewRequest) return@launch
            clearPrepared()?.let { discard(it, request) }
            runOperation {
                val prepared = repository.prepare(uri, metadata)
                saved["prepared"] = prepared.path
                saved["identity"] = identity
                mutable.value = mutable.value.copy(prepared = prepared)
            }
            preparation = operation
        }
    }
    fun cancel() {
        val request = ++previewRequest
        pendingPreparation?.cancel()
        // A committed apply/reset transaction retains its original busy protection.
        if (mutable.value.busy && operation !== preparation) return
        preparation?.cancel()
        val prepared = clearPrepared()
        mutable.value = ThemeOperationState()
        prepared?.let { discard(it, request) }
    }
    private fun clearPrepared(): PreparedTheme? {
        val prepared = mutable.value.prepared
        saved.remove<String>("prepared")
        saved.remove<String>("identity")
        mutable.value = mutable.value.copy(prepared = null)
        return prepared
    }
    private fun discard(prepared: PreparedTheme, request: Long) {
        // Capture this preview before launching: later imports own different staging paths.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try { repository.discard(prepared) }
                catch (e: Exception) {
                    android.util.Log.e("ThemeOperations", "Theme preview cleanup failed", e)
                    if (request == previewRequest) error(e)
                }
            }
        }
    }
    fun apply() {
        val prepared = mutable.value.prepared ?: return
        runOperation {
            repository.apply(prepared, saved.get<String>("identity").orEmpty())
            saved.remove<String>("prepared")
            saved.remove<String>("identity")
            val applied = ThemeRuntime.document(ksuApp)
            mutable.value = mutable.value.copy(prepared = null,
                localeToApply = applied.optString("appLanguage").takeIf { applied.has("appLanguage") },
                localeRequest = if (applied.has("appLanguage")) mutable.value.localeRequest + 1 else mutable.value.localeRequest)
            Toast.makeText(ksuApp, ksuApp.getString(R.string.theme_applied), Toast.LENGTH_SHORT).show()
        }
    }
    fun reset(comic: Boolean = false) = runOperation {
        repository.reset(comic)
        ThemeMusic.requested = false
        ThemeSound.stop()
        mutable.value = mutable.value.copy(localeToApply = "", localeRequest = mutable.value.localeRequest + 1)
        Toast.makeText(ksuApp, ksuApp.getString(R.string.theme_reset_done), Toast.LENGTH_SHORT).show()
    }
    fun export(metadata: ThemeMetadata) = runOperation {
        val uri = repository.export(metadata)
        saved["exported_uri"] = uri.toString()
        val name = metadata.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80).ifBlank { "MapleSU" }
        mutable.value = mutable.value.copy(exportedUri = uri.toString(), exportedName = "$name.fpt")
    }
    fun edit(changes: Map<String, Any>) {
        if (mutable.value.busy) return
        val previous = editOperation
        mutable.value = mutable.value.copy(error = null)
        editOperation = viewModelScope.launch {
            previous?.join()
            try { repository.edit(changes) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e) }
        }
    }
    internal fun homeLayout(layout: ThemeHomeLayout) = runOperation { repository.homeLayout(layout) }
    fun asset(base: String, uri: Uri, extension: String, changes: Map<String, Any>) = runOperation { repository.setAsset(base, uri, extension, changes) }
    fun clearAsset(base: String, changes: Map<String, Any> = emptyMap()) = runOperation { repository.setAsset(base, null, "png", changes) }
    fun dismissError() { mutable.value = mutable.value.copy(error = null) }
    fun error(error: Throwable) { mutable.value = mutable.value.copy(error = error.message ?: error.javaClass.simpleName) }
    fun languageApplied() { mutable.value = mutable.value.copy(localeToApply = null, localeRequest = 0L) }
    fun dismissExport() { mutable.value = mutable.value.copy(exportedUri = null) }
    private fun runOperation(block: suspend () -> Unit) {
        if (operation?.isCompleted == false) return
        val pendingEdits = editOperation
        mutable.value = mutable.value.copy(busy = true, error = null)
        operation = viewModelScope.launch {
            try { pendingEdits?.join(); block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: e.javaClass.simpleName) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}

internal val LocalThemeOperations = staticCompositionLocalOf<ThemeOperations> { error("Theme operations host missing") }

@Composable
fun ThemeOperationsHost(content: @Composable () -> Unit) {
    val operations = viewModel<ThemeOperations>()
    val state by operations.state.collectAsStateWithLifecycle()
    ThemeStartupResource(!state.busy)
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(state.localeRequest, state.busy) {
        val tags = state.localeToApply ?: return@LaunchedEffect
        if (state.localeRequest == 0L) return@LaunchedEffect
        if (state.busy) return@LaunchedEffect
        operations.languageApplied()
        if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java)
            .applicationLocales = android.os.LocaleList.forLanguageTags(tags)
        else {
            val expected = if (tags.isEmpty()) android.content.res.Resources.getSystem().configuration.locales else android.os.LocaleList.forLanguageTags(tags)
            if (context.resources.configuration.locales != expected) (context as? android.app.Activity)?.recreate()
        }
    }
    CompositionLocalProvider(LocalThemeOperations provides operations, LocalThemeBusy provides state.busy) {
        content()
        state.prepared?.let { prepared ->
            ThemeDialog(stringResource(R.string.theme_package_import), operations::cancel, confirm = if (state.busy) stringResource(R.string.theme_applying) else stringResource(R.string.theme_apply), enabled = !state.busy, onConfirm = operations::apply) {
                val preview by produceState<java.io.File?>(null, prepared.path) {
                    value = withContext(Dispatchers.IO) {
                        val assets = java.io.File(prepared.path,"assets").listFiles()?.associateBy { it.name }.orEmpty()
                        val config = runCatching { org.json.JSONObject(java.io.File(prepared.path,"applied.json").readText()) }.getOrDefault(org.json.JSONObject())
                        ThemeDocument.seedImage(config,assets) ?: ThemeResourcePolicy.select("background",assets,config)
                    }
                }
                if (preview != null) Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp))
                    .testTag("theme-import-preview")) {
                    ThemeImage(preview,Modifier.fillMaxSize(),prepared.metadata.name)
                }
                ThemeText(prepared.metadata.name)
                ThemeText("${prepared.metadata.author} · ${prepared.metadata.version} · ${prepared.metadata.type}", secondary = true)
                if (prepared.metadata.description.isNotBlank()) ThemeText(prepared.metadata.description)
                ThemeText(stringResource(R.string.theme_import_scope), secondary = true)
                if (prepared.warnings.isEmpty()) ThemeText(stringResource(R.string.theme_import_ready))
                else { ThemeText(stringResource(R.string.theme_compatibility)); prepared.warnings.forEach { ThemeText("• $it", secondary = true) } }
            }
        }
        state.exportedUri?.let { value ->
            val uri = android.net.Uri.parse(value)
            ThemeDialog(stringResource(R.string.theme_exported), operations::dismissExport, confirm = stringResource(R.string.theme_share), onConfirm = {
                operations.dismissExport()
                runCatching {
                    context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("application/octet-stream").putExtra(android.content.Intent.EXTRA_STREAM,uri)
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), context.getString(R.string.theme_share_package)))
                }.onFailure(operations::error)
            }) {
                ThemeText("Download/MapleSU/Themes/${state.exportedName}")
                ThemeButton(stringResource(R.string.theme_open_file)) {
                    operations.dismissExport()
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri,"application/octet-stream")
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure(operations::error)
                }
            }
        }
        state.error?.let { error ->
            ThemeDialog(stringResource(R.string.theme_error), operations::dismissError, onConfirm = operations::dismissError) { ThemeText(error) }
        }
    }
}
