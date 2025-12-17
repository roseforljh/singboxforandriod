package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.ui.screens.HubRuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import android.util.Log

class RuleSetViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "RuleSetViewModel"
    }
    
    private val _ruleSets = MutableStateFlow<List<HubRuleSet>>(emptyList())
    val ruleSets: StateFlow<List<HubRuleSet>> = _ruleSets.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val client = OkHttpClient()
    private val gson = Gson()

    init {
        fetchRuleSets()
    }

    fun fetchRuleSets() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            Log.d(TAG, "========== 开始获取规则集 ==========")
            try {
                val allRuleSets = mutableListOf<HubRuleSet>()
                
                Log.d(TAG, ">>> 获取 SagerNet 规则集...")
                val sagerNetRules = fetchFromSagerNet()
                Log.d(TAG, "<<< SagerNet 获取到 ${sagerNetRules.size} 个规则集")
                allRuleSets.addAll(sagerNetRules)
                
                Log.d(TAG, ">>> 获取 lyc8503 规则集...")
                val lycRules = fetchFromLyc8503()
                Log.d(TAG, "<<< lyc8503 获取到 ${lycRules.size} 个规则集")
                allRuleSets.addAll(lycRules)
                
                Log.d(TAG, ">>> 获取 MetaCubeX 规则集...")
                val metaRules = fetchFromMetaCubeX()
                Log.d(TAG, "<<< MetaCubeX 获取到 ${metaRules.size} 个规则集")
                allRuleSets.addAll(metaRules)

                Log.d(TAG, "总计获取到 ${allRuleSets.size} 个规则集")
                
                // 如果在线获取失败，使用预置规则集
                if (allRuleSets.isEmpty()) {
                    Log.w(TAG, "在线获取全部失败，使用预置规则集")
                    allRuleSets.addAll(getBuiltInRuleSets())
                }

                _ruleSets.value = allRuleSets.sortedBy { it.name }
                Log.d(TAG, "========== 规则集加载完成 ==========")
            } catch (e: Exception) {
                Log.e(TAG, "获取规则集失败", e)
                _error.value = "加载失败: ${e.message}"
                // 出错时也提供预置规则集
                _ruleSets.value = getBuiltInRuleSets()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun getBuiltInRuleSets(): List<HubRuleSet> {
        val baseUrl = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
        val commonRules = listOf(
            "google", "youtube", "twitter", "facebook", "instagram", "tiktok",
            "telegram", "whatsapp", "discord", "github", "microsoft", "apple",
            "amazon", "netflix", "spotify", "bilibili", "zhihu", "baidu",
            "tencent", "alibaba", "jd", "taobao", "weibo", "douyin",
            "cn", "geolocation-cn", "geolocation-!cn", "private", "ads"
        )
        return commonRules.map { name ->
            HubRuleSet(
                name = "geosite-$name",
                ruleCount = 0,
                tags = listOf("预置", "geosite"),
                description = "常用规则集",
                sourceUrl = "$baseUrl/geosite-$name.json",
                binaryUrl = "$baseUrl/geosite-$name.srs"
            )
        }
    }

    private fun fetchFromSagerNet(): List<HubRuleSet> {
        val url = "https://api.github.com/repos/SagerNet/sing-geosite/contents/rule-set"
        Log.d(TAG, "[SagerNet] 请求 URL: $url")
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "KunK-SingBox-App")
                .build()

            client.newCall(request).execute().use { response ->
                Log.d(TAG, "[SagerNet] HTTP 状态码: ${response.code}")
                Log.d(TAG, "[SagerNet] 响应头: ${response.headers}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "[SagerNet] 请求失败! 状态码=${response.code}, 响应=$errorBody")
                    return emptyList()
                }

                val json = response.body?.string() ?: "[]"
                Log.d(TAG, "[SagerNet] 响应长度: ${json.length} 字符")
                Log.d(TAG, "[SagerNet] 响应前500字符: ${json.take(500)}")
                
                val type = object : TypeToken<List<GithubFile>>() {}.type
                val files: List<GithubFile> = gson.fromJson(json, type)
                Log.d(TAG, "[SagerNet] 解析到 ${files.size} 个文件")
                
                val srsFiles = files.filter { it.name.endsWith(".srs") }
                Log.d(TAG, "[SagerNet] 其中 .srs 文件 ${srsFiles.size} 个")
                
                if (srsFiles.isNotEmpty()) {
                    Log.d(TAG, "[SagerNet] 前5个文件: ${srsFiles.take(5).map { it.name }}")
                }

                srsFiles.map { file ->
                    val nameWithoutExt = file.name.substringBeforeLast(".srs")
                    HubRuleSet(
                        name = nameWithoutExt,
                        ruleCount = 0,
                        tags = listOf("官方", "geosite"),
                        description = "SagerNet 官方规则集",
                        sourceUrl = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/${file.name.replace(".srs", ".json")}",
                        binaryUrl = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/${file.name}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SagerNet] 发生异常: ${e.javaClass.simpleName} - ${e.message}", e)
            emptyList()
        }
    }

      private fun fetchFromLyc8503(): List<HubRuleSet> {
          return try {
              val request = Request.Builder()
                  .url("https://api.github.com/repos/lyc8503/sing-box-rules/contents/rule-set-geosite")
                  .build()

              client.newCall(request).execute().use { response ->
                  if (!response.isSuccessful) return emptyList()

                  val json = response.body?.string() ?: "[]"
                  val type = object : TypeToken<List<GithubFile>>() {}.type
                  val files: List<GithubFile> = gson.fromJson(json, type)

                  files
                      .filter { it.name.endsWith(".srs") || it.name.endsWith(".json") }
                      .map { file ->
                          val nameWithoutExt = file.name.substringBeforeLast(".")
                          nameWithoutExt
                      }
                      .distinct()
                      .map { name ->
                          HubRuleSet(
                              name = name,
                              ruleCount = 0,
                              tags = listOf("社区", "geosite"),
                              description = "lyc8503 维护规则集",
                              sourceUrl = "https://raw.githubusercontent.com/lyc8503/sing-box-rules/master/rule-set-geosite/$name.json",
                              binaryUrl = "https://raw.githubusercontent.com/lyc8503/sing-box-rules/master/rule-set-geosite/$name.srs"
                          )
                      }
              }
          } catch (e: Exception) {
              e.printStackTrace()
              emptyList()
          }
      }

    // MetaCubeX/meta-rules-dat 规则集 (与 GUI.for.SingBox 相同的源)
    private fun fetchFromMetaCubeX(): List<HubRuleSet> {
        val result = mutableListOf<HubRuleSet>()
        val baseUrl = "https://testingcf.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@sing"
        
        // geosite 规则
        val geositeRules = listOf(
            "google", "youtube", "twitter", "facebook", "instagram", "tiktok",
            "telegram", "whatsapp", "discord", "github", "microsoft", "apple",
            "amazon", "netflix", "spotify", "bilibili", "zhihu", "baidu",
            "tencent", "alibaba", "openai", "bing", "oracle", "nvidia",
            "cloudflare", "steam", "epicgames", "huggingface", "google-play",
            "cn", "geolocation-cn", "geolocation-!cn", "private", "category-ads-all"
        )
        
        geositeRules.forEach { name ->
            result.add(
                HubRuleSet(
                    name = "$name-geosite",
                    ruleCount = 0,
                    tags = listOf("MetaCubeX", "geosite"),
                    description = "MetaCubeX 规则集 (jsdelivr CDN)",
                    sourceUrl = "$baseUrl/geo/geosite/$name.json",
                    binaryUrl = "$baseUrl/geo/geosite/$name.srs"
                )
            )
        }
        
        // geoip 规则
        val geoipRules = listOf(
            "cn", "private", "google", "telegram", "twitter", "facebook", 
            "netflix", "cloudflare"
        )
        
        geoipRules.forEach { name ->
            result.add(
                HubRuleSet(
                    name = "$name-geoip",
                    ruleCount = 0,
                    tags = listOf("MetaCubeX", "geoip"),
                    description = "MetaCubeX IP 规则集 (jsdelivr CDN)",
                    sourceUrl = "$baseUrl/geo/geoip/$name.json",
                    binaryUrl = "$baseUrl/geo/geoip/$name.srs"
                )
            )
        }
        
        return result
    }

    data class GithubFile(
        val name: String,
        val path: String,
        val size: Long,
        val download_url: String?
    )
}
