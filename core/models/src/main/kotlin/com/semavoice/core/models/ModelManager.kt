package com.semavoice.core.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.semavoice.core.common.AppError
import com.semavoice.core.common.Language
import com.semavoice.core.common.Outcome
import com.semavoice.core.common.SemaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

sealed class InstallState {
    data object NotInstalled : InstallState()
    data class Downloading(val progress: Float, val bytesDone: Long, val bytesTotal: Long, val currentFile: String) : InstallState()
    data class Verifying(val file: String) : InstallState()
    data class Installed(val version: String, val sizeBytes: Long) : InstallState()
    data class Failed(val error: AppError) : InstallState()
}

data class ModelState(
    val info: ModelInfo,
    val install: InstallState,
    val compatibility: CompatibilityRules.Verdict,
) {
    val isInstalled: Boolean get() = install is InstallState.Installed
    val isUsable: Boolean get() = isInstalled && (compatibility is CompatibilityRules.Verdict.Ok || compatibility is CompatibilityRules.Verdict.SlowTier)
}

@Serializable
private data class InstalledManifest(val version: String, val files: Map<String, String>)

/**
 * Owns the on-disk model store (filesDir/models/<id>/) and exposes install state for the
 * catalog. Downloads run in a supervisor scope so they survive screen rotation; the UI
 * only observes [models].
 */
class ModelManager(
    private val context: Context,
    private val downloader: ModelDownloader = ModelDownloader(),
    private val compatibility: DeviceCompatibility = DeviceCompatibility(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val jobs = HashMap<String, Job>()

    private val _models = MutableStateFlow<List<ModelState>>(emptyList())
    val models: StateFlow<List<ModelState>> = _models.asStateFlow()

    @Volatile private var catalog: List<ModelInfo> = ModelCatalog.builtIn.filter(ModelCatalog::isPublishable)
    private val remoteCatalog = RemoteCatalog(File(root, "remote-catalog.json"))

    val device: DeviceProfile by lazy { compatibility.profile() }

    init {
        mergeCatalog(remoteCatalog.loadCached(ModelCatalog.AMHARIC_MODEL_BASE_URL))
        refresh()
    }

    /** Fetches `catalog.json` from [catalogUrl] (default: next to the Amharic models) and merges it. Silent on failure. */
    suspend fun refreshRemoteCatalog(catalogUrl: String = "${ModelCatalog.AMHARIC_MODEL_BASE_URL}/catalog.json") {
        if (!isOnline()) return
        val extra = remoteCatalog.fetch(catalogUrl)
        if (extra.isNotEmpty()) mergeCatalog(extra)
    }

    /** Merge entries from a remote catalog (e.g. Amharic fine-tunes with filled checksums). */
    fun mergeCatalog(extra: List<ModelInfo>) {
        val byId = (catalog + extra.filter(ModelCatalog::isPublishable)).associateBy { it.id }
        catalog = byId.values.toList()
        refresh()
    }

    fun refresh() {
        val dev = device
        _models.value = catalog.map { info ->
            val current = _models.value.firstOrNull { it.info.id == info.id }?.install
            val install = if (current is InstallState.Downloading || current is InstallState.Verifying) current else readInstalled(info)
            ModelState(info, install, CompatibilityRules.check(info, dev))
        }
    }

    fun modelDir(id: String): File = File(root, id)
    fun filePath(id: String, fileName: String): File = File(modelDir(id), fileName)

    private fun readInstalled(info: ModelInfo): InstallState {
        val manifest = File(modelDir(info.id), MANIFEST)
        if (!manifest.exists()) return InstallState.NotInstalled
        val m = runCatching { json.decodeFromString<InstalledManifest>(manifest.readText()) }.getOrNull()
            ?: return InstallState.NotInstalled
        val allPresent = info.files.all { f -> filePath(info.id, f.name).let { it.exists() && it.length() == f.sizeBytes } && m.files[f.name] == f.sha256 }
        return if (allPresent) InstallState.Installed(m.version, info.totalBytes) else InstallState.NotInstalled
    }

    fun installedPath(id: String, fileName: String): String? {
        val st = _models.value.firstOrNull { it.info.id == id } ?: return null
        return if (st.isInstalled) filePath(id, fileName).absolutePath else null
    }

    fun installedSpeechModels(): List<ModelState> = _models.value.filter { it.info.kind == ModelKind.SPEECH && it.isUsable }
    fun installedTranslationModels(): List<ModelState> = _models.value.filter { it.info.kind == ModelKind.TRANSLATION && it.isUsable }

    /** Picks the best installed speech model for a language: prefers models listing the language, then size. */
    fun bestSpeechModelFor(language: Language, preferredId: String = ""): ModelState? {
        val usable = installedSpeechModels()
        preferredId.takeIf { it.isNotEmpty() }?.let { id -> usable.firstOrNull { it.info.id == id && (language == Language.AUTO || language in it.info.languages) }?.let { return it } }
        // Auto-detect needs a multilingual model (Whisper); CTC models are single-language.
        val candidates = if (language == Language.AUTO) usable.filter { Language.ENGLISH in it.info.languages } .ifEmpty { usable }
            else usable.filter { language in it.info.languages }
        // Prefer measured quality rank when present, then size.
        return candidates.maxWithOrNull(compareBy<ModelState> { it.info.qualityRank }.thenBy { it.info.totalBytes })
    }

    fun translationModelFor(from: Language, to: Language, preferredId: String = ""): ModelState? {
        val usable = installedTranslationModels().filter { st -> st.info.directions.any { it.from == from && it.to == to } }
        preferredId.takeIf { it.isNotEmpty() }?.let { id -> usable.firstOrNull { it.info.id == id }?.let { return it } }
        return usable.maxByOrNull { it.info.totalBytes }
    }

    fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Pre-flight checks the UI can show before starting a download. */
    fun preflight(info: ModelInfo, wifiOnly: Boolean): AppError? {
        if (!isOnline()) return AppError.NetworkUnavailable()
        if (wifiOnly && !isWifiConnected()) return AppError.CellularDownloadBlocked()
        val needed = info.totalBytes + 50L * 1024 * 1024
        val avail = compatibility.availableStorageBytes(root)
        if (avail < needed) return AppError.InsufficientStorage(needed, avail)
        val verdict = CompatibilityRules.check(info, device)
        if (verdict is CompatibilityRules.Verdict.TooLittleRam) return AppError.OutOfMemoryRisk(info.id, verdict.requiredMb, verdict.availableMb)
        if (verdict is CompatibilityRules.Verdict.UnsupportedAbi) return AppError.UnsupportedDevice("ABI ${verdict.abi}")
        if (verdict is CompatibilityRules.Verdict.MissingCpuFeatures) return AppError.UnsupportedDevice("CPU lacks ARMv8.2 features")
        return null
    }

    fun download(id: String, wifiOnly: Boolean): Outcome<Unit> {
        val info = catalog.firstOrNull { it.id == id } ?: return Outcome.Failure(AppError.ModelNotInstalled(id))
        preflight(info, wifiOnly)?.let { setState(id, InstallState.Failed(it)); return Outcome.Failure(it) }
        if (jobs[id]?.isActive == true) return Outcome.Success(Unit)
        setState(id, InstallState.Downloading(0f, 0, info.totalBytes, info.files.first().name))
        jobs[id] = scope.launch {
            val dir = modelDir(id).apply { mkdirs() }
            var doneBefore = 0L
            val hashes = HashMap<String, String>()
            for (f in info.files) {
                val target = File(dir, f.name)
                if (target.exists() && target.length() == f.sizeBytes && ModelDownloader.sha256(target).equals(f.sha256, true)) {
                    hashes[f.name] = f.sha256; doneBefore += f.sizeBytes; continue
                }
                val result = downloader.download(id, f, target) { p ->
                    setState(id, InstallState.Downloading(((doneBefore + p.bytesDone).toFloat() / info.totalBytes).coerceIn(0f, 1f), doneBefore + p.bytesDone, info.totalBytes, f.name))
                }
                when (result) {
                    is Outcome.Failure -> { setState(id, InstallState.Failed(result.error)); return@launch }
                    is Outcome.Success -> { hashes[f.name] = f.sha256; doneBefore += f.sizeBytes }
                }
            }
            setState(id, InstallState.Verifying(MANIFEST))
            File(dir, MANIFEST).writeText(json.encodeToString(InstalledManifest(info.version, hashes)))
            setState(id, InstallState.Installed(info.version, info.totalBytes))
            SemaLog.i(TAG, "installed $id")
        }
        return Outcome.Success(Unit)
    }

    fun cancelDownload(id: String) {
        jobs.remove(id)?.cancel()
        setState(id, InstallState.NotInstalled)
    }

    suspend fun delete(id: String): Outcome<Unit> = withContext(Dispatchers.IO) {
        jobs.remove(id)?.cancel()
        val dir = modelDir(id)
        val ok = !dir.exists() || dir.deleteRecursively()
        setState(id, InstallState.NotInstalled)
        if (ok) Outcome.Success(Unit) else Outcome.Failure(AppError.Unknown(RuntimeException("delete failed")))
    }

    /** Re-hashes installed files; marks corrupt models as not installed. */
    suspend fun verify(id: String): Outcome<Boolean> = withContext(Dispatchers.IO) {
        val info = catalog.firstOrNull { it.id == id } ?: return@withContext Outcome.Failure(AppError.ModelNotInstalled(id))
        setState(id, InstallState.Verifying(info.files.first().name))
        val ok = info.files.all { f -> filePath(id, f.name).let { it.exists() && ModelDownloader.sha256(it).equals(f.sha256, true) } }
        if (ok) setState(id, InstallState.Installed(info.version, info.totalBytes)) else {
            modelDir(id).deleteRecursively()
            setState(id, InstallState.Failed(AppError.ModelCorrupt(id)))
        }
        Outcome.Success(ok)
    }

    fun storageUsedBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun setState(id: String, state: InstallState) {
        _models.update { list -> list.map { if (it.info.id == id) it.copy(install = state) else it } }
    }

    private companion object {
        const val TAG = "ModelManager"
        const val MANIFEST = "installed.json"
    }
}
