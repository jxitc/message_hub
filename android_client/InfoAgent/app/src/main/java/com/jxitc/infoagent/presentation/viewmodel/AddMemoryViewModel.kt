package com.jxitc.infoagent.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.jxitc.infoagent.data.local.AppPreferences
import com.jxitc.infoagent.data.remote.InfoAgentApiClient
import com.jxitc.infoagent.domain.model.MemoryCreationRequest
import com.jxitc.infoagent.domain.model.ProcessingResult
import com.jxitc.infoagent.domain.model.SourceType
import com.jxitc.infoagent.domain.usecase.CreateMemoryUseCase
import com.jxitc.infoagent.domain.repository.MemoryRepository
import com.jxitc.infoagent.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AddMemoryViewModel(
    private val createMemoryUseCase: CreateMemoryUseCase,
    private val apiClient: InfoAgentApiClient,
    private val appPreferences: AppPreferences,
    private val memoryRepository: MemoryRepository
) : BaseViewModel() {
    
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()
    
    private val _isSubmitted = MutableStateFlow(false)
    val isSubmitted: StateFlow<Boolean> = _isSubmitted.asStateFlow()
    
    fun updateContent(newContent: String) {
        _content.value = newContent
        clearError()
    }
    
    fun submitMemory() {
        val contentText = _content.value.trim()
        
        Logger.i("submitMemory called with content length: ${contentText.length}")
        
        if (contentText.isEmpty()) {
            handleError("Please enter some content")
            return
        }
        
        val request = MemoryCreationRequest(
            content = contentText,
            sourceType = SourceType.MANUAL,
            metadata = mapOf("input_method" to "manual_text_input")
        )
        
        // Try server first if auto-sync is enabled, then fall back to local
        val autoSyncEnabled = appPreferences.autoSync
        Logger.i("Auto-sync enabled: $autoSyncEnabled")
        
        if (autoSyncEnabled) {
            Logger.i("Taking server upload path")
            submitToServerWithFallback(request)
        } else {
            Logger.i("Taking local-only path")
            submitToLocalOnly(request)
        }
    }
    
    private fun submitToServerWithFallback(request: MemoryCreationRequest) {
        Logger.i("submitToServerWithFallback: Starting server upload...")
        launchWithLoading(
            block = { 
                // Try server first
                Logger.i("Calling apiClient.createMemory...")
                when (val serverResult = apiClient.createMemory(request)) {
                    is ProcessingResult.Success -> {
                        Logger.i("Server upload successful, saving locally...")
                        
                        // Server success - save locally first, then update upload status
                        val localResult = createMemoryUseCase.execute(request)
                        if (localResult is ProcessingResult.Success) {
                            Logger.i("Local save successful, memory ID: ${localResult.data.id}, updating upload status...")
                            
                            // Mark the local memory as uploaded (MUST await this!)
                            val updateResult = memoryRepository.updateMemoryUploadStatus(localResult.data.id, true)
                            
                            when (updateResult) {
                                is ProcessingResult.Success -> {
                                    Logger.i("Upload status updated successfully for memory ID: ${localResult.data.id}")
                                    // Return success with the local memory (which now has the correct upload status)
                                    ProcessingResult.Success(localResult.data.copy(isUploaded = true))
                                }
                                is ProcessingResult.Error -> {
                                    Logger.e("Failed to update upload status: ${updateResult.message}")
                                    // Database update failed - log error but still return success since server worked
                                    ProcessingResult.Success(localResult.data.copy(isUploaded = true))
                                }
                                ProcessingResult.Loading -> ProcessingResult.Loading
                            }
                        } else {
                            Logger.e("Local save failed even though server succeeded")
                            // Local save failed, but server succeeded - return server result
                            serverResult
                        }
                    }
                    is ProcessingResult.Error -> {
                        Logger.w("Server upload failed: ${serverResult.message}, saving locally...")
                        // Server failed - save locally for later sync (isUploaded = false by default)
                        val localResult = createMemoryUseCase.execute(request)
                        when (localResult) {
                            is ProcessingResult.Success -> {
                                // Add user notification about upload failure
                                Logger.i("Memory saved locally, will retry upload later")
                                ProcessingResult.Success(localResult.data)
                            }
                            is ProcessingResult.Error -> {
                                Logger.e("Both server and local save failed!")
                                ProcessingResult.Error("Failed to save memory: ${localResult.message}")
                            }
                            ProcessingResult.Loading -> ProcessingResult.Loading
                        }
                    }
                    ProcessingResult.Loading -> ProcessingResult.Loading
                }
            },
            onSuccess = { memory ->
                _isSubmitted.value = true
                _content.value = ""
            }
        )
    }
    
    private fun submitToLocalOnly(request: MemoryCreationRequest) {
        Logger.i("submitToLocalOnly: Saving memory locally only...")
        launchWithLoading(
            block = { 
                Logger.i("Calling createMemoryUseCase.execute...")
                createMemoryUseCase.execute(request) 
            },
            onSuccess = { memory ->
                Logger.i("Local save successful, memory ID: ${memory.id}")
                _isSubmitted.value = true
                _content.value = ""
            }
        )
    }
    
    fun resetSubmissionState() {
        _isSubmitted.value = false
    }
}