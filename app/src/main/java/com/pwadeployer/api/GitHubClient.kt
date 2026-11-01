package com.pwadeployer.api

import android.util.Base64
import com.pwadeployer.model.DeployFile
import com.pwadeployer.model.DeploymentResult
import com.pwadeployer.model.DeviceCodeResponse
import com.pwadeployer.model.GitHubUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Step 1 of GitHub OAuth Device Flow: Request device & user verification codes.
     */
    suspend fun requestDeviceCode(clientId: String): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "public_repo")
            .build()

        val request = Request.Builder()
            .url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        http.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Failed to initiate GitHub Device Flow (${response.code}): $bodyStr")
            }
            val json = JSONObject(bodyStr)
            if (json.has("error")) {
                throw IOException(json.optString("error_description", json.getString("error")))
            }
            DeviceCodeResponse(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
                expiresIn = json.optInt("expires_in", 900),
                interval = json.optInt("interval", 5)
            )
        }
    }

    /**
     * Step 2 of GitHub OAuth Device Flow: Poll for the user's access token after they enter the code.
     */
    suspend fun pollForAccessToken(
        clientId: String,
        deviceCode: String,
        initialIntervalSeconds: Int
    ): String = withContext(Dispatchers.IO) {
        var pollIntervalMs = (initialIntervalSeconds.coerceAtLeast(5)) * 1000L

        while (true) {
            delay(pollIntervalMs)

            val formBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_code", deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()

            val request = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .post(formBody)
                .build()

            http.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Token polling HTTP error (${response.code}): $bodyStr")
                }
                val json = JSONObject(bodyStr)
                val accessToken = json.optString("access_token", "")
                if (accessToken.isNotEmpty()) {
                    return@withContext accessToken
                }

                when (val error = json.optString("error")) {
                    "authorization_pending" -> {
                        // Continue polling
                    }
                    "slow_down" -> {
                        val newInterval = json.optInt("interval", 0)
                        pollIntervalMs = if (newInterval > 0) newInterval * 1000L else pollIntervalMs + 5000L
                    }
                    "expired_token" -> throw IOException("The verification code has expired. Please start again.")
                    "access_denied" -> throw IOException("Authorization was cancelled by the user.")
                    else -> {
                        val desc = json.optString("error_description", error.ifEmpty { "Unknown OAuth error" })
                        throw IOException(desc)
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }

    /**
     * Fetch the currently authenticated GitHub user profile.
     */
    suspend fun fetchAuthenticatedUser(token: String): GitHubUser = withContext(Dispatchers.IO) {
        val request = githubApiRequest("https://api.github.com/user", token)
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub authentication failed (${response.code}): ${extractErrorMessage(bodyStr)}")
            }
            val json = JSONObject(bodyStr)
            GitHubUser(
                login = json.getString("login"),
                name = json.optString("name").takeIf { it.isNotBlank() && it != "null" },
                avatarUrl = json.optString("avatar_url").takeIf { it.isNotBlank() && it != "null" },
                htmlUrl = json.optString("html_url", "https://github.com/${json.getString("login")}")
            )
        }
    }

    /**
     * Deploy a list of files to a public GitHub repository and enable GitHub Pages on `main`.
     */
    suspend fun deployToGitHubPages(
        token: String,
        owner: String,
        repoName: String,
        description: String,
        files: List<DeployFile>,
        autoFixesApplied: List<String>,
        onProgress: (String) -> Unit
    ): DeploymentResult = withContext(Dispatchers.IO) {
        val sanitizedRepo = repoName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "-")
        if (sanitizedRepo.isEmpty()) {
            throw IllegalArgumentException("Repository name cannot be empty.")
        }

        // 1. Check if repository exists; if not, create it with auto_init = true so Git Data API is initialized
        onProgress("Checking or creating public repository '$owner/$sanitizedRepo'...")
        val defaultBranch = ensurePublicRepoInitialized(token, owner, sanitizedRepo, description)

        // 2. Get current HEAD commit SHA of default branch
        onProgress("Fetching branch '$defaultBranch' reference...")
        val headCommitSha = getBranchHeadSha(token, owner, sanitizedRepo, defaultBranch)

        // 3. Upload blobs for each file
        val treeEntries = JSONArray()
        files.forEachIndexed { index, file ->
            onProgress("Uploading file (${index + 1}/${files.size}): ${file.relativePath}...")
            val blobSha = createBlob(token, owner, sanitizedRepo, file.content)
            treeEntries.put(
                JSONObject().apply {
                    put("path", file.relativePath.trimStart('/'))
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", blobSha)
                }
            )
        }

        // 4. Create a new Git tree with all uploaded files
        onProgress("Creating Git tree with ${files.size} files...")
        val newTreeSha = createTree(token, owner, sanitizedRepo, treeEntries)

        // 5. Create a new commit pointing to the new tree
        onProgress("Creating Git commit...")
        val commitMessage = "Deploy PWA via Android PWA GitHub Deployer"
        val newCommitSha = createCommit(
            token = token,
            owner = owner,
            repo = sanitizedRepo,
            message = commitMessage,
            treeSha = newTreeSha,
            parentSha = headCommitSha
        )

        // 6. Update branch reference to point to the new commit
        onProgress("Updating '$defaultBranch' branch reference...")
        updateBranchRef(token, owner, sanitizedRepo, defaultBranch, newCommitSha)

        // 7. Enable GitHub Pages on the default branch root "/"
        onProgress("Enabling GitHub Pages on '$defaultBranch'...")
        val pagesUrl = enableGitHubPages(token, owner, sanitizedRepo, defaultBranch)

        DeploymentResult(
            repoUrl = "https://github.com/$owner/$sanitizedRepo",
            pagesUrl = pagesUrl,
            commitSha = newCommitSha,
            filesUploaded = files.size,
            autoFixesApplied = autoFixesApplied
        )
    }

    private fun ensurePublicRepoInitialized(
        token: String,
        owner: String,
        repo: String,
        description: String
    ): String {
        val getRepoReq = githubApiRequest("https://api.github.com/repos/$owner/$repo", token)
            .get()
            .build()

        http.newCall(getRepoReq).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string().orEmpty())
                val defaultBranch = json.optString("default_branch", "main").ifBlank { "main" }
                val isEmpty = json.optInt("size", 0) == 0
                if (isEmpty) {
                    // Ensure at least one file exists if repo was previously created completely empty
                    seedInitialCommitIfEmpty(token, owner, repo, defaultBranch)
                }
                return defaultBranch
            } else if (response.code != 404) {
                val errBody = response.body?.string().orEmpty()
                throw IOException("Error checking repository (${response.code}): ${extractErrorMessage(errBody)}")
            }
        }

        // Create new public repository with auto_init = true
        val createPayload = JSONObject().apply {
            put("name", repo)
            put("description", description.ifBlank { "Progressive Web App deployed via GitHub Pages" })
            put("private", false)
            put("auto_init", true)
        }

        val createReq = githubApiRequest("https://api.github.com/user/repos", token)
            .post(createPayload.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(createReq).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Failed to create repository '$repo' (${response.code}): ${extractErrorMessage(bodyStr)}")
            }
            val json = JSONObject(bodyStr)
            return json.optString("default_branch", "main").ifBlank { "main" }
        }
    }

    private fun seedInitialCommitIfEmpty(token: String, owner: String, repo: String, branch: String) {
        val refReq = githubApiRequest("https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch", token)
            .get()
            .build()
        http.newCall(refReq).execute().use { res ->
            if (res.isSuccessful) return
        }

        // Use Contents API to create initial .nojekyll file so Git Data API has a valid repository state
        val payload = JSONObject().apply {
            put("message", "Initialize repository for GitHub Pages")
            put("content", Base64.encodeToString("".toByteArray(), Base64.NO_WRAP))
            put("branch", branch)
        }
        val putReq = githubApiRequest("https://api.github.com/repos/$owner/$repo/contents/.nojekyll", token)
            .put(payload.toString().toRequestBody(jsonMediaType))
            .build()
        http.newCall(putReq).execute().close()
    }

    private fun getBranchHeadSha(token: String, owner: String, repo: String, branch: String): String {
        // Retry briefly if auto_init just completed on GitHub's backend
        var lastError = ""
        repeat(4) { attempt ->
            val req = githubApiRequest("https://api.github.com/repos/$owner/$repo/git/ref/heads/$branch", token)
                .get()
                .build()
            http.newCall(req).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    return json.getJSONObject("object").getString("sha")
                }
                lastError = "HTTP ${response.code}: ${extractErrorMessage(bodyStr)}"
            }
            if (attempt == 1) {
                seedInitialCommitIfEmpty(token, owner, repo, branch)
            }
            Thread.sleep(1000L)
        }
        throw IOException("Could not resolve HEAD of branch '$branch': $lastError")
    }

    private fun createBlob(token: String, owner: String, repo: String, bytes: ByteArray): String {
        val base64Content = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("content", base64Content)
            put("encoding", "base64")
        }
        val req = githubApiRequest("https://api.github.com/repos/$owner/$repo/git/blobs", token)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(req).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Failed to upload file blob (${response.code}): ${extractErrorMessage(bodyStr)}")
            }
            return JSONObject(bodyStr).getString("sha")
        }
    }

    private fun createTree(token: String, owner: String, repo: String, treeEntries: JSONArray): String {
        val payload = JSONObject().apply {
            put("tree", treeEntries)
        }
        val req = githubApiRequest("https://api.github.com/repos/$owner/$repo/git/trees", token)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(req).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Failed to create Git tree (${response.code}): ${extractErrorMessage(bodyStr)}")
            }
            return JSONObject(bodyStr).getString("sha")
        }
    }

    private fun createCommit(
        token: String,
        owner: String,
        repo: String,
        message: String,
        treeSha: String,
        parentSha: String
    ): String {
        val payload = JSONObject().apply {
            put("message", message)
            put("tree", treeSha)
            put("parents", JSONArray().put(parentSha))
        }
        val req = githubApiRequest("https://api.github.com/repos/$owner/$repo/git/commits", token)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(req).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Failed to create Git commit (${response.code}): ${extractErrorMessage(bodyStr)}")
            }
            return JSONObject(bodyStr).getString("sha")
        }
    }

    private fun updateBranchRef(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        commitSha: String
    ) {
        val payload = JSONObject().apply {
            put("sha", commitSha)
            put("force", true)
        }
        val req = githubApiRequest("https://api.github.com/repos/$owner/$repo/git/refs/heads/$branch", token)
            .patch(payload.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(req).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Failed to update branch '$branch' (${response.code}): ${extractErrorMessage(bodyStr)}")
            }
        }
    }

    private fun enableGitHubPages(
        token: String,
        owner: String,
        repo: String,
        branch: String
    ): String {
        val defaultPagesUrl = if (repo.equals("$owner.github.io", ignoreCase = true)) {
            "https://$owner.github.io/"
        } else {
            "https://$owner.github.io/$repo/"
        }

        // First check if GitHub Pages is already enabled
        val getPagesReq = githubApiRequest("https://api.github.com/repos/$owner/$repo/pages", token)
            .get()
            .build()

        http.newCall(getPagesReq).execute().use { response ->
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string().orEmpty())
                return json.optString("html_url", defaultPagesUrl).ifBlank { defaultPagesUrl }
            }
        }

        // Enable GitHub Pages from branch root
        val payload = JSONObject().apply {
            put(
                "source",
                JSONObject().apply {
                    put("branch", branch)
                    put("path", "/")
                }
            )
        }
        val createPagesReq = githubApiRequest("https://api.github.com/repos/$owner/$repo/pages", token)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        http.newCall(createPagesReq).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (response.isSuccessful || response.code == 409) {
                val json = runCatching { JSONObject(bodyStr) }.getOrNull()
                return json?.optString("html_url", defaultPagesUrl)?.ifBlank { defaultPagesUrl } ?: defaultPagesUrl
            }
            // Even if Pages creation returns a transient warning, return the canonical Pages URL
            return defaultPagesUrl
        }
    }

    private fun githubApiRequest(url: String, token: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun extractErrorMessage(bodyStr: String): String {
        return try {
            val json = JSONObject(bodyStr)
            json.optString("message", bodyStr)
        } catch (_: Exception) {
            bodyStr.ifBlank { "Unknown error" }
        }
    }
}
