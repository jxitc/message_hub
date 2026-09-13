package com.jxitc.messagehub.di

import android.content.Context
import com.jxitc.messagehub.data.attachment.AndroidImageEncoder
import com.jxitc.messagehub.data.attachment.AttachmentPreparer
import com.jxitc.messagehub.data.attachment.AttachmentReader
import com.jxitc.messagehub.data.database.MessageHubDatabase
import com.jxitc.messagehub.data.repository.MemoryRepositoryImpl
import com.jxitc.messagehub.domain.repository.MemoryRepository
import com.jxitc.messagehub.domain.usecase.CreateMemoryUseCase
import com.jxitc.messagehub.domain.usecase.GetMemoriesUseCase
import com.jxitc.messagehub.domain.usecase.ProcessSmsUseCase
import com.jxitc.messagehub.domain.usecase.ProcessNotificationUseCase
import com.jxitc.messagehub.domain.service.MemorySyncService
import com.jxitc.messagehub.domain.service.SmsProcessor
import com.jxitc.messagehub.domain.service.NotificationProcessor
import com.jxitc.messagehub.data.local.AppPreferences
import com.jxitc.messagehub.data.remote.MessageHubApiClient
import com.jxitc.messagehub.data.remote.UpdateChecker
import com.jxitc.messagehub.presentation.viewmodel.AddMemoryViewModel
import com.jxitc.messagehub.presentation.viewmodel.MemoryListViewModel
import com.jxitc.messagehub.presentation.viewmodel.SettingsViewModel

class AppContainer(private val context: Context) {
    
    private val database by lazy {
        MessageHubDatabase.getDatabase(context.applicationContext)
    }
    
    val appPreferences by lazy {
        AppPreferences(context.applicationContext)
    }
    
    val apiClient by lazy {
        MessageHubApiClient(appPreferences)
    }

    /** 应用内自动更新（检查版本 / 下载 APK / 拉起安装器） */
    val updateChecker by lazy {
        UpdateChecker(context.applicationContext, appPreferences)
    }
    
    val memoryRepository: MemoryRepository by lazy {
        MemoryRepositoryImpl(database.memoryDao())
    }
    
    val createMemoryUseCase by lazy {
        CreateMemoryUseCase(memoryRepository)
    }

    /**
     * 附件准备：读取 content:// → 校验类型 → 超过上限的图片压缩。
     * 规则本体在 domain（纯函数、有单测），这里只负责把 Android 实现装起来。
     */
    val attachmentPreparer by lazy {
        AttachmentPreparer(
            reader = AttachmentReader(context.applicationContext),
            encoder = AndroidImageEncoder()
        )
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
        return AddMemoryViewModel(
            createMemoryUseCase,
            apiClient,
            appPreferences,
            memoryRepository,
            attachmentPreparer
        )
    }
    
    fun createMemoryListViewModel(): MemoryListViewModel {
        return MemoryListViewModel(getMemoriesUseCase, syncService)
    }
    
    fun createSettingsViewModel(): SettingsViewModel {
        return SettingsViewModel(appPreferences, apiClient, updateChecker)
    }
}