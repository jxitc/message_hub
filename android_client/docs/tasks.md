# Android Client Development Tasks
## InfoAgent Mobile Data Collection App

This document breaks down the Android client development into concrete, independent tasks grouped by development phases and ordered by dependencies.

## 0. Project Setup & Environment ✅ **COMPLETED**

### 0.1 Development Environment Setup
- [x] 0.1.1 Install Android Studio and Kotlin plugin
- [x] 0.1.2 Create new Android project with target SDK 34, minimum SDK 23
- [x] 0.1.3 Set up Git repository structure in android_client folder
- [x] 0.1.4 Configure Gradle build files with necessary dependencies
- [x] 0.1.5 Set up basic MVVM architecture with ViewModels and Repository pattern

### 0.2 Dependencies Configuration
- [x] 0.2.1 Add Retrofit + OkHttp for network communication
- [x] 0.2.2 Add Room database for local storage
- [x] 0.2.3 Add WorkManager for background processing
- [x] 0.2.4 Add Android Keystore encryption libraries
- [x] 0.2.5 Add ML Kit for OCR functionality
- [x] 0.2.6 Add testing dependencies (JUnit, Espresso, Mockito)

### 0.3 Project Structure
- [x] 0.3.1 Create core package structure (data, domain, presentation layers)
- [x] 0.3.2 Set up dependency injection with manual DI (AppContainer pattern)
- [x] 0.3.3 Create base classes for Activities, Fragments, ViewModels
- [x] 0.3.4 Set up logging and crash reporting
- [ ] 0.3.5 Configure build variants (debug, staging, production)

## 1. Core Infrastructure

### 1.1 Local Database Layer ✅ **MOSTLY COMPLETED**
- [x] 1.1.1 Design Room database schema for cached memories
- [x] 1.1.2 Create Entity classes for Memory, Settings, and Queue items
- [x] 1.1.3 Implement DAO interfaces for database operations
- [ ] 1.1.4 Add database migrations and version management
- [x] 1.1.5 Create Repository abstraction layer over Room

### 1.2 Network Layer Foundation ✅ **MOSTLY COMPLETED**
- [x] 1.2.1 Design InfoAgent API client interface
- [x] 1.2.2 Implement Retrofit service definitions
- [ ] 1.2.3 Add authentication handling (token management) **[NOT NEEDED FOR MVP]**
- [ ] 1.2.4 Create network state monitoring
- [x] 1.2.5 Implement retry logic with exponential backoff (for memory uploads)
- [x] 1.2.6 Add request/response logging for debugging

### 1.3 Security & Encryption **[MOVED TO PHASE 7 - NOT NEEDED FOR MVP]**
- [ ] MOVED: All security tasks moved to Section 11 (Security & Compliance)

## 2. Basic UI & Settings

### 2.1 Core UI Framework ✅ **MOSTLY COMPLETED**
- [x] 2.1.1 Create main activity with bottom navigation
- [x] 2.1.2 Design settings screen with server configuration
- [x] 2.1.3 Implement server configuration UI (URL, auto-sync toggle)
- [ ] 2.1.4 Create permission request flow
- [ ] 2.1.5 Add status dashboard showing collection statistics
- [ ] 2.1.6 **UX BUG**: Implement memory detail screen - Currently SMS content preview limited to 3 lines, no way to view full message (MemoryListScreen.kt:185)
- [ ] 2.1.7 **UX BUG**: Make MemoryCard clickable with navigation to detail view - Add onClick handler and route parameter passing (MainActivity.kt navigation)

### 2.2 Onboarding & Setup
- [ ] 2.2.1 Design welcome/onboarding flow
- [ ] 2.2.2 Create server connection setup screen
- [ ] 2.2.3 Implement permission explanation dialogs
- [ ] 2.2.4 Add data type selection interface
- [ ] 2.2.5 Create privacy settings configuration
- [ ] 2.2.6 **P1 - Notification Access Onboarding**: Create first-launch prompt/dialog that explains notification access, shows instructions, and guides user to Settings (Note: Cannot programmatically enable - Android security restriction)

### 2.3 Settings Management
- [ ] 2.3.1 Implement user preferences storage
- [ ] 2.3.2 Create privacy control toggles
- [ ] 2.3.3 Add content filtering rule configuration
- [ ] 2.3.4 Implement time-based collection controls
- [ ] 2.3.5 Create backup and restore settings functionality

## 3. Data Collection - Phase 1 (MVP)

### 3.0 Manual Memory Input ✅ **MOSTLY COMPLETED** 
- [x] 3.0.1 Create simple "Add Memory" UI screen with text input
- [x] 3.0.2 Implement basic memory creation form (content only, server generates title)
- [x] 3.0.3 Add memory submission to local database
- [x] 3.0.4 Create memory list view to display saved memories with upload status
- [x] 3.0.5 Add InfoAgent server API integration for manual submissions
- [x] 3.0.6 Test end-to-end manual memory creation workflow
- [x] 3.0.7 **FIX COMPLETED**: Fixed server response parsing issue (NullPointerException in dynamic fields)
- [x] 3.0.8 **BUG - FIXED**: Sync retry logic now session-based - each sync attempt (auto/manual) retries up to 5 times, next sync session tries again with fresh count. Memories no longer permanently stuck after failures.
- [x] 3.0.9 **NOT APPLICABLE**: Periodic background sync rejected - user prefers manual/automatic trigger-based sync only (on app launch, after SMS/notification, manual button). Session-based retry logic (3.0.8) addresses stuck uploads.
- [x] 3.0.10 **BUG - FIXED**: Background services (SMS/Notification) now trigger auto-sync after saving - memories automatically upload to server in background

### 3.1 SMS Monitoring ✅ **COMPLETED**
- [x] 3.1.1 Request SMS permissions with proper explanation
- [x] 3.1.2 Implement SMS content observer (BroadcastReceiver)
- [x] 3.1.3 Create SMS message parser and formatter
- [x] 3.1.4 Add contact name resolution
- [x] 3.1.5 ~~Implement privacy filtering for SMS content~~ **REMOVED: No filtering - capture ALL SMS**
- [x] 3.1.6 Test SMS collection with various message types

### 3.2 Screenshot Detection
- [ ] 3.2.1 Implement MediaStore observer for screenshot detection
- [ ] 3.2.2 Add image access permissions handling
- [ ] 3.2.3 Integrate ML Kit OCR for text extraction
- [ ] 3.2.4 Create image preprocessing for better OCR results
- [ ] 3.2.5 Implement screenshot content formatting
- [ ] 3.2.6 Add user confirmation for screenshot processing

### 3.3 Share Intent Handling
- [ ] 3.3.1 Register app as share target in AndroidManifest
- [ ] 3.3.2 Create share receiver activity
- [ ] 3.3.3 Handle different content types (text, images, files)
- [ ] 3.3.4 Implement content processing pipeline
- [ ] 3.3.5 Add user confirmation before saving shared content
- [ ] 3.3.6 Test with various sharing apps

### 3.4 Notification Monitoring ✅ **COMPLETED** **[PROMOTED FROM 8.1]**
- [x] 3.4.1 Request notification access permission (Settings → Notification Access)
- [x] 3.4.2 Implement NotificationListenerService for background notification monitoring
- [x] 3.4.3 Create notification content parser to extract title, text, timestamp, app package name
- [x] 3.4.4 Add notification metadata extraction (app name, icon, notification ID, priority)
- [x] 3.4.5 ~~Add app-specific filtering rules~~ **REMOVED: No filtering - capture ALL notifications**
- [x] 3.4.6 Format notification as Memory with source type (similar to SMS formatting)
- [x] 3.4.7 Save to local database using existing Memory model and MemoryRepository
- [x] 3.4.8 Reuse MemorySyncService for automatic server upload (same logic as SMS)
- [x] 3.4.9 Test notification collection with various apps (WhatsApp, Gmail, system notifications, etc.)
- [ ] 3.4.10 Handle notification dismissal/interaction events (optional metadata) **[FUTURE ENHANCEMENT]**

### 3.5 Notification Service Reliability ✅ **COMPLETED - BASIC MONITORING**
- [x] 3.5.1 Implement auto-rebind logic in NotificationListener.onListenerDisconnected() for Android 7.0+
- [x] 3.5.2 Add service connection state tracking in NotificationPermissionHelper (onServiceConnected/Disconnected)
- [x] 3.5.3 Create isNotificationServiceActive() with smart detection (handles app restart gracefully)
- [x] 3.5.4 Add UI health check in SettingsScreen showing "Active" vs "Disconnected" status with real-time updates
- [x] 3.5.5 Display warning card when service disconnected with "Toggle OFF/ON" fix instructions
- [x] 3.5.6 Add periodic UI polling (2-second interval) to detect service state changes in Settings screen
- [ ] 3.5.7 Send push notification when service disconnects to alert user **[FUTURE ENHANCEMENT]**
- [ ] 3.5.8 Create OPPO/ColorOS-specific setup guide with battery optimization instructions **[FUTURE ENHANCEMENT]**
- [ ] 3.5.9 Add first-launch tutorial for OPPO devices explaining Auto-Launch, Background Freeze, etc. **[FUTURE ENHANCEMENT]**
- [ ] 3.5.10 Implement manufacturer detection to show device-specific troubleshooting tips **[FUTURE ENHANCEMENT]**

**Note**: OPPO/Realme/OnePlus devices kill NotificationListenerService despite permission being granted due to ColorOS battery management. Auto-rebind often fails on these devices - user must manually toggle notification access OFF/ON to reconnect service. Current implementation detects this issue and guides users to the fix.

## 4. Memory Processing Pipeline

### 4.1 Content Analysis
- [ ] 4.1.1 Create content type classification system
- [ ] 4.1.2 Implement basic metadata extraction
- [ ] 4.1.3 Add timestamp and source information
- [ ] 4.1.4 Create content quality assessment
- [ ] 4.1.5 Implement duplicate detection logic

### 4.2 Privacy Filtering
- [ ] 4.2.1 Design privacy rule engine
- [ ] 4.2.2 Implement pattern-based content filtering
- [ ] 4.2.3 Add sensitive data detection (SSN, passwords, etc.)
- [ ] 4.2.4 Create user-defined filtering rules
- [ ] 4.2.5 Implement content anonymization options

### 4.3 Data Formatting
- [ ] 4.3.1 Create InfoAgent memory format converter
- [ ] 4.3.2 Add device context metadata
- [ ] 4.3.3 Implement JSON serialization
- [ ] 4.3.4 Add compression for large content
- [ ] 4.3.5 Create batch formatting for multiple memories

## 5. Background Processing

### 5.1 WorkManager Integration
- [ ] 5.1.1 Create background worker for memory upload
- [ ] 5.1.2 Implement periodic sync worker
- [ ] 5.1.3 Add constraints for battery and network state
- [ ] 5.1.4 Create retry policies for failed uploads
- [ ] 5.1.5 Implement progress tracking and notifications

### 5.2 Queue Management
- [ ] 5.2.1 Design upload queue system
- [ ] 5.2.2 Implement priority-based queuing
- [ ] 5.2.3 Add offline mode handling
- [ ] 5.2.4 Create queue persistence across app restarts
- [ ] 5.2.5 Implement queue size limits and cleanup

### 5.3 Foreground Service
- [ ] 5.3.1 Create foreground service for continuous monitoring
- [ ] 5.3.2 Add persistent notification with status
- [ ] 5.3.3 Implement service lifecycle management
- [ ] 5.3.4 Add user controls for service management
- [ ] 5.3.5 Handle Android power management restrictions

## 6. InfoAgent Server Integration

### 6.1 API Client Implementation
- [ ] 6.1.1 Implement authentication flow with InfoAgent server
- [ ] 6.1.2 Create memory creation API calls
- [ ] 6.1.3 Add batch memory upload functionality
- [ ] 6.1.4 Implement device registration and identification
- [ ] 6.1.5 Add server health checking
- [ ] 6.1.6 Test API integration with real InfoAgent server

### 6.2 Error Handling & Resilience
- [ ] 6.2.1 Implement comprehensive error handling
- [ ] 6.2.2 Add network timeout and retry logic
- [ ] 6.2.3 Create fallback mechanisms for server issues
- [ ] 6.2.4 Implement graceful degradation
- [ ] 6.2.5 Add error reporting and logging

### 6.3 Data Synchronization
- [ ] 6.3.1 Implement two-way sync capabilities
- [ ] 6.3.2 Add conflict resolution for duplicate memories
- [ ] 6.3.3 Create incremental sync mechanisms
- [ ] 6.3.4 Implement sync status tracking
- [ ] 6.3.5 Add manual sync trigger option

## 7. Testing & Quality Assurance

### 7.1 Unit Testing
- [ ] 7.1.1 Create unit tests for Repository layer
- [ ] 7.1.2 Test ViewModel logic and state management
- [ ] 7.1.3 Add tests for data processing pipeline
- [ ] 7.1.4 Test privacy filtering and content analysis
- [ ] 7.1.5 Create tests for network layer and API client

### 7.2 Integration Testing
- [ ] 7.2.1 Test end-to-end memory collection workflow
- [ ] 7.2.2 Test offline mode and queue management
- [ ] 7.2.3 Validate permissions and security features
- [ ] 7.2.4 Test different Android versions and devices
- [ ] 7.2.5 Test integration with InfoAgent server

### 7.3 UI Testing
- [ ] 7.3.1 Create Espresso tests for key user flows
- [ ] 7.3.2 Test permission request flows
- [ ] 7.3.3 Validate settings and configuration UI
- [ ] 7.3.4 Test share intent handling
- [ ] 7.3.5 Create accessibility tests

## 8. Advanced Features - Phase 2

### 8.1 Notification Access **[MOVED TO SECTION 3.4 - HIGH PRIORITY]**
- [ ] **MOVED**: All notification monitoring tasks promoted to Section 3.4 for immediate implementation

### 8.2 Enhanced Privacy Controls
- [ ] 8.2.1 Create advanced filtering rule builder
- [ ] 8.2.2 Add machine learning for content classification
- [ ] 8.2.3 Implement time-based collection schedules
- [ ] 8.2.4 Create location-based privacy controls
- [ ] 8.2.5 Add emergency privacy lockdown feature

### 8.3 Calendar Integration
- [ ] 8.3.1 Request calendar permissions
- [ ] 8.3.2 Implement calendar event monitoring
- [ ] 8.3.3 Create event content extraction
- [ ] 8.3.4 Add meeting detection and processing
- [ ] 8.3.5 Implement calendar context for other data

## 9. Performance & Optimization

### 9.1 Battery Optimization
- [ ] 9.1.1 Implement doze mode and standby handling
- [ ] 9.1.2 Optimize background processing frequency
- [ ] 9.1.3 Add battery usage monitoring
- [ ] 9.1.4 Create adaptive collection based on usage patterns
- [ ] 9.1.5 Implement smart scheduling for background tasks

### 9.2 Memory & Storage Optimization
- [ ] 9.2.1 Implement efficient caching strategies
- [ ] 9.2.2 Add automatic cache cleanup
- [ ] 9.2.3 Optimize image processing and storage
- [ ] 9.2.4 Implement data compression
- [ ] 9.2.5 Add storage usage monitoring and alerts

### 9.3 Network Optimization
- [ ] 9.3.1 Implement intelligent upload scheduling
- [ ] 9.3.2 Add network type awareness (WiFi vs mobile)
- [ ] 9.3.3 Create adaptive quality settings
- [ ] 9.3.4 Implement request batching and compression
- [ ] 9.3.5 Add bandwidth usage monitoring

## 10. Advanced Features - Phase 3

### 10.1 AI & Machine Learning
- [ ] 10.1.1 Integrate on-device ML models for content classification
- [ ] 10.1.2 Implement smart content suggestions
- [ ] 10.1.3 Add predictive collection recommendations
- [ ] 10.1.4 Create personalized privacy suggestions
- [ ] 10.1.5 Implement usage pattern analysis

### 10.2 Location Intelligence
- [ ] 10.2.1 Request location permissions with clear explanation
- [ ] 10.2.2 Implement significant location change detection
- [ ] 10.2.3 Add geofencing for context-aware collection
- [ ] 10.2.4 Create location-based memory tagging
- [ ] 10.2.5 Implement place recognition and naming

### 10.3 Advanced UI Features
- [ ] 10.3.1 Create memory browser interface
- [ ] 10.3.2 Add search functionality for local cache
- [ ] 10.3.3 Implement data visualization dashboard
- [ ] 10.3.4 Create collection insights and analytics
- [ ] 10.3.5 Add widget for quick access to features

## 11. Security & Compliance

### 11.1 Enhanced Security
- [ ] 11.1.1 Implement end-to-end encryption for all data
- [ ] 11.1.2 Add biometric authentication for app access
- [ ] 11.1.3 Create secure key rotation mechanisms
- [ ] 11.1.4 Implement tamper detection
- [ ] 11.1.5 Add security audit logging

### 11.2 Privacy Compliance
- [ ] 11.2.1 Implement GDPR compliance features
- [ ] 11.2.2 Add data export functionality
- [ ] 11.2.3 Create data deletion capabilities
- [ ] 11.2.4 Implement consent management
- [ ] 11.2.5 Add privacy impact assessments

### 11.3 Audit & Monitoring
- [ ] 11.3.1 Create comprehensive audit logging
- [ ] 11.3.2 Implement data access tracking
- [ ] 11.3.3 Add security event monitoring
- [ ] 11.3.4 Create compliance reporting
- [ ] 11.3.5 Implement user activity transparency

## 12. Deployment & Distribution

### 12.1 Build & Release
- [ ] 12.1.1 Set up automated build pipeline
- [ ] 12.1.2 Create release signing configuration
- [ ] 12.1.3 Implement automated testing in CI/CD
- [ ] 12.1.4 Add static analysis and security scanning
- [ ] 12.1.5 Create release documentation

### 12.2 Distribution Preparation
- [ ] 12.2.1 Prepare Google Play Store listing
- [ ] 12.2.2 Create app screenshots and promotional materials
- [ ] 12.2.3 Write comprehensive privacy policy
- [ ] 12.2.4 Prepare user documentation and help guides
- [ ] 12.2.5 Set up crash reporting and analytics

### 12.3 Monitoring & Support
- [ ] 12.3.1 Implement crash reporting and monitoring
- [ ] 12.3.2 Add performance monitoring
- [ ] 12.3.3 Create user feedback collection
- [ ] 12.3.4 Set up support channels and documentation
- [ ] 12.3.5 Implement remote configuration capabilities

### 12.4 Production Network Security ⚠️ **CRITICAL FOR PRODUCTION**
- [ ] 12.4.1 **REMOVE temporary HTTP cleartext permission** - Revert network_security_config.xml from "allow all" back to domain-specific whitelist
- [ ] 12.4.2 Set up HTTPS/TLS with valid SSL certificate on InfoAgent server (Let's Encrypt recommended)
- [ ] 12.4.3 Configure production domain name with proper DNS (replace IP address with domain)
- [ ] 12.4.4 Update network_security_config.xml to use domain-based security instead of IP whitelist
- [ ] 12.4.5 Implement certificate pinning for enhanced security (optional but recommended)
- [ ] 12.4.6 Set `base-config cleartextTrafficPermitted="false"` to enforce HTTPS-only in production
- [ ] 12.4.7 Test production build to ensure it rejects HTTP connections
- [ ] 12.4.8 Update server URL in app settings/documentation to use HTTPS domain
- [ ] 12.4.9 **Disable HTTP logging** - Change `InfoAgentApiClient.kt` HttpLoggingInterceptor level from BODY to NONE for production

**Note**: Current development setup uses `cleartextTrafficPermitted="true"` globally which is **NOT ACCEPTABLE** for Google Play Store submission. Must be reverted before production release.

## Dependencies & Ordering

### Phase 0: Foundation ⚠️ **REQUIRED FIRST**
Complete in order: 0.1 → 0.2 → 0.3

### Phase 1: Core Infrastructure (Parallel after Phase 0)
Can work in parallel: 1.1, 1.2, 1.3 and 2.1, 2.2, 2.3

### Phase 2: MVP Data Collection (After Phase 1)
Complete in order: 3.0 → 3.1 → **3.4 (NEXT)** → 3.2 → 3.3 → 4.1 → 4.2 → 4.3
**Note**: 3.4 (Notification Monitoring) promoted to high priority after 3.1 (SMS Monitoring)

### Phase 3: Background Processing (After Phase 2)
Complete in order: 5.1 → 5.2 → 5.3

### Phase 4: Server Integration (After Phase 3)
Complete in order: 6.1 → 6.2 → 6.3

### Phase 5: Testing & Quality (After Phase 4)
Can work in parallel: 7.1, 7.2, 7.3

### Phase 6: Advanced Features (After Phase 5)
Can work in parallel: 8.1, 8.2, 8.3 and 9.1, 9.2, 9.3

### Phase 7: Production Ready (After Phase 6)
Complete in order: 11.1 → 11.2 → 11.3 → 12.1 → 12.2 → 12.3 → 12.4

## Current Status: Notification Monitoring Complete ✅
**Phase 0 Completed**: Project foundation with Clean Architecture, Room database, manual DI, and build system
**Phase 1-2 MVP Completed**: Manual memory input with server integration working end-to-end
**Phase 3.0.7 CRITICAL FIX**: Fixed server response parsing NullPointerException - new memories now upload correctly
**Phase 3.1 SMS MONITORING COMPLETED**: Full SMS monitoring with BroadcastReceiver and contact resolution - **NO FILTERING: ALL SMS captured**
**Phase 3.4 NOTIFICATION MONITORING COMPLETED**: NotificationListenerService implementation with Settings UI - **NO FILTERING: ALL notifications captured** ✅
**Phase 3.5 SERVICE RELIABILITY COMPLETED**: Auto-rebind, real-time health monitoring with smart detection, and UI alerts (3.5.1-3.5.6) ✅ - OPPO-specific setup guides pending

**Known Issues (Non-Critical):**
- ~~**Sync Issue 1** (3.0.8): Old memories with failed uploads never retry after 5 attempts~~ **FIXED** ✅ - Now uses session-based retry (5 tries per sync session, fresh count on next sync)
- ~~**Sync Issue 2** (3.0.9): No background periodic sync~~ **NOT APPLICABLE** - User prefers trigger-based sync (auto after SMS/notification capture, manual button)
- ~~**Sync Issue 3** (3.0.10): Background auto-sync not working~~ **FIXED** ✅
- **UI Issue 1** (2.1.6): SMS content preview limited to 3 lines in list view - cannot view full message
- **UI Issue 2** (2.1.7): No detail screen to view full memory content - MemoryCard not clickable
- **UX Issue** (2.2.6): No first-launch prompt for notification access - user must discover Settings manually
- ~~**OPPO Service Issue** (3.5): NotificationListenerService disconnects on OPPO/ColorOS despite permission granted~~ **FIXED** ✅ - Real-time detection, smart state tracking, and user guidance implemented (3.5.1-3.5.6). Device-specific setup guides pending (3.5.7-3.5.10).

**⚠️ TEMPORARY DEV CONFIG**: network_security_config.xml set to allow ALL HTTP traffic - **MUST REVERT** before production (see task 12.4.1)

**Next Action**: Screenshot Detection (3.2) OR Notification Onboarding UX (2.2.6) OR Fix known UX issues (2.1.6-2.1.7) OR Complete OPPO service reliability (3.5.6-3.5.10)

---

**Key Principles:**
- **Privacy First**: Every feature must respect user privacy and provide clear controls
- **Battery Efficient**: Minimize impact on device performance and battery life
- **Secure by Design**: Implement security measures from the ground up
- **User Transparency**: Clear communication about what data is collected and why
- **Incremental Development**: Build and test core features before adding complexity
