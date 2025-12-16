package com.kunk.singbox.model

data class ProfileUi(
    val id: String,
    val name: String,
    val type: ProfileType,
    val url: String?,
    val lastUpdated: Long,
    val enabled: Boolean,
    val autoUpdateInterval: Int = 0, // 0 means disabled, minutes
    val updateStatus: UpdateStatus = UpdateStatus.Idle
)

enum class ProfileType {
    Subscription, LocalFile, Imported
}

enum class UpdateStatus {
    Idle, Updating, Success, Failed
}

data class NodeUi(
    val id: String,
    val name: String,
    val protocol: String,
    val group: String,
    val regionFlag: String? = null,
    val latencyMs: Long? = null, // null means not tested
    val isFavorite: Boolean = false,
    val sourceProfileId: String,
    val tags: List<String> = emptyList()
)

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