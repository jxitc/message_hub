package com.jxitc.infoagent.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import com.jxitc.infoagent.domain.model.Memory
import com.jxitc.infoagent.domain.usecase.GetMemoriesUseCase
import com.jxitc.infoagent.domain.service.MemorySyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MemoryListViewModel(
    private val getMemoriesUseCase: GetMemoriesUseCase,
    private val syncService: MemorySyncService
) : BaseViewModel() {
    
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    init {
        loadMemories()
        // Start auto-sync on app launch
        syncPendingMemories()
    }
    
    fun loadMemories() {
        viewModelScope.launch(exceptionHandler) {
            setLoading(true)
            try {
                // 全量取数(几千条内存聚合无压力), 避免先取 50 条导致单一 app 连刷顶掉其他 app
                getMemoriesUseCase.getAllMemories().collect { memoriesList ->
                    _memories.value = memoriesList
                    setLoading(false)
                }
            } catch (e: Exception) {
                handleError("Failed to load memories: ${e.message}")
            }
        }
    }
    
    fun searchMemories(query: String) {
        _searchQuery.value = query
        
        viewModelScope.launch(exceptionHandler) {
            setLoading(true)
            try {
                getMemoriesUseCase.searchMemories(query).collect { memoriesList ->
                    _memories.value = memoriesList
                    setLoading(false)
                }
            } catch (e: Exception) {
                handleError("Failed to search memories: ${e.message}")
            }
        }
    }
    
    fun clearSearch() {
        _searchQuery.value = ""
        loadMemories()
    }
    
    fun refreshMemories() {
        clearError()
        // Trigger sync before refreshing memories
        syncPendingMemories()
        
        if (_searchQuery.value.isBlank()) {
            loadMemories()
        } else {
            searchMemories(_searchQuery.value)
        }
    }
    
    private fun syncPendingMemories() {
        viewModelScope.launch(exceptionHandler) {
            try {
                val (uploaded, failed) = syncService.syncPendingMemories()
                if (uploaded > 0 || failed > 0) {
                    // Refresh the memory list after sync to show updated upload status
                    loadMemories()
                }
            } catch (e: Exception) {
                // Don't show sync errors to user, just log them
                // The memories will remain in pending state and sync can retry later
            }
        }
    }
}