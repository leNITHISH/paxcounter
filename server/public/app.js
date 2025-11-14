/* ============================================
   Paxcounter - Main Application Logic
   ============================================ */

// Initialize Socket.IO connection
const socket = io();

// Application state
let live = true;
let allDevices = [];
let visibleLimit = 10;
const PAGE_STEP = 10;
let lastPayloadAt = 0;

// DOM elements
const deviceCountEl = document.getElementById('deviceCount');
const lastUpdateEl = document.getElementById('lastUpdate');
const tbody = document.getElementById('tbody');
const shownCountEl = document.getElementById('shownCount');
const totalCountEl = document.getElementById('totalCount');
const showMoreBtn = document.getElementById('showMoreBtn');
const pauseBtn = document.getElementById('pauseBtn');
const searchInput = document.getElementById('search');
const sortBy = document.getElementById('sortBy');
const exportCsv = document.getElementById('exportCsv');
const clearHistoryBtn = document.getElementById('clearHistory');
const themeToggle = document.getElementById('themeToggle');

// Initialize Chart.js
const ctx = document.getElementById('channelCountChart').getContext('2d');
const channelLabels = Array.from({length: 11}, (_, i) => '' + (i + 1));
const channelCounts = new Array(11).fill(0);
const channelAvgRssi = new Array(11).fill(0);

// Get theme colors for chart
function getThemeColors() {
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
  if (isDark) {
    return {
      primary: 'rgba(255, 42, 173, 0.4)',
      primaryBorder: 'rgba(255, 42, 173, 0.8)',
      secondary: 'rgba(255, 79, 168, 0.6)',
      text: '#ff2aad',
      muted: '#ff4fa8'
    };
  } else {
    return {
      primary: 'rgba(242, 198, 208, 0.6)',
      primaryBorder: 'rgba(242, 198, 208, 0.9)',
      secondary: 'rgba(232, 194, 106, 0.6)',
      text: '#5a1a1a',
      muted: 'rgba(90, 26, 26, 0.6)'
    };
  }
}

const channelChart = new Chart(ctx, {
  type: 'bar',
  data: {
    labels: channelLabels,
    datasets: [
      {
        label: 'Devices',
        data: channelCounts,
        backgroundColor: channelLabels.map(() => getThemeColors().primary),
        borderColor: channelLabels.map(() => getThemeColors().primaryBorder),
        borderWidth: 2,
        barThickness: 20,
        borderRadius: 4
      },
      {
        label: 'Avg RSSI',
        type: 'line',
        yAxisID: 'rssiAxis',
        data: channelAvgRssi,
        borderColor: getThemeColors().secondary,
        backgroundColor: getThemeColors().secondary,
        tension: 0.4,
        pointRadius: 5,
        pointHoverRadius: 7,
        fill: false
      }
    ]
  },
  options: {
    animation: { duration: 300 },
    responsive: true,
    maintainAspectRatio: false,
    scales: {
      y: {
        beginAtZero: true,
        position: 'left',
        title: {
          display: true,
          text: 'Device count',
          color: getThemeColors().muted,
          font: { size: 12 }
        },
        ticks: { color: getThemeColors().muted },
        grid: { color: getThemeColors().muted + '20' }
      },
      rssiAxis: {
        position: 'right',
        min: -100,
        max: -20,
        grid: { display: false },
        ticks: { color: getThemeColors().muted },
        title: {
          display: true,
          text: 'Avg RSSI (dBm)',
          color: getThemeColors().muted,
          font: { size: 12 }
        }
      },
      x: {
        ticks: { color: getThemeColors().text },
        grid: { color: getThemeColors().muted + '20' }
      }
    },
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: 'rgba(0, 0, 0, 0.8)',
        padding: 12,
        titleFont: { size: 14 },
        bodyFont: { size: 12 }
      }
    }
  }
});

// Update chart colors when theme changes
function updateChartColors() {
  const colors = getThemeColors();
  channelChart.data.datasets[0].backgroundColor = channelLabels.map(() => colors.primary);
  channelChart.data.datasets[0].borderColor = channelLabels.map(() => colors.primaryBorder);
  channelChart.data.datasets[1].borderColor = colors.secondary;
  channelChart.data.datasets[1].backgroundColor = colors.secondary;
  channelChart.options.scales.y.title.color = colors.muted;
  channelChart.options.scales.y.ticks.color = colors.muted;
  channelChart.options.scales.rssiAxis.ticks.color = colors.muted;
  channelChart.options.scales.rssiAxis.title.color = colors.muted;
  channelChart.options.scales.x.ticks.color = colors.text;
  channelChart.update('none');
}

// Theme management
function initTheme() {
  const savedTheme = localStorage.getItem('theme') || 'light';
  document.documentElement.setAttribute('data-theme', savedTheme);
  updateThemeToggleText();
  updateChartColors();
}

function toggleTheme() {
  const currentTheme = document.documentElement.getAttribute('data-theme');
  const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', newTheme);
  localStorage.setItem('theme', newTheme);
  updateThemeToggleText();
  updateChartColors();
}

function updateThemeToggleText() {
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
  themeToggle.innerHTML = isDark 
    ? '<span>🌙</span> Light Mode' 
    : '<span>☀️</span> Dark Mode';
}

// Helper functions
function formatMac(mac) {
  return String(mac || '').toUpperCase();
}

function rssiClass(r) {
  if (r >= -60) return 'strong';
  if (r >= -75) return 'medium';
  return 'weak';
}

function escapeHtml(s) {
  return String(s || '').replace(/[&<>"'`]/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
    '`': '&#96;'
  })[c]);
}

function shortVendor(v) {
  if (!v) return '';
  const parts = v.split(/\s+/);
  return parts.slice(0, 2).join(' ').slice(0, 18);
}

// Filter and sort
function applyFiltersAndSort(list) {
  const q = searchInput.value.trim().toLowerCase();
  let out = list.filter(d => {
    if (!q) return true;
    return (d.mac && d.mac.toLowerCase().includes(q)) ||
           (d.vendor && d.vendor.toLowerCase().includes(q));
  });

  const s = sortBy.value;
  if (s === 'rssi-desc') out.sort((a, b) => (b.rssi || -999) - (a.rssi || -999));
  if (s === 'rssi-asc') out.sort((a, b) => (a.rssi || -999) - (b.rssi || -999));
  if (s === 'lastSeen-asc') out.sort((a, b) => (a.lastSeen || 99999) - (b.lastSeen || 99999));
  if (s === 'lastSeen-desc') out.sort((a, b) => (b.lastSeen || 0) - (a.lastSeen || 0));

  return out;
}

// Render table
function render() {
  const filtered = applyFiltersAndSort(allDevices);
  totalCountEl.textContent = filtered.length;
  const toShow = filtered.slice(0, visibleLimit);

  shownCountEl.textContent = toShow.length;
  
  if (toShow.length === 0) {
    tbody.innerHTML = '<tr><td colspan="5" class="empty-state">No devices found</td></tr>';
  } else {
    tbody.innerHTML = toShow.map(d => {
      let vendorDisplay = '';
      if (d.vendor && d.vendor !== 'Unknown' && d.vendor !== 'Randomized') {
        vendorDisplay = `<div class="vendor">${escapeHtml(d.vendor)}</div>`;
      } else {
        vendorDisplay = '<div class="vendor randomized">Randomized</div>';
      }
      const rclass = rssiClass(d.rssi || -999);
      return `<tr>
        <td class="mac">${escapeHtml(formatMac(d.mac || ''))}</td>
        <td>${vendorDisplay}</td>
        <td class="rssi ${rclass}">${escapeHtml(String(d.rssi || ''))}</td>
        <td>${escapeHtml(String(d.ch || ''))}</td>
        <td>${escapeHtml(String(d.lastSeen || ''))}</td>
      </tr>`;
    }).join('');
  }

  showMoreBtn.style.display = (filtered.length > visibleLimit) ? 'inline-block' : 'none';
}

// Update charts
function updateCharts() {
  const counts = new Array(11).fill(0);
  const rssiSum = new Array(11).fill(0);
  const rssiCount = new Array(11).fill(0);

  allDevices.forEach(d => {
    const ch = Number(d.ch);
    if (ch >= 1 && ch <= 11) {
      counts[ch - 1]++;
      if (typeof d.rssi === 'number') {
        rssiSum[ch - 1] += d.rssi;
        rssiCount[ch - 1]++;
      }
    }
  });

  for (let i = 0; i < 11; i++) {
    channelChart.data.datasets[0].data[i] = counts[i];
    channelChart.data.datasets[1].data[i] = rssiCount[i] ? (rssiSum[i] / rssiCount[i]).toFixed(1) : null;
  }
  channelChart.update('active');
}

// Event listeners
themeToggle.addEventListener('click', toggleTheme);

pauseBtn.addEventListener('click', () => {
  live = !live;
  pauseBtn.textContent = live ? 'Pause' : 'Resume';
  pauseBtn.classList.toggle('ghost', !live);
});

showMoreBtn.addEventListener('click', () => {
  visibleLimit += PAGE_STEP;
  render();
});

searchInput.addEventListener('input', () => {
  visibleLimit = PAGE_STEP;
  render();
});

sortBy.addEventListener('change', () => {
  render();
});

exportCsv.addEventListener('click', () => {
  const hdr = ['mac', 'vendor', 'rssi', 'channel', 'lastSeen'];
  const rows = applyFiltersAndSort(allDevices).map(d => [
    d.mac, d.vendor, d.rssi, d.ch, d.lastSeen
  ]);
  const csv = [hdr, ...rows]
    .map(r => r.map(cell => `"${String(cell || '').replace(/"/g, '""')}"`).join(','))
    .join('\n');
  const blob = new Blob([csv], { type: 'text/csv' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `paxcounter-${Date.now()}.csv`;
  a.click();
  URL.revokeObjectURL(url);
});

clearHistoryBtn.addEventListener('click', () => {
  if (!confirm('Clear local device snapshot? This does not affect ESP (only UI).')) return;
  allDevices = [];
  render();
  updateCharts();
});

// Keyboard shortcut for search
window.addEventListener('keydown', (e) => {
  if (e.key === '/' && document.activeElement !== searchInput) {
    e.preventDefault();
    searchInput.focus();
    searchInput.select();
  }
});

// Socket.IO handlers
socket.on('paxdata', (payload) => {
  if (!live) return;
  
  lastPayloadAt = Date.now();
  lastUpdateEl.textContent = 'Updated ' + new Date().toLocaleTimeString();
  deviceCountEl.textContent = payload.active ?? (payload.devices?.length ?? 0);

  allDevices = (payload.devices || []).map(d => ({
    mac: d.mac,
    rssi: Number(d.rssi),
    ch: Number(d.ch),
    lastSeen: Number(d.lastSeen),
    vendor: d.vendor || ''
  }));

  render();
  updateCharts();
});

// Initialize
initTheme();
render();
updateCharts();

