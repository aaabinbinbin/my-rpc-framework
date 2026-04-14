package com.rpc.spring.boot;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser dashboard for RPC observability snapshots.
 *
 * <p>The page polls {@code /rpc/observability} from the same application process.
 * Provider and consumer processes should be opened separately when both expose the dashboard.</p>
 */
@RestController
@RequestMapping("/rpc/observability")
public class RpcObservabilityDashboardEndpoint {
    @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public String dashboard() {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>RPC Runtime Dashboard</title>
                  <style>
                    :root {
                      --bg: #f7f8fa;
                      --panel: #ffffff;
                      --text: #16181d;
                      --muted: #68707d;
                      --line: #dfe3ea;
                      --accent: #0f766e;
                      --warn: #b45309;
                      --bad: #b91c1c;
                      --good: #15803d;
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      font-family: Arial, Helvetica, sans-serif;
                      background: var(--bg);
                      color: var(--text);
                    }
                    header {
                      padding: 22px 28px;
                      border-bottom: 1px solid var(--line);
                      background: var(--panel);
                    }
                    h1 { margin: 0 0 6px; font-size: 24px; }
                    p { margin: 0; color: var(--muted); line-height: 1.5; }
                    main { padding: 24px 28px 32px; }
                    .toolbar {
                      display: flex;
                      flex-wrap: wrap;
                      align-items: center;
                      gap: 12px;
                      margin-bottom: 18px;
                    }
                    button, select, input {
                      height: 34px;
                      border: 1px solid var(--line);
                      border-radius: 6px;
                      background: #fff;
                      color: var(--text);
                      padding: 0 10px;
                    }
                    button {
                      background: var(--accent);
                      color: #fff;
                      border-color: var(--accent);
                      cursor: pointer;
                    }
                    .status {
                      font-size: 13px;
                      color: var(--muted);
                    }
                    .grid {
                      display: grid;
                      grid-template-columns: repeat(4, minmax(160px, 1fr));
                      gap: 14px;
                    }
                    .metric {
                      background: var(--panel);
                      border: 1px solid var(--line);
                      border-radius: 8px;
                      padding: 16px;
                    }
                    .metric h2 {
                      margin: 0 0 8px;
                      font-size: 13px;
                      color: var(--muted);
                      font-weight: 600;
                    }
                    .metric .value {
                      font-size: 26px;
                      font-weight: 700;
                      overflow-wrap: anywhere;
                    }
                    section {
                      margin-top: 22px;
                      background: var(--panel);
                      border: 1px solid var(--line);
                      border-radius: 8px;
                      overflow: hidden;
                    }
                    section h2 {
                      margin: 0;
                      padding: 14px 16px;
                      font-size: 16px;
                      border-bottom: 1px solid var(--line);
                    }
                    table {
                      width: 100%;
                      border-collapse: collapse;
                      font-size: 14px;
                    }
                    th, td {
                      padding: 10px 12px;
                      border-bottom: 1px solid var(--line);
                      text-align: left;
                      vertical-align: top;
                    }
                    th {
                      background: #f1f3f6;
                      color: #3f4652;
                      font-weight: 600;
                    }
                    tr:last-child td { border-bottom: 0; }
                    .ok { color: var(--good); }
                    .warn { color: var(--warn); }
                    .bad { color: var(--bad); }
                    .empty {
                      padding: 16px;
                      color: var(--muted);
                    }
                    @media (max-width: 900px) {
                      main { padding: 18px 14px; }
                      header { padding: 18px 14px; }
                      .grid { grid-template-columns: repeat(2, minmax(130px, 1fr)); }
                      table { font-size: 12px; }
                      th, td { padding: 8px; }
                    }
                  </style>
                </head>
                <body>
                  <header>
                    <h1>RPC Runtime Dashboard</h1>
                    <p>Live snapshot from this JVM. Open provider and consumer dashboards separately to compare both sides.</p>
                  </header>
                  <main>
                    <div class="toolbar">
                      <button id="refresh">Refresh now</button>
                      <label>Interval
                        <select id="interval">
                          <option value="1000">1s</option>
                          <option value="3000" selected>3s</option>
                          <option value="5000">5s</option>
                          <option value="10000">10s</option>
                        </select>
                      </label>
                      <label>Service limit
                        <input id="limit" type="number" min="1" max="200" value="200">
                      </label>
                      <span id="status" class="status">Waiting for first refresh...</span>
                    </div>

                    <div class="grid">
                      <div class="metric"><h2>Total Services</h2><div id="totalServices" class="value">-</div></div>
                      <div class="metric"><h2>Returned Services</h2><div id="returnedServices" class="value">-</div></div>
                      <div class="metric"><h2>Total Calls</h2><div id="totalCalls" class="value">-</div></div>
                      <div class="metric"><h2>Failed Calls</h2><div id="failedCalls" class="value">-</div></div>
                      <div class="metric"><h2>Avg Latency</h2><div id="avgLatency" class="value">-</div></div>
                      <div class="metric"><h2>Last Latency</h2><div id="lastLatency" class="value">-</div></div>
                      <div class="metric"><h2>Pending Rejections</h2><div id="pendingRejections" class="value">-</div></div>
                      <div class="metric"><h2>Reconnect Failures</h2><div id="reconnectFailures" class="value">-</div></div>
                    </div>

                    <section>
                      <h2>Client Runtime</h2>
                      <table>
                        <tbody id="clientRuntime"></tbody>
                      </table>
                    </section>

                    <section>
                      <h2>Service Metrics</h2>
                      <div id="serviceEmpty" class="empty">No service metrics yet.</div>
                      <table id="serviceTable" style="display:none">
                        <thead>
                          <tr>
                            <th>Service</th>
                            <th>Total</th>
                            <th>Failed</th>
                            <th>Failure Rate</th>
                            <th>Average Latency</th>
                            <th>Last Latency</th>
                          </tr>
                        </thead>
                        <tbody id="serviceMetrics"></tbody>
                      </table>
                    </section>
                  </main>
                  <script>
                    const statusEl = document.getElementById('status');
                    let timer = null;

                    function fmtNumber(value) {
                      if (value === null || value === undefined) return '-';
                      return Number(value).toLocaleString();
                    }

                    function nanosToMs(nanos) {
                      if (!nanos) return '0 ms';
                      return (Number(nanos) / 1000000).toFixed(3) + ' ms';
                    }

                    function setText(id, value) {
                      document.getElementById(id).textContent = value;
                    }

                    function renderClientRuntime(runtime) {
                      const body = document.getElementById('clientRuntime');
                      body.innerHTML = '';
                      Object.entries(runtime || {}).forEach(([key, value]) => {
                        const row = document.createElement('tr');
                        row.innerHTML = '<td>' + key + '</td><td>' + fmtNumber(value) + '</td>';
                        body.appendChild(row);
                      });
                    }

                    function renderServices(metrics) {
                      const entries = Object.entries(metrics || {});
                      const body = document.getElementById('serviceMetrics');
                      const table = document.getElementById('serviceTable');
                      const empty = document.getElementById('serviceEmpty');
                      body.innerHTML = '';
                      if (entries.length === 0) {
                        table.style.display = 'none';
                        empty.style.display = 'block';
                        return { totalCalls: 0, failedCalls: 0, avgLatencyNanos: 0, lastLatencyNanos: 0 };
                      }
                      table.style.display = 'table';
                      empty.style.display = 'none';
                      let totalCalls = 0;
                      let failedCalls = 0;
                      let weightedLatency = 0;
                      let lastLatencyNanos = 0;
                      entries.forEach(([name, metric]) => {
                        const calls = Number(metric.totalCalls || 0);
                        const failures = Number(metric.failedCalls || 0);
                        const avg = Number(metric.averageLatencyNanos || 0);
                        const last = Number(metric.lastLatencyNanos || 0);
                        totalCalls += calls;
                        failedCalls += failures;
                        weightedLatency += avg * calls;
                        lastLatencyNanos = Math.max(lastLatencyNanos, last);
                        const failureRate = calls === 0 ? 0 : (failures * 100 / calls);
                        const row = document.createElement('tr');
                        row.innerHTML =
                          '<td>' + name + '</td>' +
                          '<td>' + fmtNumber(calls) + '</td>' +
                          '<td class="' + (failures > 0 ? 'bad' : 'ok') + '">' + fmtNumber(failures) + '</td>' +
                          '<td>' + failureRate.toFixed(2) + '%</td>' +
                          '<td>' + nanosToMs(avg) + '</td>' +
                          '<td>' + nanosToMs(last) + '</td>';
                        body.appendChild(row);
                      });
                      return {
                        totalCalls,
                        failedCalls,
                        avgLatencyNanos: totalCalls === 0 ? 0 : weightedLatency / totalCalls,
                        lastLatencyNanos
                      };
                    }

                    async function refresh() {
                      const limit = document.getElementById('limit').value || '200';
                      const basePath = window.location.pathname.replace(/\\/dashboard$/, '');
                      const url = basePath + '?includeServices=true&limit=' + encodeURIComponent(limit);
                      try {
                        const response = await fetch(url, { cache: 'no-store' });
                        if (!response.ok) throw new Error('HTTP ' + response.status);
                        const data = await response.json();
                        const aggregate = renderServices(data.serviceMetrics || {});
                        renderClientRuntime(data.clientRuntime || {});
                        setText('totalServices', fmtNumber(data.totalServices));
                        setText('returnedServices', fmtNumber(data.returnedServices));
                        setText('totalCalls', fmtNumber(aggregate.totalCalls));
                        setText('failedCalls', fmtNumber(aggregate.failedCalls));
                        setText('avgLatency', nanosToMs(aggregate.avgLatencyNanos));
                        setText('lastLatency', nanosToMs(aggregate.lastLatencyNanos));
                        setText('pendingRejections', fmtNumber((data.clientRuntime || {}).pendingLimitRejections));
                        setText('reconnectFailures', fmtNumber((data.clientRuntime || {}).reconnectFailedCount));
                        statusEl.textContent = 'Updated at ' + new Date().toLocaleTimeString();
                        statusEl.className = 'status ok';
                      } catch (error) {
                        statusEl.textContent = 'Refresh failed: ' + error.message;
                        statusEl.className = 'status bad';
                      }
                    }

                    function resetTimer() {
                      if (timer) clearInterval(timer);
                      const interval = Number(document.getElementById('interval').value);
                      timer = setInterval(refresh, interval);
                    }

                    document.getElementById('refresh').addEventListener('click', refresh);
                    document.getElementById('interval').addEventListener('change', resetTimer);
                    document.getElementById('limit').addEventListener('change', refresh);
                    resetTimer();
                    refresh();
                  </script>
                </body>
                </html>
                """;
    }
}
