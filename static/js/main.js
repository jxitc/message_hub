// Message Hub Web Interface JavaScript

// Configuration.
// The web UI is always served same-origin (it's rendered by the server it
// talks to), so fetch() calls use relative URLs. An empty serverUrl means
// "this site" — never hard-code 127.0.0.1:5001 here: when the page is opened
// via the public domain, that address points at the *visitor's* machine.
const DEFAULT_SERVER_URL = "";
const config = {
    serverUrl: DEFAULT_SERVER_URL
};

// Global state
let currentFilters = {
    type: '',
    device: '',
    limit: 20,
    page: 1
};

// Initialize on page load
document.addEventListener('DOMContentLoaded', function() {
    initializeApp();
    localizeTimestamps();
});

/**
 * Localize every element marked with data-utc (an explicit UTC ISO string).
 * Times are stored/served in UTC; each viewer sees them in their own
 * timezone. Falls back to the raw UTC text if parsing fails.
 */
function localizeTimestamps() {
    const pad = function(n) { return String(n).padStart(2, '0'); };
    document.querySelectorAll('[data-utc]').forEach(function(el) {
        const raw = el.getAttribute('data-utc');
        if (!raw) return;
        const d = new Date(raw);
        if (isNaN(d.getTime())) return; // keep original text
        const fmt = el.getAttribute('data-fmt') || 'datetime';
        let text;
        const weekdays = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
        const months = ['January', 'February', 'March', 'April', 'May', 'June',
                        'July', 'August', 'September', 'October', 'November', 'December'];
        if (fmt === 'date') {
            text = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
        } else if (fmt === 'time') {
            text = pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
        } else if (fmt === 'minute') {
            text = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
                   ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes());
        } else if (fmt === 'weekday') {
            text = weekdays[d.getDay()] + ', ' + months[d.getMonth()] + ' ' +
                   d.getDate() + ', ' + d.getFullYear();
        } else { // datetime
            text = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
                   ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
        }
        el.textContent = text;
    });
}

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
    // Load from localStorage (similar to CLI's config file).
    // Only honor a relative path if one was ever stored; ignore any absolute
    // URL (e.g. a stale "http://127.0.0.1:5001" saved by an older version)
    // because the web UI must always talk to the server it came from.
    const savedConfig = localStorage.getItem('messageHub.config');
    if (savedConfig) {
        try {
            const configData = JSON.parse(savedConfig);
            const url = configData.serverUrl;
            if (url && !/^[a-z][a-z0-9+.-]*:\/\//i.test(url)) {
                config.serverUrl = url.replace(/\/+$/, '');
            }
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

function applyFilters() {
    const form = document.getElementById('filter-form');
    if (!form) return;
    
    const formData = new FormData(form);
    currentFilters = {
        type: formData.get('type') || '',
        device: formData.get('device') || '',
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
            limit: 20,
            page: 1
        };
        applyFilters();
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
    showToast,
    formatTimestamp,
    formatMessageType,
    checkServerStatus
};