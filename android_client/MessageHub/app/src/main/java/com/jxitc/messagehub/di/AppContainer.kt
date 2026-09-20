package com.jxitc.messagehub.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.jxitc.messagehub.data.attachment.AndroidImageEncoder
import com.jxitc.messagehub.data.attachment.AttachmentDownloader
import com.jxitc.messagehub.data.attachment.AttachmentPreparer
import com.jxitc.messagehub.data.attachment.AttachmentReader
import com.jxitc.messagehub.data.database.MessageHubDatabase
import com.jxitc.messagehub.data.repository.MemoryRepositoryImpl
import com.jxitc.messagehub.domain.model.ProcessingResult
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
import kotlinx.coroutines.flow.first

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

    /**
     * 图片加载器（Coil）：附件缩略图用。
     *
     * 三个关键点：
     *  - **共用 API 客户端**：`/api/v1/blobs/<key>` 需要 `X-API-Key`，而鉴权只在那个
     *    OkHttpClient 的拦截器里；另起一个客户端就会 401。
     *  - **磁盘缓存**：附件按内容寻址（key 里就是 sha256），内容不可变 —— 缓存一次就够了，
     *    反复打开列表不该反复下载。响应头不一定给 `Cache-Control`，所以关掉"按响应头判断
     *    可缓存性"，否则磁盘缓存可能根本不写入。
     *  - **内存缓存**：滚动时不用反复解码。
     */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context.applicationContext)
            .callFactory { apiClient.httpClient }
            .memoryCache {
                MemoryCache.Builder(context.applicationContext)
                    .maxSizePercent(0.2)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.applicationContext.cacheDir.resolve(IMAGE_CACHE_DIR))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }

    /** 附件原件（PDF/文本等）的按需下载：只落在 cacheDir，不进数据库。 */
    val attachmentDownloader: AttachmentDownloader by lazy {
        AttachmentDownloader(context.applicationContext, apiClient)
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
        // 组合根：把真实的仓库/网络/设置包成 MemorySyncService 要的函数。
        // 服务本身只依赖函数类型，所以它的并发规则（串行上传 + 不叠加全量扫描）
        // 能在 JVM 单测里用假的实现跑，见 MemorySyncServiceConcurrencyTest。
        MemorySyncService(
            loadPending = { memoryRepository.getPendingUploads().first() },
            loadOne = { id -> (memoryRepository.getMemory(id) as? ProcessingResult.Success)?.data },
            markUploaded = { id, serverId ->
                memoryRepository.updateMemoryUploadStatus(id, true)
                serverId?.takeIf { it.isNotBlank() }?.let {
                    memoryRepository.attachServerMessageId(id, it)
                }
            },
            upload = { request -> apiClient.createMemory(request) },
            autoSyncEnabled = { appPreferences.autoSync }
        )
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
        return MemoryListViewModel(getMemoriesUseCase, syncService, apiClient, memoryRepository)
    }
    
    fun createSettingsViewModel(): SettingsViewModel {
        return SettingsViewModel(appPreferences, apiClient, updateChecker)
    }

    companion object {
        /** Coil 磁盘缓存目录（cacheDir 下，系统可回收）。 */
        private const val IMAGE_CACHE_DIR = "image_cache"
    }
}