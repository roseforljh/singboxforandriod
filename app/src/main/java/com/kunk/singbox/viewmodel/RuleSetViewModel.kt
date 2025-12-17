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

class RuleSetViewModel(application: Application) : AndroidViewModel(application) {
    
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
              try {
                  val allRuleSets = mutableListOf<HubRuleSet>()
                  
                  allRuleSets.addAll(fetchFromSagerNet())
                  allRuleSets.addAll(fetchFromLyc8503())

                  _ruleSets.value = allRuleSets.sortedBy { it.name }
              } catch (e: Exception) {
                  e.printStackTrace()
                  _error.value = "加载失败: ${e.message}"
              } finally {
                  _isLoading.value = false
              }
          }
      }

      private fun fetchFromSagerNet(): List<HubRuleSet> {
          return try {
              val request = Request.Builder()
                  .url("https://api.github.com/repos/SagerNet/sing-geosite/contents/rule-set")
                  .build()

              client.newCall(request).execute().use { response ->
                  if (!response.isSuccessful) return emptyList()

                  val json = response.body?.string() ?: "[]"
                  val type = object : TypeToken<List<GithubFile>>() {}.type
                  val files: List<GithubFile> = gson.fromJson(json, type)

                  files
                      .filter { it.name.endsWith(".srs") }
                      .map { file ->
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
              e.printStackTrace()
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

    data class GithubFile(
        val name: String,
        val path: String,
        val size: Long,
        val download_url: String?
    )
}
