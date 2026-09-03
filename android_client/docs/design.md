# Android Client Design Document
## InfoAgent Mobile Data Collection App

### Overview
The InfoAgent Android client is a background data collection application that captures various types of information from the user's device and automatically sends them to the InfoAgent server as memories. This enables seamless, continuous personal information management without manual input.

### Core Objectives
1. **Automatic Data Collection**: Capture SMS, screenshots, shared content, and other relevant data
2. **Privacy-First**: User control over what data is collected and when
3. **Seamless Integration**: Work transparently with InfoAgent server API
4. **Battery Efficient**: Minimize impact on device performance
5. **Secure Transmission**: Encrypt all data sent to server

## Architecture

### High-Level Architecture
```
┌─────────────────────────────────────────────────────────┐
│                 Android Client App                      │
├─────────────────────────────────────────────────────────┤
│ ┌─────────────────┐  ┌─────────────────────────────────┐│
│ │   Data Sources  │  │      Processing Pipeline        ││
│ │                 │  │                                 ││
│ │ • SMS Monitor   │  │ • Content Analyzer              ││
│ │ • Screenshot    │  │ • Privacy Filter                ││
│ │ • Share Intent  │  │ • Data Formatter                ││
│ │ • Notification  │  │ • Queue Manager                 ││
│ │ • App Usage     │  │                                 ││
│ └─────────────────┘  └─────────────────────────────────┘│
├─────────────────────────────────────────────────────────┤
│ ┌─────────────────┐  ┌─────────────────────────────────┐│
│ │  Local Storage  │  │      Network Layer              ││
│ │                 │  │                                 ││
│ │ • SQLite Cache  │  │ • InfoAgent API Client          ││
│ │ • Settings      │  │ • Retry Logic                   ││
│ │ • Temp Files    │  │ • Network State Monitor         ││
│ └─────────────────┘  └─────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────┐
│               InfoAgent Server                          │
│          (Existing Flask API + Extensions)              │
└─────────────────────────────────────────────────────────┘
```

### Component Details

#### 1. Data Sources
**SMS Monitor**
- Listen for incoming/outgoing SMS messages
- Extract relevant information (sender, content, timestamp)
- **NO AUTOMATIC FILTERING**: Capture ALL SMS messages as-is to preserve complete personal communication history

**Screenshot Detector**
- Monitor screenshot events using MediaStore observer
- Optionally perform OCR on screenshots
- Extract text content for memory creation

**Share Intent Handler**
- Register as share target for text, images, files
- Process shared content from other apps
- Convert to memory format

**Notification Access**
- Read notifications from selected apps
- Extract actionable information
- Respect user notification preferences

**App Usage Tracking**
- Monitor app usage patterns (optional)
- Track time spent in different apps
- Generate usage insights as memories

#### 2. Processing Pipeline
**Content Analyzer**
- Classify content type (personal, work, entertainment)
- Extract key information (dates, people, locations)
- Generate memory metadata

**Privacy Filter (Optional - Future Feature)**
- **CURRENTLY DISABLED**: All SMS messages are captured without filtering
- Future: User-defined privacy rules for other data sources
- Future: Optional pattern-based filtering for screenshots/notifications

**Data Formatter**
- Convert raw data to InfoAgent memory format
- Add device context (location, time, app source)
- Prepare for server transmission

**Queue Manager**
- Handle offline scenarios
- Retry failed uploads
- Manage upload priority

#### 3. Network Layer
**InfoAgent API Client**
- HTTP client for InfoAgent server API
- Authentication handling
- Request/response processing

**Retry Logic**
- Exponential backoff for failed requests
- Network state monitoring
- Queue management for offline mode

#### 4. Local Storage
**SQLite Cache**
- Temporary storage for pending uploads
- User settings and preferences
- Error logs and diagnostics

## Data Collection Types

### Phase 1: Core Data Sources
1. **SMS Messages**
   - **ALL incoming SMS messages captured without filtering**
   - Contact name resolution from phone contacts
   - Complete message preservation (no spam/OTP filtering)
   - MMS content extraction (future)

2. **Screenshots**
   - Screenshot detection
   - OCR text extraction
   - Image metadata

3. **Share Intents**
   - Text shared from other apps
   - URLs and links
   - File sharing

### Phase 2: Extended Data Sources
4. **Notifications**
   - Selected app notifications
   - System notifications
   - Actionable content

5. **Calendar Events**
   - Meeting invitations
   - Event details
   - Location information

6. **Location Context**
   - Significant location changes
   - Place visits
   - Geofenced events

### Phase 3: Advanced Features
7. **App Usage Analytics**
   - Time tracking
   - Usage patterns
   - App context

8. **Voice Recordings**
   - Call recordings (where legal)
   - Voice memos
   - Transcription

## Privacy & Security

### Privacy Controls
- **Granular Permissions**: User controls what data types to collect (SMS, screenshots, etc.)
- **No Automatic Filtering**: SMS messages are captured completely without content filtering
- **User Control**: Users can disable SMS collection entirely via app settings
- **Time-Based Controls**: Pause collection during specific hours (future feature)
- **App-Specific Rules**: Allow/block specific apps from data collection (future feature)

### Security Measures
- **End-to-End Encryption**: All data encrypted before transmission
- **Local Encryption**: Cached data encrypted on device
- **Certificate Pinning**: Secure connection to InfoAgent server
- **Authentication**: Token-based authentication with server

### Compliance
- **GDPR Compliance**: Data deletion, export capabilities
- **User Consent**: Clear consent flow for all data types
- **Audit Logging**: Track what data is collected and sent

## User Experience

### Installation & Setup
1. **Initial Setup**: Server connection configuration
2. **Permission Requests**: Explain each permission clearly
3. **Data Type Selection**: Choose what to collect
4. **Privacy Configuration**: Set filtering rules

### Ongoing Usage
- **Background Operation**: Transparent data collection
- **Status Notifications**: Periodic collection summaries
- **Manual Triggers**: On-demand screenshot analysis
- **Settings Management**: Easy privacy control adjustments

### User Interface
- **Minimal UI**: Simple settings and status screens
- **Web Interface Integration**: Option to browse collected memories
- **Quick Actions**: Fast access to common functions

## Integration with InfoAgent Server

### API Extensions Needed
1. **Mobile Client Endpoints**
   - Device registration and authentication
   - Bulk memory upload
   - Client status reporting

2. **Enhanced Memory API**
   - Device context metadata
   - Source type classification
   - Batch processing support

3. **Privacy Controls**
   - User privacy settings sync
   - Content filtering rules
   - Data deletion requests

### Data Format Enhancements
```json
{
  "content": "SMS from John: Meeting at 3pm tomorrow",
  "source": {
    "type": "sms",
    "app": "messages",
    "device_id": "android_device_123",
    "timestamp": "2024-01-15T10:30:00Z"
  },
  "metadata": {
    "sender": "John",
    "message_type": "incoming",
    "phone_number": "+1234567890"
  },
  "privacy": {
    "sensitivity_level": "medium",
    "filters_applied": ["phone_number_mask"]
  }
}
```

## Technical Requirements

### Android Platform
- **Minimum SDK**: API 23 (Android 6.0) for permissions model
- **Target SDK**: API 34 (Android 14) for latest features
- **Architecture**: MVVM with Repository pattern
- **Language**: Kotlin with coroutines for async operations

### Dependencies
- **Networking**: Retrofit + OkHttp for API communication
- **Database**: Room for local SQLite operations
- **Image Processing**: ML Kit for OCR functionality
- **Encryption**: Android Keystore + AES encryption
- **Background Processing**: WorkManager for reliable background tasks

### Performance Requirements
- **Battery Impact**: < 2% additional battery drain
- **Memory Usage**: < 50MB RAM usage
- **Storage**: < 100MB local cache maximum
- **Network Usage**: Minimize data usage with compression

## Development Phases

### Phase 1: Foundation (MVP)
- Basic app structure and server connection
- SMS monitoring and basic text extraction
- Screenshot detection with OCR
- Share intent handling
- Basic InfoAgent API integration

### Phase 2: Enhanced Collection
- Notification access and processing
- Advanced privacy controls
- Offline queue management
- Improved error handling

### Phase 3: Intelligence & Analytics
- Smart content classification
- Usage pattern analysis
- Predictive collection suggestions
- Advanced UI features

### Phase 4: Enterprise & Advanced Features
- Multiple server support
- Advanced security features
- Team/family sharing capabilities
- Custom data collection rules

## Future Considerations

### Scalability
- Support for multiple InfoAgent servers
- Cloud sync capabilities
- Cross-device memory synchronization

### AI Integration
- On-device AI for content pre-processing
- Intelligent filtering and classification
- Personalized collection recommendations

### Platform Expansion
- iOS client development
- Desktop companion apps
- Browser extension integration

---

This design document provides the foundation for building a comprehensive Android client that seamlessly integrates with the InfoAgent ecosystem while maintaining user privacy and security as top priorities.
