package com.jxitc.infoagent.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jxitc.infoagent.domain.model.ProcessingResult
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

abstract class BaseViewModel : ViewModel() {
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    protected val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        handleError("Unexpected error: ${exception.message}")
    }
    
    protected fun handleError(message: String) {
        _error.value = message
        _isLoading.value = false
    }
    
    protected fun clearError() {
        _error.value = null
    }
    
    protected fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
    
    protected fun <T> launchWithLoading(
        block: suspend () -> ProcessingResult<T>,
        onSuccess: (T) -> Unit = {},
        onError: (String) -> Unit = { handleError(it) }
    ) {
        viewModelScope.launch(exceptionHandler) {
            _isLoading.value = true
            clearError()
            
            when (val result = block()) {
                is ProcessingResult.Success -> {
                    onSuccess(result.data)
                }
                is ProcessingResult.Error -> {
                    onError(result.message)
                }
                ProcessingResult.Loading -> {
                    // Loading state already handled
                }
            }
            
            _isLoading.value = false
        }
    }
}