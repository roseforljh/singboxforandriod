package com.kunk.singbox.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

@Keep
@Immutable
data class ProfileUi(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: ProfileType,
    @SerializedName("url") val url: String?,
    @SerializedName("lastUpdated") val lastUpdated: Long,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("autoUpdateInterval") val autoUpdateInterval: Int = 0, // 0 means disabled, minutes
    @SerializedName("updateStatus") val updateStatus: UpdateStatus = UpdateStatus.Idle
)

@Keep
enum class ProfileType {
    @SerializedName("Subscription") Subscription,
    @SerializedName("LocalFile") LocalFile,
    @SerializedName("Imported") Imported
}

@Keep
enum class UpdateStatus {
    @SerializedName("Idle") Idle,
    @SerializedName("Updating") Updating,
    @SerializedName("Success") Success,
    @SerializedName("Failed") Failed
}

/**
 * 订阅更新结果
 */
sealed class SubscriptionUpdateResult {
    /**
     * 更新成功，有变化
     * @param profileName 配置名称
     * @param addedCount 新增节点数
     * @param removedCount 移除节点数
     * @param totalCount 总节点数
     */
    data class SuccessWithChanges(
        val profileName: String,
        val addedCount: Int,
        val removedCount: Int,
        val totalCount: Int
    ) : SubscriptionUpdateResult()
    
    /**
     * 更新成功，无变化
     * @param profileName 配置名称
     * @param totalCount 总节点数
     */
    data class SuccessNoChanges(
        val profileName: String,
        val totalCount: Int
    ) : SubscriptionUpdateResult()
    
    /**
     * 更新失败
     * @param profileName 配置名称
     * @param error 错误信息
     */
    data class Failed(
        val profileName: String,
        val error: String
    ) : SubscriptionUpdateResult()
}

/**
 * 批量更新订阅的汇总结果
 */
data class BatchUpdateResult(
    val successWithChanges: Int = 0,
    val successNoChanges: Int = 0,
    val failed: Int = 0,
    val details: List<SubscriptionUpdateResult> = emptyList()
) {
    val totalCount: Int get() = successWithChanges + successNoChanges + failed
    val successCount: Int get() = successWithChanges + successNoChanges
    
    fun toDisplayMessage(): String {
        return when {
            totalCount == 0 -> "没有可更新的订阅"
            failed == totalCount -> "更新失败"
            successWithChanges > 0 && failed == 0 -> "更新成功，${successWithChanges}个订阅有变化"
            successNoChanges == totalCount -> "更新完成，无变化"
            failed > 0 -> "更新完成，${successCount}个成功，${failed}个失败"
            else -> "更新完成"
        }
    }
}

@Immutable
@Keep
data class NodeUi(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("protocol") val protocol: String,
    @SerializedName("group") val group: String,
    @SerializedName("regionFlag") val regionFlag: String? = null,
    @SerializedName("latencyMs") val latencyMs: Long? = null, // null means not tested
    @SerializedName("isFavorite") val isFavorite: Boolean = false,
    @SerializedName("sourceProfileId") val sourceProfileId: String,
    @SerializedName("tags") val tags: List<String> = emptyList()
) {
    val displayName: String
        get() = name
}

data class RuleSetUi(
    val id: String,
    val name: String,
    val type: String, // Remote, Local
    val sourceUrl: String?,
    val enabled: Boolean,
    val lastUpdated: Long,
    val ruleCount: Int
)

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntryUi(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
)

data class ConnectionStats(
    val uploadSpeed: Long, // bytes/s
    val downloadSpeed: Long, // bytes/s
    val uploadTotal: Long, // bytes
    val downloadTotal: Long, // bytes
    val duration: Long // ms
)

enum class ConnectionState {
    Idle, Connecting, Connected, Disconnecting, Error
}