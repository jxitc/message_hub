# Android SMS Processing Implementation Guide

## Overview
This guide extracts key architectural patterns from a successful SMS processing Android app, focusing on avoiding common dependency injection pitfalls and ensuring smooth project bootstrap.

## Key Architecture Decisions

### 1. Manual Dependency Injection (No Hilt/Dagger)
**Problem Solved**: Avoid complex DI setup issues that can block development

**Implementation**: Simple AppContainer pattern
```kotlin
class AppContainer(private val context: Context) {
    private val database by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            SMSForwardDatabase::class.java,
            "sms_forward_database"
        )
        .fallbackToDestructiveMigration()
        .build()
    }
    
    val messageRepository: MessageRepository by lazy {
        MessageRepositoryImpl(database.messageDao())
    }
    
    val messageProcessorUseCase by lazy {
        MessageProcessorUseCase(saveMessageUseCase, messageValidationUseCase)
    }
}
```

### 2. Clean Architecture with MVP Foundation
**Layers**:
- **Data Layer**: Room database, repositories
- **Domain Layer**: Use cases, models
- **Presentation Layer**: Compose UI, ViewModels

**Key Files Structure**:
```
app/src/main/java/com/[package]/
├── di/AppContainer.kt              # Manual DI container
├── data/
│   ├── database/                   # Room DB setup
│   └── repository/                 # Data layer implementation
├── domain/
│   ├── model/                     # Business models
│   ├── repository/                # Repository interfaces
│   └── usecase/                   # Business logic
├── presentation/
│   └── screen/                    # Compose screens
├── receiver/SmsReceiver.kt        # SMS broadcast receiver
└── utils/                         # Utilities and parsers
```

### 3. SMS Reception Pattern
**Essential Components**:

**AndroidManifest.xml**:
```xml
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_SMS" />

<receiver android:name=".receiver.SmsReceiver" android:exported="true">
    <intent-filter android:priority="1000">
        <action android:name="android.provider.Telephony.SMS_RECEIVED" />
    </intent-filter>
</receiver>
```

**SmsReceiver.kt**:
```kotlin
class SmsReceiver : BroadcastReceiver() {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION && context != null) {
            val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val appContainer = (context.applicationContext as YourApplication).appContainer
            
            smsMessages?.forEach { smsMessage ->
                coroutineScope.launch {
                    appContainer.smsParser.processSmsMessage(smsMessage)
                }
            }
        }
    }
}
```

### 4. Application Class Setup
```kotlin
class YourApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }
}
```

**AndroidManifest.xml**:
```xml
<application android:name=".YourApplication">
```

## Critical Success Patterns

### 1. Lazy Initialization
- Use `by lazy` for all dependencies in AppContainer
- Prevents circular dependencies and startup issues

### 2. Error-First Design
- Implement robust error handling with sealed classes
- Use `ProcessingResult` pattern for operation outcomes
```kotlin
sealed class ProcessingResult {
    data class Success(val id: Long, val data: T) : ProcessingResult()
    data class ValidationFailed(val errors: List<String>) : ProcessingResult()
    data class SaveFailed(val error: Throwable) : ProcessingResult()
}
```

### 3. Validation Layer
- Always sanitize and validate incoming SMS data
- Separate validation logic into dedicated use cases

### 4. Coroutine Scope Management
- Use appropriate scopes (IO for database operations)
- Handle SMS processing asynchronously in BroadcastReceiver

## Common Pitfalls to Avoid

1. **Hilt/Dagger Complexity**: Start with manual DI, add framework DI later if needed
2. **Synchronous SMS Processing**: Always use coroutines for database operations
3. **Missing Permissions**: Ensure both RECEIVE_SMS and READ_SMS permissions
4. **Context Leaks**: Use applicationContext in AppContainer
5. **Database Migration Issues**: Use `fallbackToDestructiveMigration()` for development

## Recommended Development Sequence

1. Set up basic project structure with manual DI
2. Implement Room database with basic entities
3. Create SMS receiver and processor use cases
4. Add validation and error handling
5. Implement UI components
6. Add comprehensive logging
7. Only then consider adding complex DI frameworks

## Dependencies to Include
```kotlin
// Room database
implementation("androidx.room:room-runtime:2.5.0")
implementation("androidx.room:room-ktx:2.5.0")
kapt("androidx.room:room-compiler:2.5.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// Compose UI (optional)
implementation("androidx.compose.ui:ui:1.5.4")
implementation("androidx.activity:activity-compose:1.8.2")
```

This pattern provides a solid, maintainable foundation that avoids common Android development blockers while maintaining clean architecture principles.