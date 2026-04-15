package com.rpc;

import com.rpc.core.api.annotation.RpcReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * 浏览器压测控制台。
 *
 * 这个 Controller 只放在 example-consumer 中使用：页面负责启动/停止压测任务，
 * 压测任务通过 @RpcReference 调用 provider，同时页面轮询 consumer/provider 两端可观测指标。
 */
@RestController
@RequestMapping("/benchmark/console")
public class BenchmarkConsoleController {
    private static final String DEFAULT_PROVIDER_OBSERVABILITY_URL =
            "http://127.0.0.1:18080/rpc/observability?includeServices=true&limit=200";

    @RpcReference
    private HelloService helloService;

    private final Object lifecycleMonitor = new Object();
    private final AtomicReference<LoadTask> currentTask = new AtomicReference<>();

    /** 打开压测控制台页面。 */
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public String page() {
        return String.join("\n",
                "<!doctype html>",
                "<html lang=\"zh-CN\">",
                "<head>",
                "  <meta charset=\"utf-8\">",
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
                "  <title>RPC 压测控制台</title>",
                "  <style>",
                "    :root { --bg:#f6f7f9; --panel:#ffffff; --text:#15171c; --muted:#667085; --line:#d9dee7; --accent:#0f766e; --bad:#b42318; --good:#137333; --warn:#a15c07; }",
                "    * { box-sizing: border-box; }",
                "    body { margin:0; font-family: Arial, Helvetica, sans-serif; background:var(--bg); color:var(--text); }",
                "    header { padding:20px 28px; background:var(--panel); border-bottom:1px solid var(--line); }",
                "    h1 { margin:0 0 6px; font-size:24px; }",
                "    p { margin:0; color:var(--muted); line-height:1.6; }",
                "    main { padding:22px 28px 36px; }",
                "    .controls { display:grid; grid-template-columns: repeat(6, minmax(120px, 1fr)); gap:12px; align-items:end; }",
                "    label { display:block; color:#344054; font-size:13px; }",
                "    input, select, button { width:100%; height:36px; margin-top:5px; border:1px solid var(--line); border-radius:6px; background:#fff; color:var(--text); padding:0 10px; }",
                "    button { cursor:pointer; background:var(--accent); color:#fff; border-color:var(--accent); font-weight:600; }",
                "    button.secondary { background:#fff; color:var(--text); border-color:var(--line); }",
                "    button.danger { background:var(--bad); border-color:var(--bad); }",
                "    .wide { grid-column: span 3; }",
                "    .status-line { margin-top:14px; color:var(--muted); font-size:13px; }",
                "    .grid { display:grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap:12px; margin-top:18px; }",
                "    .metric { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:14px; min-height:86px; }",
                "    .metric h2 { margin:0 0 8px; font-size:13px; color:var(--muted); }",
                "    .value { font-size:24px; font-weight:700; overflow-wrap:anywhere; }",
                "    section { margin-top:20px; background:var(--panel); border:1px solid var(--line); border-radius:8px; overflow:hidden; }",
                "    section h2 { margin:0; padding:13px 15px; border-bottom:1px solid var(--line); font-size:16px; }",
                "    table { width:100%; border-collapse:collapse; font-size:13px; }",
                "    th, td { padding:9px 11px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; }",
                "    th { background:#eef1f5; color:#344054; }",
                "    tr:last-child td { border-bottom:0; }",
                "    .ok { color:var(--good); } .bad { color:var(--bad); } .warn { color:var(--warn); }",
                "    @media (max-width: 1000px) { main, header { padding-left:14px; padding-right:14px; } .controls { grid-template-columns: repeat(2, minmax(130px, 1fr)); } .wide { grid-column: span 2; } .grid { grid-template-columns: repeat(2, minmax(130px, 1fr)); } }",
                "  </style>",
                "</head>",
                "<body>",
                "<header>",
                "  <h1>RPC 压测控制台</h1>",
                "  <p>在 consumer 进程中发起 RPC 压测，并实时查看 consumer 与 provider 两端指标。</p>",
                "</header>",
                "<main>",
                "  <div class=\"controls\">",
                "    <label>压测方法<select id=\"method\"><option value=\"hello\">hello</option><option value=\"add\">add</option><option value=\"payload\">payload</option><option value=\"sleep\">sleep</option><option value=\"unstable\">unstable</option><option value=\"mixed\">mixed</option></select></label>",
                "    <label>线程数<input id=\"threads\" type=\"number\" min=\"1\" max=\"1000\" value=\"10\"></label>",
                "    <label>持续秒数<input id=\"durationSeconds\" type=\"number\" min=\"1\" value=\"300\"></label>",
                "    <label>payload 大小<input id=\"payloadSize\" type=\"number\" min=\"0\" value=\"1024\"></label>",
                "    <label>sleep 毫秒<input id=\"sleepMillis\" type=\"number\" min=\"0\" value=\"0\"></label>",
                "    <label>失败比例<input id=\"failurePercent\" type=\"number\" min=\"0\" max=\"100\" value=\"0\"></label>",
                "    <label class=\"wide\">provider 指标地址<input id=\"providerUrl\" value=\"http://127.0.0.1:18080/rpc/observability?includeServices=true&amp;limit=200\"></label>",
                "    <label>刷新间隔<select id=\"interval\"><option value=\"1000\">1 秒</option><option value=\"3000\" selected>3 秒</option><option value=\"5000\">5 秒</option></select></label>",
                "    <button id=\"start\">开始压测</button>",
                "    <button id=\"stop\" class=\"danger\">停止压测</button>",
                "    <button id=\"refresh\" class=\"secondary\">立即刷新</button>",
                "  </div>",
                "  <div id=\"statusLine\" class=\"status-line\">等待刷新。</div>",
                "  <div class=\"grid\">",
                "    <div class=\"metric\"><h2>压测状态</h2><div id=\"running\" class=\"value\">-</div></div>",
                "    <div class=\"metric\"><h2>已运行时间</h2><div id=\"elapsed\" class=\"value\">-</div></div>",
                "    <div class=\"metric\"><h2>压测 QPS</h2><div id=\"loadQps\" class=\"value\">-</div></div>",
                "    <div class=\"metric\"><h2>压测成功/失败</h2><div id=\"loadResult\" class=\"value\">-</div></div>",
                "    <div class=\"metric\"><h2>压测 P99</h2><div id=\"loadP99\" class=\"value\">-</div></div>",
                "    <div class=\"metric\"><h2>consumer 调用数</h2><div id=\"consumerCalls\" class=\"value\">-</div></div>",
                "    <div class=\"metric\"><h2>consumer 失败数</h2><div id=\"consumerFailures\" class=\"value\">-</div></div>",
                "    <div class=\"metric\"><h2>provider 调用数</h2><div id=\"providerCalls\" class=\"value\">-</div></div>",
                "    <div class=\"metric\"><h2>provider 失败数</h2><div id=\"providerFailures\" class=\"value\">-</div></div>",
                "  </div>",
                "  <section><h2>压测任务详情</h2><table><tbody id=\"taskDetail\"></tbody></table></section>",
                "  <section><h2>consumer 服务指标</h2><table><thead><tr><th>服务</th><th>总调用</th><th>失败</th><th>平均耗时</th><th>最近耗时</th></tr></thead><tbody id=\"consumerServices\"></tbody></table></section>",
                "  <section><h2>provider 服务指标</h2><table><thead><tr><th>服务</th><th>总调用</th><th>失败</th><th>平均耗时</th><th>最近耗时</th></tr></thead><tbody id=\"providerServices\"></tbody></table></section>",
                "</main>",
                "<script>",
                "let timer = null;",
                "function byId(id){ return document.getElementById(id); }",
                "function fmt(v){ return v === null || v === undefined ? '-' : Number(v).toLocaleString(); }",
                "function ms(nanos){ return (!nanos ? 0 : Number(nanos) / 1000000).toFixed(3) + ' ms'; }",
                "function pct(v){ return Number(v || 0).toFixed(4) + '%'; }",
                "function duration(seconds){ seconds = Math.max(0, Math.floor(Number(seconds || 0))); const h = Math.floor(seconds / 3600); const m = Math.floor((seconds % 3600) / 60); const s = seconds % 60; return (h > 0 ? h + '时' : '') + m + '分' + s + '秒'; }",
                "function params(){ const p = new URLSearchParams(); ['method','threads','durationSeconds','payloadSize','sleepMillis','failurePercent'].forEach(id => p.set(id, byId(id).value)); return p; }",
                "async function post(path, body){ const r = await fetch(path, { method:'POST', body, headers:{'Content-Type':'application/x-www-form-urlencoded'} }); if(!r.ok) throw new Error(await r.text()); return r.json(); }",
                "function renderRows(id, obj){ const body = byId(id); body.innerHTML=''; Object.entries(obj || {}).forEach(([k,v]) => { const tr=document.createElement('tr'); tr.innerHTML='<td>'+k+'</td><td>'+v+'</td>'; body.appendChild(tr); }); }",
                "function summarizeServices(data){ let total=0, failed=0; Object.values((data && data.serviceMetrics) || {}).forEach(m => { total += Number(m.totalCalls || 0); failed += Number(m.failedCalls || 0); }); return { total, failed }; }",
                "function renderServices(id, data){ const body = byId(id); body.innerHTML=''; const entries = Object.entries((data && data.serviceMetrics) || {}); if(entries.length===0){ body.innerHTML='<tr><td colspan=\"5\">暂无数据</td></tr>'; return; } entries.forEach(([name,m]) => { const tr=document.createElement('tr'); tr.innerHTML='<td>'+name+'</td><td>'+fmt(m.totalCalls)+'</td><td class=\"'+(Number(m.failedCalls||0)>0?'bad':'ok')+'\">'+fmt(m.failedCalls)+'</td><td>'+ms(m.averageLatencyNanos)+'</td><td>'+ms(m.lastLatencyNanos)+'</td>'; body.appendChild(tr); }); }",
                "async function refresh(){ try { const [status, consumer, providerResp] = await Promise.all([fetch('/benchmark/console/status').then(r=>r.json()), fetch('/rpc/observability?includeServices=true&limit=200').then(r=>r.json()), fetch('/benchmark/console/provider-observability?url=' + encodeURIComponent(byId('providerUrl').value)).then(r=>r.json())]); const provider = providerResp.data; const cs = summarizeServices(consumer); const ps = summarizeServices(provider); byId('running').textContent = status.running ? '运行中' : '已停止'; byId('elapsed').textContent = status.id ? duration(status.elapsedSeconds) : '-'; byId('loadQps').textContent = Number(status.qps || 0).toFixed(2); byId('loadResult').textContent = fmt(status.success) + ' / ' + fmt(status.failure); byId('loadP99').textContent = Number(status.p99Millis || 0).toFixed(3) + ' ms'; byId('consumerCalls').textContent = fmt(cs.total); byId('consumerFailures').textContent = fmt(cs.failed); byId('providerCalls').textContent = fmt(ps.total); byId('providerFailures').textContent = fmt(ps.failed); renderRows('taskDetail', {'任务ID':status.id || '-', '方法':status.method || '-', '线程数':status.threads || '-', '开始时间':status.startedAt || '-', '预计结束时间':status.plannedEndAt || '-', '已运行时间':duration(status.elapsedSeconds), '剩余时间':duration(status.remainingSeconds), '总调用数':status.total || 0, '成功数':status.success || 0, '失败数':status.failure || 0, '失败率':pct(status.failureRate), '平均耗时':Number(status.avgMillis || 0).toFixed(3) + ' ms', 'P95':Number(status.p95Millis || 0).toFixed(3) + ' ms', 'P99':Number(status.p99Millis || 0).toFixed(3) + ' ms', '最大耗时':Number(status.maxMillis || 0).toFixed(3) + ' ms'}); renderServices('consumerServices', consumer); renderServices('providerServices', provider); byId('statusLine').textContent='刷新成功：' + new Date().toLocaleTimeString(); byId('statusLine').className='status-line ok'; } catch(e) { byId('statusLine').textContent='刷新失败：' + e.message; byId('statusLine').className='status-line bad'; } }",
                "byId('start').addEventListener('click', async () => { try { await post('/benchmark/console/start', params()); await refresh(); } catch(e) { byId('statusLine').textContent='启动失败：' + e.message; byId('statusLine').className='status-line bad'; } });",
                "byId('stop').addEventListener('click', async () => { try { await post('/benchmark/console/stop', new URLSearchParams()); await refresh(); } catch(e) { byId('statusLine').textContent='停止失败：' + e.message; byId('statusLine').className='status-line bad'; } });",
                "byId('refresh').addEventListener('click', refresh);",
                "byId('interval').addEventListener('change', () => { if(timer) clearInterval(timer); timer=setInterval(refresh, Number(byId('interval').value)); });",
                "timer=setInterval(refresh, Number(byId('interval').value)); refresh();",
                "</script>",
                "</body>",
                "</html>"
        );
    }

    /** 启动一个新的压测任务；同一时刻只允许存在一个运行中的任务。 */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam(defaultValue = "hello") String method,
                                     @RequestParam(defaultValue = "10") Integer threads,
                                     @RequestParam(defaultValue = "300") Long durationSeconds,
                                     @RequestParam(defaultValue = "1024") Integer payloadSize,
                                     @RequestParam(defaultValue = "0") Long sleepMillis,
                                     @RequestParam(defaultValue = "0") Integer failurePercent) {
        synchronized (lifecycleMonitor) {
            LoadTask existing = currentTask.get();
            if (existing != null && existing.isRunning()) {
                throw new IllegalStateException("已有压测任务正在运行，请先停止当前任务。");
            }

            LoadOptions options = LoadOptions.create(method, threads, durationSeconds, payloadSize, sleepMillis, failurePercent);
            LoadTask task = new LoadTask(helloService, options);
            currentTask.set(task);
            task.start();
            return task.snapshot();
        }
    }

    /** 停止当前压测任务。 */
    @PostMapping("/stop")
    public Map<String, Object> stop() {
        LoadTask task = currentTask.get();
        if (task != null) {
            task.stop();
            return task.snapshot();
        }
        return Map.of("running", false, "message", "当前没有压测任务。");
    }

    /** 查询当前压测任务状态。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        LoadTask task = currentTask.get();
        if (task == null) {
            return Map.of("running", false);
        }
        return task.snapshot();
    }

    /** 代理读取 provider 的可观测端点，避免浏览器因为跨端口请求被 CORS 拦截。 */
    @GetMapping("/provider-observability")
    public ResponseEntity<String> providerObservability(
            @RequestParam(defaultValue = DEFAULT_PROVIDER_OBSERVABILITY_URL) String url) throws IOException {
        String protocol = new URL(url).getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("provider 指标地址只允许使用 HTTP/HTTPS。");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"data\":" + httpGet(url) + "}");
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod("GET");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static final class LoadOptions {
        private final String method;
        private final int threads;
        private final long durationSeconds;
        private final int payloadSize;
        private final long sleepMillis;
        private final int failurePercent;

        private LoadOptions(String method, int threads, long durationSeconds, int payloadSize,
                            long sleepMillis, int failurePercent) {
            this.method = method;
            this.threads = threads;
            this.durationSeconds = durationSeconds;
            this.payloadSize = payloadSize;
            this.sleepMillis = sleepMillis;
            this.failurePercent = failurePercent;
        }

        private static LoadOptions create(String method, Integer threads, Long durationSeconds,
                                          Integer payloadSize, Long sleepMillis, Integer failurePercent) {
            String safeMethod = method == null ? "hello" : method.trim().toLowerCase(Locale.ROOT);
            if (!List.of("hello", "add", "payload", "sleep", "unstable", "mixed").contains(safeMethod)) {
                throw new IllegalArgumentException("不支持的压测方法：" + safeMethod);
            }
            return new LoadOptions(
                    safeMethod,
                    bound(threads == null ? 10 : threads, 1, 1000),
                    boundLong(durationSeconds == null ? 300L : durationSeconds, 1L, 24 * 3600L),
                    bound(payloadSize == null ? 1024 : payloadSize, 0, 1024 * 1024),
                    boundLong(sleepMillis == null ? 0L : sleepMillis, 0L, 30000L),
                    bound(failurePercent == null ? 0 : failurePercent, 0, 100)
            );
        }

        private static int bound(int value, int min, int max) {
            return Math.max(min, Math.min(value, max));
        }

        private static long boundLong(long value, long min, long max) {
            return Math.max(min, Math.min(value, max));
        }
    }

    private static final class LoadTask {
        private final String id = Instant.now().toString();
        private final HelloService helloService;
        private final LoadOptions options;
        private final LoadStats stats = new LoadStats();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong activeWorkers = new AtomicLong();
        private final AtomicReference<Instant> finishedAt = new AtomicReference<>();
        private final String payload;
        private final Instant startedAt = Instant.now();
        private final Instant plannedEndAt;
        private ExecutorService executor;

        private LoadTask(HelloService helloService, LoadOptions options) {
            this.helloService = helloService;
            this.options = options;
            this.payload = "x".repeat(options.payloadSize);
            this.plannedEndAt = startedAt.plusSeconds(options.durationSeconds);
        }

        private void start() {
            running.set(true);
            activeWorkers.set(options.threads);
            executor = Executors.newFixedThreadPool(options.threads);
            long endNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.durationSeconds);
            for (int i = 0; i < options.threads; i++) {
                executor.submit(() -> runWorker(endNanos));
            }
            executor.shutdown();
        }

        private void runWorker(long endNanos) {
            try {
                while (running.get() && System.nanoTime() < endNanos) {
                    long start = System.nanoTime();
                    try {
                        invoke();
                        stats.recordSuccess(System.nanoTime() - start);
                    } catch (Throwable throwable) {
                        stats.recordFailure(System.nanoTime() - start);
                    }
                }
            } finally {
                if (activeWorkers.decrementAndGet() == 0) {
                    finish();
                }
            }
        }

        private void invoke() {
            long next = sequence.incrementAndGet();
            if ("hello".equals(options.method)) {
                helloService.sayHello("console-" + next);
            } else if ("add".equals(options.method)) {
                helloService.add((int) (next % 1000), (int) ((next + 1) % 1000));
            } else if ("payload".equals(options.method)) {
                helloService.echoPayload(payload);
            } else if ("sleep".equals(options.method)) {
                helloService.sleep(options.sleepMillis);
            } else if ("unstable".equals(options.method)) {
                helloService.unstable("console-" + next, options.failurePercent);
            } else {
                invokeMixed(next);
            }
        }

        private void invokeMixed(long next) {
            long selector = next % 5;
            if (selector == 0) {
                helloService.sayHello("console-" + next);
            } else if (selector == 1) {
                helloService.add((int) (next % 1000), (int) ((next + 1) % 1000));
            } else if (selector == 2) {
                helloService.echoPayload(payload);
            } else if (selector == 3) {
                helloService.sleep(options.sleepMillis);
            } else {
                helloService.unstable("console-" + next, options.failurePercent);
            }
        }

        private void stop() {
            finish();
            if (executor != null) {
                executor.shutdownNow();
            }
        }

        private void finish() {
            running.set(false);
            finishedAt.compareAndSet(null, Instant.now());
        }

        private boolean isRunning() {
            return running.get();
        }

        private Map<String, Object> snapshot() {
            return stats.snapshot(id, running.get(), startedAt, plannedEndAt, finishedAt.get(), options);
        }
    }

    private static final class LoadStats {
        private static final int SAMPLE_LIMIT = 100_000;
        private final LongAdder success = new LongAdder();
        private final LongAdder failure = new LongAdder();
        private final LongAdder totalLatencyNanos = new LongAdder();
        private final AtomicLong maxLatencyNanos = new AtomicLong();
        private final AtomicLong sampleCounter = new AtomicLong();
        private final ConcurrentLinkedQueue<Long> latencySamples = new ConcurrentLinkedQueue<>();

        private void recordSuccess(long latencyNanos) {
            success.increment();
            recordLatency(latencyNanos);
        }

        private void recordFailure(long latencyNanos) {
            failure.increment();
            recordLatency(latencyNanos);
        }

        private void recordLatency(long latencyNanos) {
            totalLatencyNanos.add(latencyNanos);
            maxLatencyNanos.accumulateAndGet(latencyNanos, Math::max);
            if (sampleCounter.incrementAndGet() <= SAMPLE_LIMIT) {
                latencySamples.add(latencyNanos);
            }
        }

        private Map<String, Object> snapshot(String id, boolean running, Instant startedAt,
                                             Instant plannedEndAt, Instant finishedAt, LoadOptions options) {
            long successCount = success.sum();
            long failureCount = failure.sum();
            long total = successCount + failureCount;
            Instant snapshotAt = running || finishedAt == null ? Instant.now() : finishedAt;
            double elapsedSeconds = Math.max(0.001D,
                    (snapshotAt.toEpochMilli() - startedAt.toEpochMilli()) / 1000.0D);
            double remainingSeconds = Math.max(0.0D,
                    (plannedEndAt.toEpochMilli() - snapshotAt.toEpochMilli()) / 1000.0D);
            List<Long> samples = new ArrayList<>(latencySamples);
            Collections.sort(samples);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("running", running);
            result.put("method", options.method);
            result.put("threads", options.threads);
            result.put("startedAt", startedAt.toString());
            result.put("plannedEndAt", plannedEndAt.toString());
            result.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            result.put("elapsedSeconds", elapsedSeconds);
            result.put("remainingSeconds", running ? remainingSeconds : 0.0D);
            result.put("total", total);
            result.put("success", successCount);
            result.put("failure", failureCount);
            result.put("failureRate", total == 0 ? 0.0D : failureCount * 100.0D / total);
            result.put("qps", total / elapsedSeconds);
            result.put("avgMillis", total == 0 ? 0.0D : totalLatencyNanos.sum() / (double) total / 1_000_000.0D);
            result.put("p95Millis", percentileMillis(samples, 95.0D));
            result.put("p99Millis", percentileMillis(samples, 99.0D));
            result.put("maxMillis", maxLatencyNanos.get() / 1_000_000.0D);
            result.put("latencySamples", samples.size());
            return result;
        }

        private static double percentileMillis(List<Long> sortedSamples, double percentile) {
            if (sortedSamples.isEmpty()) {
                return 0.0D;
            }
            int index = (int) Math.ceil(percentile / 100.0D * sortedSamples.size()) - 1;
            index = Math.max(0, Math.min(index, sortedSamples.size() - 1));
            return sortedSamples.get(index) / 1_000_000.0D;
        }
    }
}
