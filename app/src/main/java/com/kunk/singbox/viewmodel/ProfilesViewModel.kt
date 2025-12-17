package com.kunk.singbox.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.repository.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfilesViewModel(application: Application) : AndroidViewModel(application) {
    
    private val configRepository = ConfigRepository.getInstance(application)

    val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val activeProfileId: StateFlow<String?> = configRepository.activeProfileId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
    
    // 导入状态
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun setActiveProfile(profileId: String) {
        configRepository.setActiveProfile(profileId)
    }

    fun toggleProfileEnabled(profileId: String) {
        configRepository.toggleProfileEnabled(profileId)
    }

    fun updateProfile(profileId: String) {
        viewModelScope.launch {
            configRepository.updateProfile(profileId)
        }
    }

    fun deleteProfile(profileId: String) {
        configRepository.deleteProfile(profileId)
    }

    /**
     * 导入订阅配置
     */
    fun importSubscription(name: String, url: String) {
        // 防止重复导入
        if (_importState.value is ImportState.Loading) {
            return
        }
        
        viewModelScope.launch {
            _importState.value = ImportState.Loading("正在获取订阅...")
            
            val result = configRepository.importFromSubscription(
                name = name,
                url = url,
                onProgress = { progress ->
                    _importState.value = ImportState.Loading(progress)
                }
            )
            
            result.fold(
                onSuccess = { profile ->
                    _importState.value = ImportState.Success(profile)
                },
                onFailure = { error ->
                    _importState.value = ImportState.Error(error.message ?: "导入失败")
                }
            )
        }
    }

    fun importFromContent(
        name: String,
        content: String,
        profileType: ProfileType = ProfileType.Imported
    ) {
        if (_importState.value is ImportState.Loading) {
            return
        }
        if (content.isBlank()) {
            _importState.value = ImportState.Error("内容为空")
            return
        }

        viewModelScope.launch {
            _importState.value = ImportState.Loading("正在解析配置...")

            val result = configRepository.importFromContent(
                name = name,
                content = content,
                profileType = profileType,
                onProgress = { progress ->
                    _importState.value = ImportState.Loading(progress)
                }
            )

            result.fold(
                onSuccess = { profile ->
                    _importState.value = ImportState.Success(profile)
                },
                onFailure = { error ->
                    _importState.value = ImportState.Error(error.message ?: "导入失败")
                }
            )
        }
    }
    
    fun resetImportState() {
        _importState.value = ImportState.Idle
    }

    sealed class ImportState {
        data object Idle : ImportState()
        data class Loading(val message: String) : ImportState()
        data class Success(val profile: ProfileUi) : ImportState()
        data class Error(val message: String) : ImportState()
    }
}