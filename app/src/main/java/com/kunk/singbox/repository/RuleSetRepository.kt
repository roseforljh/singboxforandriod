package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 规则集仓库 - 负责规则集的下载、缓存和管理
 */
class RuleSetRepository(private val context: Context) {

    companion object {
        private const val TAG = "RuleSetRepository"
        private const val AD_BLOCK_TAG = "geosite-category-ads-all"
        private const val AD_BLOCK_URL_SUFFIX = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs"

        @Volatile
        private var instance: RuleSetRepository? = null

        fun getInstance(context: Context): RuleSetRepository {
            return instance ?: synchronized(this) {
                instance ?: RuleSetRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val ruleSetDir: File
        get() = File(context.filesDir, "rulesets").also { it.mkdirs() }

    private val settingsRepository = SettingsRepository.getInstance(context)

    /**
     * 确保所有需要的规则集都已就绪（本地存在）
     * 如果不存在，尝试从 assets 复制或下载
     * @param forceUpdate 是否强制更新（忽略过期时间）
     * @return 是否所有规则集都可用（至少有旧缓存）
     */
    suspend fun ensureRuleSetsReady(
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        var allReady = true

        // 1. 处理广告拦截规则集
        if (settings.blockAds) {
            val adBlockFile = getRuleSetFile(AD_BLOCK_TAG)

            // 尝试从 assets 安装 baseline
            if (!adBlockFile.exists()) {
                onProgress("正在安装基础规则集...")
                installBaselineRuleSet(AD_BLOCK_TAG, adBlockFile)
            }

            if (allowNetwork && (!adBlockFile.exists() || (forceUpdate && isExpired(adBlockFile)))) {
                onProgress("正在更新广告规则集...")
                val success = downloadAdBlockRuleSet(settings)
                if (!success && !adBlockFile.exists()) {
                    allReady = false
                    Log.e(TAG, "Failed to download ad block rule set and no cache available")
                }
            } else if (!adBlockFile.exists()) {
                allReady = false
                Log.w(TAG, "Ad block rule set missing, and network download is disabled")
            }
        }

        // 2. 处理自定义远程规则集
        settings.ruleSets.filter { it.enabled && it.type == RuleSetType.REMOTE }.forEach { ruleSet ->
            val file = getRuleSetFile(ruleSet.tag)

            if (!file.exists()) {
                installBaselineRuleSet(ruleSet.tag, file)
            }

            if (allowNetwork && (!file.exists() || (forceUpdate && isExpired(file)))) {
                onProgress("正在更新规则集: ${ruleSet.tag}...")
                val success = downloadCustomRuleSet(ruleSet)
                if (!success && !file.exists()) {
                    allReady = false
                    Log.e(TAG, "Failed to download rule set ${ruleSet.tag} and no cache available")
                }
            } else if (!file.exists()) {
                allReady = false
                Log.w(TAG, "Rule set ${ruleSet.tag} missing, and network download is disabled")
            }
        }

        allReady
    }

    /**
     * 预下载指定规则集（用于添加时立刻拉取，避免启动阶段阻塞）
     */
    suspend fun prefetchRuleSet(
        ruleSet: RuleSet,
        forceUpdate: Boolean = false,
        allowNetwork: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ruleSet.enabled) return@withContext true

        return@withContext when (ruleSet.type) {
            RuleSetType.LOCAL -> File(ruleSet.path).exists()
            RuleSetType.REMOTE -> {
                val file = getRuleSetFile(ruleSet.tag)
                if (!file.exists()) {
                    installBaselineRuleSet(ruleSet.tag, file)
                }
                if (!allowNetwork) {
                    file.exists()
                } else if (!file.exists() || (forceUpdate && isExpired(file))) {
                    val success = downloadCustomRuleSet(ruleSet)
                    success || file.exists()
                } else {
                    true
                }
            }
        }
    }

    /**
     * 从 assets 安装基础规则集
     */
    private fun installBaselineRuleSet(tag: String, targetFile: File): Boolean {
        return try {
            val assetPath = "rulesets/$tag.srs"
            Log.d(TAG, "Installing baseline rule set from assets: $assetPath")
            
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Baseline rule set installed: ${targetFile.name}")
            true
        } catch (e: Exception) {
            // 可能是 assets 里没有这个文件，这是正常的（比如自定义规则集）
            Log.w(TAG, "Baseline rule set not found in assets: $tag")
            false
        }
    }

    /**
     * 获取规则集本地文件路径
     */
    fun getRuleSetPath(tag: String): String {
        return getRuleSetFile(tag).absolutePath
    }

    private fun getRuleSetFile(tag: String): File {
        return File(ruleSetDir, "$tag.srs")
    }

    private fun isExpired(file: File): Boolean {
        // 简单策略：超过 24 小时视为过期
        // 实际生产中可以配合 ETag 或 Last-Modified 检查，这里简化处理
        val lastModified = file.lastModified()
        val now = System.currentTimeMillis()
        return (now - lastModified) > 24 * 60 * 60 * 1000
    }

    private suspend fun downloadAdBlockRuleSet(settings: AppSettings): Boolean {
        val mirrorUrl = settings.ghProxyMirror.url
        val url = "$mirrorUrl$AD_BLOCK_URL_SUFFIX"
        return downloadFile(url, getRuleSetFile(AD_BLOCK_TAG))
    }

    private suspend fun downloadCustomRuleSet(ruleSet: RuleSet): Boolean {
        if (ruleSet.url.isBlank()) return false
        return downloadFile(ruleSet.url, getRuleSetFile(ruleSet.tag))
    }

    private suspend fun downloadFile(url: String, targetFile: File): Boolean {
        return try {
            Log.d(TAG, "Downloading rule set from: $url")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code}")
                    return false
                }

                val body = response.body ?: return false
                val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
                
                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)
                Log.i(TAG, "Rule set downloaded successfully: ${targetFile.name}")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            false
        }
    }
}