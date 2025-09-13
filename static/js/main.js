// Message Hub Web Interface JavaScript

// Configuration - reuse same as CLI
const DEFAULT_SERVER_URL = "http://127.0.0.1:5001";
const config = {
    serverUrl: DEFAULT_SERVER_URL
};

// Global state
let currentFilters = {
    type: '',
    device: '',
    unread: false,
    limit: 20,
    page: 1
};

// Initialize on page load
document.addEventListener('DOMContentLoaded', function() {
    initializeApp();
});

function initializeApp() {
    // Load configuration from localStorage (mirrors CLI config)
    loadConfig();
    
    // Check server status
    checkServerStatus();
    
    // Setup event listeners
    setupEventListeners();
    
    // Initialize tooltips
    initializeTooltips();
}

function loadConfig() {
    // Load from localStorage (similar to CLI's config file)
    const savedConfig = localStorage.getItem('messageHub.config');
    if (savedConfig) {
        try {
            const configData = JSON.parse(savedConfig);
            config.serverUrl = configData.serverUrl || DEFAULT_SERVER_URL;
        } catch (e) {
            console.warn('Could not load saved config:', e);
        }
    }
}

function saveConfig() {
    // Save to localStorage
    localStorage.setItem('messageHub.config', JSON.stringify(config));
}

async function checkServerStatus() {
    const statusElement = document.getElementById('server-status');
    if (!statusElement) return;
    
    try {
        const response = await fetch(`${config.serverUrl}/health`);
        if (response.ok) {
            statusElement.textContent = 'Connected';
            statusElement.className = 'badge bg-success';
        } else {
            throw new Error(`HTTP ${response.status}`);
        }
    } catch (error) {
        statusElement.textContent = 'Disconnected';
        statusElement.className = 'badge bg-danger';
        console.error('Server connection failed:', error);
    }
}

function setupEventListeners() {
    // Mark as read buttons
    document.addEventListener('click', function(e) {
        if (e.target.matches('.btn-mark-read')) {
            e.preventDefault();
            const messageId = e.target.dataset.messageId;
            markMessageAsRead(messageId, e.target);
        }
    });
    
    // Filter form submission
    const filterForm = document.getElementById('filter-form');
    if (filterForm) {
        filterForm.addEventListener('submit', function(e) {
            e.preventDefault();
            applyFilters();
        });
    }
    
    // Clear filters button
    const clearFiltersBtn = document.getElementById('clear-filters');
    if (clearFiltersBtn) {
        clearFiltersBtn.addEventListener('click', clearFilters);
    }
    
    // Pagination links
    document.addEventListener('click', function(e) {
        if (e.target.matches('.page-link[data-page]')) {
            e.preventDefault();
            const page = parseInt(e.target.dataset.page);
            loadPage(page);
        }
    });
    
    // Auto-refresh toggle
    const autoRefreshToggle = document.getElementById('auto-refresh');
    if (autoRefreshToggle) {
        autoRefreshToggle.addEventListener('change', toggleAutoRefresh);
    }
}

function initializeTooltips() {
    // Initialize Bootstrap tooltips
    const tooltipTriggerList = [].slice.call(document.querySelectorAll('[data-bs-toggle="tooltip"]'));
    tooltipTriggerList.map(function (tooltipTriggerEl) {
        return new bootstrap.Tooltip(tooltipTriggerEl);
    });
}

async function markMessageAsRead(messageId, buttonElement) {
    const originalText = buttonElement.innerHTML;
    buttonElement.innerHTML = '<span class="spinner-border spinner-border-sm" role="status"></span>';
    buttonElement.disabled = true;
    
    try {
        const response = await fetch(`${config.serverUrl}/api/v1/messages/${messageId}/read`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            }
        });
        
        if (response.ok) {
            // Update UI
            const messageCard = buttonElement.closest('.message-card');
            if (messageCard) {
                messageCard.classList.remove('unread');
                messageCard.classList.add('read');
                
                // Update status indicator
                const statusIndicator = messageCard.querySelector('.status-indicator');
                if (statusIndicator) {
                    statusIndicator.classList.remove('status-unread');
                    statusIndicator.classList.add('status-read');
                }
                
                // Hide mark as read button
                buttonElement.style.display = 'none';
            }
            
            showToast('Message marked as read', 'success');
        } else {
            throw new Error(`HTTP ${response.status}`);
        }
    } catch (error) {
        console.error('Failed to mark message as read:', error);
        showToast('Failed to mark message as read', 'error');
        buttonElement.innerHTML = originalText;
        buttonElement.disabled = false;
    }
}

function applyFilters() {
    const form = document.getElementById('filter-form');
    if (!form) return;
    
    const formData = new FormData(form);
    currentFilters = {
        type: formData.get('type') || '',
        device: formData.get('device') || '',
        unread: formData.has('unread'),
        limit: parseInt(formData.get('limit')) || 20,
        page: 1
    };
    
    // Reload messages with new filters
    if (typeof loadMessages === 'function') {
        loadMessages();
    } else {
        // Reload page with query parameters
        const params = new URLSearchParams();
        Object.keys(currentFilters).forEach(key => {
            if (currentFilters[key]) {
                params.append(key, currentFilters[key]);
            }
        });
        
        window.location.search = params.toString();
    }
}

function clearFilters() {
    const form = document.getElementById('filter-form');
    if (form) {
        form.reset();
        currentFilters = {
            type: '',
            device: '',
            unread: false,
            limit: 20,
            page: 1
        };
        applyFilters();
    }
}

function loadPage(page) {
    currentFilters.page = page;
    if (typeof loadMessages === 'function') {
        loadMessages();
    } else {
        // Reload with new page parameter
        const params = new URLSearchParams(window.location.search);
        params.set('page', page);
        window.location.search = params.toString();
    }
}

function showToast(message, type = 'info') {
    // Create toast HTML
    const toastHtml = `
        <div class="toast align-items-center text-white bg-${type === 'error' ? 'danger' : type === 'success' ? 'success' : 'primary'}" role="alert">
            <div class="d-flex">
                <div class="toast-body">
                    ${message}
                </div>
                <button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button>
            </div>
        </div>
    `;
    
    // Add to toast container
    let toastContainer = document.querySelector('.toast-container');
    if (!toastContainer) {
        toastContainer = document.createElement('div');
        toastContainer.className = 'toast-container position-fixed top-0 end-0 p-3';
        document.body.appendChild(toastContainer);
    }
    
    const toastElement = document.createElement('div');
    toastElement.innerHTML = toastHtml;
    toastContainer.appendChild(toastElement.firstElementChild);
    
    // Initialize and show toast
    const toast = new bootstrap.Toast(toastContainer.lastElementChild);
    toast.show();
    
    // Remove from DOM after hidden
    toastContainer.lastElementChild.addEventListener('hidden.bs.toast', function() {
        this.remove();
    });
}

function formatTimestamp(timestampStr) {
    if (!timestampStr) return 'Unknown';
    
    try {
        const date = new Date(timestampStr);
        return date.toLocaleString();
    } catch (e) {
        return timestampStr;
    }
}

function formatMessageType(type) {
    const typeMap = {
        'SMS': { icon: 'bi-chat-text', color: 'primary' },
        'PUSH_NOTIFICATION': { icon: 'bi-bell', color: 'warning' },
        'EMAIL': { icon: 'bi-envelope', color: 'info' },
        'CALL_LOG': { icon: 'bi-telephone', color: 'success' }
    };
    
    return typeMap[type] || { icon: 'bi-question-circle', color: 'secondary' };
}

// Auto-refresh functionality
let autoRefreshInterval = null;

function toggleAutoRefresh() {
    const toggle = document.getElementById('auto-refresh');
    if (!toggle) return;
    
    if (toggle.checked) {
        // Start auto-refresh every 30 seconds
        autoRefreshInterval = setInterval(() => {
            if (typeof loadMessages === 'function') {
                loadMessages();
            } else {
                window.location.reload();
            }
        }, 30000);
        showToast('Auto-refresh enabled (30s)', 'success');
    } else {
        // Stop auto-refresh
        if (autoRefreshInterval) {
            clearInterval(autoRefreshInterval);
            autoRefreshInterval = null;
        }
        showToast('Auto-refresh disabled', 'info');
    }
}

// Export for use in other files
window.MessageHub = {
    config,
    markMessageAsRead,
    showToast,
    formatTimestamp,
    formatMessageType,
    checkServerStatus
};