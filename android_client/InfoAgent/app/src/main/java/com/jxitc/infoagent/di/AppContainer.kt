package com.jxitc.infoagent.di

import android.content.Context
import com.jxitc.infoagent.data.database.InfoAgentDatabase
import com.jxitc.infoagent.data.repository.MemoryRepositoryImpl
import com.jxitc.infoagent.domain.repository.MemoryRepository
import com.jxitc.infoagent.domain.usecase.CreateMemoryUseCase
import com.jxitc.infoagent.domain.usecase.GetMemoriesUseCase
import com.jxitc.infoagent.domain.usecase.ProcessSmsUseCase
import com.jxitc.infoagent.domain.usecase.ProcessNotificationUseCase
import com.jxitc.infoagent.domain.service.MemorySyncService
import com.jxitc.infoagent.domain.service.SmsProcessor
import com.jxitc.infoagent.domain.service.NotificationProcessor
import com.jxitc.infoagent.data.local.AppPreferences
import com.jxitc.infoagent.data.remote.InfoAgentApiClient
import com.jxitc.infoagent.presentation.viewmodel.AddMemoryViewModel
import com.jxitc.infoagent.presentation.viewmodel.MemoryListViewModel
import com.jxitc.infoagent.presentation.viewmodel.SettingsViewModel

class AppContainer(private val context: Context) {
    
    private val database by lazy {
        InfoAgentDatabase.getDatabase(context.applicationContext)
    }
    
    val appPreferences by lazy {
        AppPreferences(context.applicationContext)
    }
    
    val apiClient by lazy {
        InfoAgentApiClient(appPreferences)
    }
    
    val memoryRepository: MemoryRepository by lazy {
        MemoryRepositoryImpl(database.memoryDao())
    }
    
    val createMemoryUseCase by lazy {
        CreateMemoryUseCase(memoryRepository)
    }
    
    val getMemoriesUseCase by lazy {
        GetMemoriesUseCase(memoryRepository)
    }
    
    val syncService by lazy {
        MemorySyncService(memoryRepository, apiClient, appPreferences)
    }
    
    val processSmsUseCase by lazy {
        ProcessSmsUseCase(memoryRepository, context.contentResolver, syncService)
    }

    val smsProcessor by lazy {
        SmsProcessor(processSmsUseCase)
    }

    val processNotificationUseCase by lazy {
        ProcessNotificationUseCase(memoryRepository, context.packageManager, syncService)
    }

    val notificationProcessor by lazy {
        NotificationProcessor(processNotificationUseCase)
    }

    fun createAddMemoryViewModel(): AddMemoryViewModel {
        return AddMemoryViewModel(createMemoryUseCase, apiClient, appPreferences, memoryRepository)
    }
    
    fun createMemoryListViewModel(): MemoryListViewModel {
        return MemoryListViewModel(getMemoriesUseCase, syncService)
    }
    
    fun createSettingsViewModel(): SettingsViewModel {
        return SettingsViewModel(appPreferences, apiClient)
    }
}