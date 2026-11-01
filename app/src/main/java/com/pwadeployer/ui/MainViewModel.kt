package com.pwadeployer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pwadeployer.api.GitHubClient
import com.pwadeployer.model.DeployFile
import com.pwadeployer.model.DeploymentResult
import com.pwadeployer.model.DeviceCodeResponse
import com.pwadeployer.model.GitHubUser
import com.pwadeployer.model.PwaValidationResult
import com.pwadeployer.pwa.PwaInspectorAndFixer
import com.pwadeployer.util.FileImportHelper
import com.pwadeployer.util.SecureTokenStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val isLoadingAuth: Boolean = false,
    val githubUser: GitHubUser? = null,
    val oauthClientId: String = SecureTokenStore.DEFAULT_GITHUB_DEVICE_CLIENT_ID,
    val deviceCodeFlow: DeviceCodeResponse? = null,
    val isPollingDeviceFlow: Boolean = false,
    val authError: String? = null,

    val rawFiles: List<DeployFile> = emptyList(),
    val pwaValidation: PwaValidationResult? = null,
    val repoName: String = "my-mobile-pwa",
    val appTitle: String = "My Mobile PWA",
    val repoDescription: String = "Progressive Web App deployed directly from Android",

    val isDeploying: Boolean = false,
    val deployProgressMessage: String? = null,
    val deployError: String? = null,
    val deploymentResult: DeploymentResult? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = SecureTokenStore(application)
    private val githubClient = GitHubClient()

    private val _uiState = MutableStateFlow(
        UiState(oauthClientId = tokenStore.oauthClientId)
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var deviceFlowPollingJob: Job? = null

    init {
        restoreSavedSession()
    }

    private fun restoreSavedSession() {
        val savedToken = tokenStore.accessToken
        if (!savedToken.isNullOrBlank()) {
            _uiState.update { it.copy(isLoadingAuth = true, authError = null) }
            viewModelScope.launch {
                runCatching {
                    githubClient.fetchAuthenticatedUser(savedToken)
                }.onSuccess { user ->
                    _uiState.update { it.copy(isLoadingAuth = false, githubUser = user) }
                }.onFailure {
                    tokenStore.clearAuth()
                    _uiState.update { it.copy(isLoadingAuth = false, githubUser = null) }
                }
            }
        }
    }

    fun updateOAuthClientId(newClientId: String) {
        tokenStore.oauthClientId = newClientId
        _uiState.update { it.copy(oauthClientId = newClientId) }
    }

    fun startGitHubDeviceFlow() {
        deviceFlowPollingJob?.cancel()
        val clientId = _uiState.value.oauthClientId.trim()
        if (clientId.isEmpty()) {
            _uiState.update { it.copy(authError = "OAuth Client ID cannot be empty.") }
            return
        }

        _uiState.update {
            it.copy(
                isLoadingAuth = true,
                authError = null,
                deviceCodeFlow = null,
                isPollingDeviceFlow = false
            )
        }

        deviceFlowPollingJob = viewModelScope.launch {
            runCatching {
                githubClient.requestDeviceCode(clientId)
            }.onSuccess { deviceCodeResp ->
                _uiState.update {
                    it.copy(
                        isLoadingAuth = false,
                        deviceCodeFlow = deviceCodeResp,
                        isPollingDeviceFlow = true
                    )
                }
                pollForToken(clientId, deviceCodeResp)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoadingAuth = false,
                        authError = err.message ?: "Failed to start GitHub OAuth Device Flow."
                    )
                }
            }
        }
    }

    private suspend fun pollForToken(clientId: String, deviceCodeResp: DeviceCodeResponse) {
        runCatching {
            val token = githubClient.pollForAccessToken(
                clientId = clientId,
                deviceCode = deviceCodeResp.deviceCode,
                initialIntervalSeconds = deviceCodeResp.interval
            )
            tokenStore.accessToken = token
            val user = githubClient.fetchAuthenticatedUser(token)
            Pair(token, user)
        }.onSuccess { (_, user) ->
            _uiState.update {
                it.copy(
                    githubUser = user,
                    deviceCodeFlow = null,
                    isPollingDeviceFlow = false,
                    authError = null
                )
            }
        }.onFailure { err ->
            if (err !is kotlinx.coroutines.CancellationException) {
                _uiState.update {
                    it.copy(
                        isPollingDeviceFlow = false,
                        deviceCodeFlow = null,
                        authError = err.message ?: "GitHub authorization failed."
                    )
                }
            }
        }
    }

    fun cancelDeviceFlow() {
        deviceFlowPollingJob?.cancel()
        _uiState.update {
            it.copy(
                isLoadingAuth = false,
                deviceCodeFlow = null,
                isPollingDeviceFlow = false
            )
        }
    }

    fun signOut() {
        deviceFlowPollingJob?.cancel()
        tokenStore.clearAuth()
        _uiState.update {
            it.copy(
                githubUser = null,
                deviceCodeFlow = null,
                isPollingDeviceFlow = false,
                authError = null
            )
        }
    }

    fun updateRepoName(name: String) {
        _uiState.update { it.copy(repoName = name) }
    }

    fun updateAppTitle(title: String) {
        _uiState.update { state ->
            val updatedValidation = if (state.rawFiles.isNotEmpty()) {
                PwaInspectorAndFixer.inspectAndEnsurePwa(state.rawFiles, title)
            } else null
            state.copy(appTitle = title, pwaValidation = updatedValidation)
        }
    }

    fun updateRepoDescription(description: String) {
        _uiState.update { it.copy(repoDescription = description) }
    }

    fun importFilesFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = FileImportHelper.importFromUris(getApplication(), uris)
            setImportedFiles(imported)
        }
    }

    fun importFolderFromTreeUri(treeUri: Uri) {
        viewModelScope.launch {
            val imported = FileImportHelper.importFromTreeUri(getApplication(), treeUri)
            setImportedFiles(imported)
        }
    }

    fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        viewModelScope.launch {
            val imported = FileImportHelper.importFromShareIntent(getApplication(), intent)
            if (imported.isNotEmpty()) {
                setImportedFiles(imported)
            }
        }
    }

    fun clearSelectedFiles() {
        _uiState.update {
            it.copy(
                rawFiles = emptyList(),
                pwaValidation = null,
                deployError = null,
                deploymentResult = null
            )
        }
    }

    private fun setImportedFiles(files: List<DeployFile>) {
        if (files.isEmpty()) return
        val currentState = _uiState.value
        val inferredTitle = inferHtmlTitle(files) ?: currentState.appTitle
        val inferredRepo = inferRepoSlug(inferredTitle, currentState.repoName)
        val validation = PwaInspectorAndFixer.inspectAndEnsurePwa(files, inferredTitle)
        _uiState.update {
            it.copy(
                rawFiles = files,
                appTitle = inferredTitle,
                repoName = inferredRepo,
                pwaValidation = validation,
                deployError = null,
                deploymentResult = null
            )
        }
    }

    private fun inferHtmlTitle(files: List<DeployFile>): String? {
        val htmlFile = files.firstOrNull {
            it.relativePath.endsWith(".html", ignoreCase = true) ||
                it.relativePath.endsWith(".htm", ignoreCase = true)
        } ?: return null
        val content = htmlFile.content.toString(Charsets.UTF_8)
        val match = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(content)
        val title = match?.groupValues?.getOrNull(1)?.trim()
        return title?.takeIf { it.isNotBlank() && it.length <= 50 }
    }

    private fun inferRepoSlug(title: String, fallback: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifBlank { fallback }
    }

    fun deployNow() {
        val state = _uiState.value
        val token = tokenStore.accessToken
        val user = state.githubUser
        if (token.isNullOrBlank() || user == null) {
            _uiState.update { it.copy(deployError = "Please sign in with GitHub first.") }
            return
        }

        val validation = state.pwaValidation
            ?: PwaInspectorAndFixer.inspectAndEnsurePwa(state.rawFiles, state.appTitle)

        if (validation.finalFiles.isEmpty()) {
            _uiState.update { it.copy(deployError = "Please select or share HTML/PWA files first.") }
            return
        }

        _uiState.update {
            it.copy(
                isDeploying = true,
                deployProgressMessage = "Starting deployment...",
                deployError = null,
                deploymentResult = null
            )
        }

        viewModelScope.launch {
            runCatching {
                githubClient.deployToGitHubPages(
                    token = token,
                    owner = user.login,
                    repoName = state.repoName,
                    description = state.repoDescription,
                    files = validation.finalFiles,
                    autoFixesApplied = validation.autoFixesApplied,
                    onProgress = { msg ->
                        _uiState.update { it.copy(deployProgressMessage = msg) }
                    }
                )
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isDeploying = false,
                        deployProgressMessage = null,
                        deploymentResult = result
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isDeploying = false,
                        deployProgressMessage = null,
                        deployError = err.message ?: "Deployment failed."
                    )
                }
            }
        }
    }
}
