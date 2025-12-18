package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NodesViewModel(application: Application) : AndroidViewModel(application) {
    
    enum class SortType {
        DEFAULT, LATENCY, NAME, REGION
    }
    
    private val configRepository = ConfigRepository.getInstance(application)

    private var testingJob: Job? = null

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()
    
    // 正在测试延迟的节点 ID 集合
    private val _testingNodeIds = MutableStateFlow<Set<String>>(emptySet())
    val testingNodeIds: StateFlow<Set<String>> = _testingNodeIds.asStateFlow()
    
    private val _sortType = MutableStateFlow(SortType.DEFAULT)
    val sortType: StateFlow<SortType> = _sortType.asStateFlow()

    val nodes: StateFlow<List<NodeUi>> = combine(configRepository.nodes, _sortType) { nodes, sortType ->
        when (sortType) {
            SortType.DEFAULT -> nodes
            SortType.LATENCY -> nodes.sortedWith(compareBy<NodeUi> { it.latencyMs == null }.thenBy { it.latencyMs })
            SortType.NAME -> nodes.sortedBy { it.name }
            SortType.REGION -> nodes.sortedBy { it.regionFlag ?: "\uFFFF" } // Put no flag at end
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val nodeGroups: StateFlow<List<String>> = configRepository.nodeGroups
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = listOf("全部")
        )

    val allNodes: StateFlow<List<NodeUi>> = configRepository.allNodes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allNodeGroups: StateFlow<List<String>> = configRepository.allNodeGroups
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeNodeId: StateFlow<String?> = configRepository.activeNodeId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _switchResult = MutableStateFlow<String?>(null)
    val switchResult: StateFlow<String?> = _switchResult.asStateFlow()

    fun setActiveNode(nodeId: String) {
        viewModelScope.launch {
            val node = nodes.value.find { it.id == nodeId }
            val success = configRepository.setActiveNode(nodeId)
            if (com.kunk.singbox.service.SingBoxService.isRunning && node != null) {
                _switchResult.value = if (success) {
                    "已切换到 ${node.name}"
                } else {
                    "切换到 ${node.name} 失败"
                }
            }
        }
    }

    fun clearSwitchResult() {
        _switchResult.value = null
    }
    
    fun testLatency(nodeId: String) {
        if (_testingNodeIds.value.contains(nodeId)) return
        viewModelScope.launch {
            _testingNodeIds.value = _testingNodeIds.value + nodeId
            try {
                configRepository.testNodeLatency(nodeId)
            } finally {
                _testingNodeIds.value = _testingNodeIds.value - nodeId
            }
        }
    }

    fun testAllLatency() {
        if (_isTesting.value) {
            // 如果正在测试，则取消测试
            testingJob?.cancel()
            testingJob = null
            _isTesting.value = false
            _testingNodeIds.value = emptySet()
            return
        }
        
        testingJob = viewModelScope.launch {
            _isTesting.value = true
            try {
                // 使用 ConfigRepository 的批量测试功能，它已经在内部实现了并行化
                configRepository.testAllNodesLatency()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isTesting.value = false
                _testingNodeIds.value = emptySet()
                testingJob = null
            }
        }
    }

    fun deleteNode(nodeId: String) {
        viewModelScope.launch {
            configRepository.deleteNode(nodeId)
        }
    }

    fun exportNode(nodeId: String): String? {
        return configRepository.exportNode(nodeId)
    }
    
    fun setSortType(type: SortType) {
        _sortType.value = type
    }
    
    fun clearLatency() {
        viewModelScope.launch {
            configRepository.clearAllNodesLatency()
        }
    }
    
    fun addNode(content: String) {
        viewModelScope.launch {
            // Check if it's a link or content
            if (content.startsWith("vmess://") || content.startsWith("vless://") ||
                content.startsWith("ss://") || content.startsWith("trojan://") ||
                content.startsWith("hysteria") || content.startsWith("tuic")) {
                
                // For simple link import, we can reuse importFromContent but we might need a dummy profile or "Manual" profile
                // Currently ConfigRepository.importFromContent creates a new profile.
                // User wants to add to "Current Group".
                // However, ConfigRepository structure ties nodes to Profiles.
                // If "Current Group" is a Profile (Subscription), adding manually might be overwritten on update.
                // Best practice: Add to a specific "Manual" profile or allow editing subscription (not recommended).
                
                // For now, let's treat "Add Node" as creating a new "Imported" profile or appending to one.
                // To keep it simple and consistent with ConfigRepository structure:
                configRepository.importFromContent("手动添加", content)
            }
        }
    }
}
