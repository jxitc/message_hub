# InfoAgent Android Client

This is the Android client for the InfoAgent personal information management system. The app automatically collects various types of information from the user's device and sends them to the InfoAgent server.

## Project Structure

The project follows Clean Architecture principles with MVVM pattern:

```
app/src/main/java/com/jxitc/infoagent/
├── InfoAgentApplication.kt        # Custom Application class with DI
├── MainActivity.kt                # Main activity with Compose UI
├── data/                         # Data layer
│   ├── database/                 # Room database
│   ├── remote/                   # Network/API classes
│   └── repository/               # Repository implementations
├── domain/                       # Domain/Business logic layer
│   ├── model/                    # Business models
│   ├── repository/               # Repository interfaces
│   └── usecase/                  # Business use cases
├── presentation/                 # UI layer
│   ├── screen/                   # Compose screens
│   └── viewmodel/                # ViewModels
├── di/                          # Dependency injection
├── receiver/                    # Broadcast receivers (SMS, etc.)
└── utils/                       # Utilities
```

## Technology Stack

- **Architecture**: Clean Architecture + MVVM
- **UI**: Jetpack Compose
- **Database**: Room with SQLite
- **Networking**: Retrofit + OkHttp
- **Background Processing**: WorkManager
- **Async**: Kotlin Coroutines + Flow
- **DI**: Manual dependency injection (AppContainer pattern)
- **Image Processing**: ML Kit OCR

## Build Requirements

- **Android Studio**: Arctic Fox or newer
- **Java**: JDK 11 or higher
- **Android SDK**: API 34 (Android 14)
- **Minimum Android Version**: API 24 (Android 7.0)

## Build Instructions

1. **Set up Java**:
   ```bash
   export JAVA_HOME=/path/to/java11/or/higher
   ```

2. **Clone and build**:
   ```bash
   cd android_client/InfoAgent
   ./gradlew assembleDebug
   ```

3. **Run tests**:
   ```bash
   ./gradlew test
   ```

## Key Features

### Phase 0 - Foundation ✅ **COMPLETED**
- Clean Architecture project structure
- Room database with Memory entities
- Manual dependency injection with AppContainer
- Base classes for ViewModels and error handling
- Logging utilities

### Phase 1 - Core Data Collection (Next)
- SMS monitoring and processing
- Screenshot detection with OCR
- Share intent handling
- Basic UI for memory management

### Future Phases
- Advanced privacy controls
- Background processing optimization
- InfoAgent server integration
- Notification monitoring
- Calendar integration

## Development Status

**Current Phase**: Phase 0 Complete ✅
**Next Task**: Phase 1 - SMS Monitoring Implementation

## Architecture Decisions

1. **Manual DI over Hilt/Dagger**: Simplified setup and faster development iteration
2. **KSP over KAPT**: Modern annotation processing for Room
3. **Compose UI**: Modern declarative UI framework
4. **Clean Architecture**: Separation of concerns and testability
5. **Repository Pattern**: Data access abstraction
6. **Use Cases**: Encapsulated business logic

## Data Flow

```
UI (Compose) → ViewModel → Use Case → Repository → Database/Network
                   ↓
            StateFlow/Flow ← ProcessingResult ← Domain Models
```

## Error Handling

The project uses a `ProcessingResult<T>` sealed class for consistent error handling:
- `ProcessingResult.Success<T>`: Successful operation with data
- `ProcessingResult.Error`: Error with message and optional throwable
- `ProcessingResult.Loading`: Loading state

## Testing Strategy

- **Unit Tests**: Domain layer (use cases, models)
- **Integration Tests**: Repository and database operations
- **UI Tests**: Compose screens with Espresso

## Contributing

1. Follow the existing Clean Architecture structure
2. Use the established error handling patterns
3. Add proper logging with the Logger utility
4. Write tests for new functionality
5. Follow Kotlin coding conventions

## Next Steps

1. Implement SMS monitoring (Phase 1.1)
2. Add screenshot detection (Phase 1.2)
3. Create share intent handlers (Phase 1.3)
4. Build basic UI screens (Phase 1.4)
5. Set up WorkManager for background processing (Phase 1.5)