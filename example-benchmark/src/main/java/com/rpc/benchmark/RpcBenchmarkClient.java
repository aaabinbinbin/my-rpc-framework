package com.rpc.benchmark;

import com.rpc.HelloService;
import com.rpc.core.api.bootstrap.RpcConsumerBootstrap;
import com.rpc.core.config.framework.RpcConfigLoader;
import com.rpc.core.config.framework.RpcFrameworkConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 纯 RPC 压测客户端。
 *
 * 这个类用于直接创建 RPC consumer，拿到 HelloService 代理后循环调用 provider。
 * 它不经过 HTTP Controller，因此更适合观察 RPC 框架本身的吞吐、延迟、错误率和连接池表现。
 */
public final class RpcBenchmarkClient {
    private RpcBenchmarkClient() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help) {
            Options.printHelp();
            return;
        }

        RpcFrameworkConfig config = RpcConfigLoader.load();
        options.applyTo(config);

        System.out.println("RPC 压测客户端");
        System.out.println(options.describe(config));

        try (RpcConsumerBootstrap bootstrap = RpcConsumerBootstrap.fromConfig(config)) {
            HelloService helloService = bootstrap.getService(HelloService.class);
            new BenchmarkRunner(helloService, options).run();
        }
    }

    /**
     * 压测执行器：负责创建工作线程、控制预热/正式统计窗口，并在结束后输出统计报告。
     */
    private static final class BenchmarkRunner {
        private final HelloService helloService;
        private final Options options;
        private final String payload;
        private final AtomicLong sequence = new AtomicLong();

        private BenchmarkRunner(HelloService helloService, Options options) {
            this.helloService = helloService;
            this.options = options;
            this.payload = buildPayload(options.payloadSize);
        }

        private void run() throws InterruptedException {
            Stats stats = new Stats(options.sampleLimit);
            AtomicBoolean measuring = new AtomicBoolean(false);
            long warmupNanos = TimeUnit.SECONDS.toNanos(options.warmupSeconds);
            long durationNanos = TimeUnit.SECONDS.toNanos(options.durationSeconds);
            long startNanos = System.nanoTime();
            long measureStartNanos = startNanos + warmupNanos;
            long endNanos = measureStartNanos + durationNanos;

            System.out.println("预热时间（秒）：" + options.warmupSeconds);
            System.out.println("正式统计时间（秒）：" + options.durationSeconds);

            ExecutorService workers = Executors.newFixedThreadPool(options.threads);
            ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
            reporter.scheduleAtFixedRate(new IntervalReporter(stats), options.reportIntervalSeconds,
                    options.reportIntervalSeconds, TimeUnit.SECONDS);

            for (int i = 0; i < options.threads; i++) {
                workers.submit(() -> runWorker(measuring, measureStartNanos, endNanos, stats));
            }

            long warmupMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, measureStartNanos - System.nanoTime()));
            if (warmupMillis > 0) {
                Thread.sleep(warmupMillis);
            }
            stats.markStart();
            measuring.set(true);
            System.out.println("正式统计开始");

            workers.shutdown();
            long waitMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, endNanos - System.nanoTime())) + 10_000L;
            if (!workers.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                workers.shutdownNow();
            }

            reporter.shutdownNow();
            stats.markEnd();
            stats.printFinalReport();
        }

        private void runWorker(AtomicBoolean measuring, long measureStartNanos, long endNanos, Stats stats) {
            while (System.nanoTime() < endNanos) {
                boolean measured = measuring.get() || System.nanoTime() >= measureStartNanos;
                long started = System.nanoTime();
                try {
                    invoke();
                    long latency = System.nanoTime() - started;
                    if (measured) {
                        stats.recordSuccess(latency);
                    }
                } catch (Throwable ex) {
                    long latency = System.nanoTime() - started;
                    if (measured) {
                        stats.recordFailure(latency, ex);
                    }
                }
            }
        }

        private void invoke() {
            String method = options.method;
            if ("hello".equals(method)) {
                helloService.sayHello("benchmark-" + sequence.incrementAndGet());
            } else if ("hi".equals(method)) {
                helloService.sayHi("benchmark-" + sequence.incrementAndGet());
            } else if ("add".equals(method)) {
                long next = sequence.incrementAndGet();
                helloService.add((int) (next % 1000), (int) ((next + 1) % 1000));
            } else if ("payload".equals(method)) {
                helloService.echoPayload(payload);
            } else if ("sleep".equals(method)) {
                helloService.sleep(options.sleepMillis);
            } else if ("unstable".equals(method)) {
                helloService.unstable("benchmark-" + sequence.incrementAndGet(), options.failurePercent);
            } else if ("mixed".equals(method)) {
                invokeMixed();
            } else {
                throw new IllegalArgumentException("不支持的压测方法：" + method);
            }
        }

        /**
         * 混合场景用于制造更接近真实业务的调用组合，避免只压一个极简方法导致结果过于单一。
         */
        private void invokeMixed() {
            long next = sequence.incrementAndGet();
            long selector = next % 5;
            if (selector == 0) {
                helloService.sayHello("benchmark-" + next);
            } else if (selector == 1) {
                helloService.add((int) (next % 1000), (int) ((next + 1) % 1000));
            } else if (selector == 2) {
                helloService.echoPayload(payload);
            } else if (selector == 3) {
                helloService.sleep(options.sleepMillis);
            } else {
                helloService.unstable("benchmark-" + next, options.failurePercent);
            }
        }

        /**
         * 构造固定大小的字符串，主要用于测试序列化和网络传输压力。
         */
        private static String buildPayload(int size) {
            StringBuilder builder = new StringBuilder(size);
            for (int i = 0; i < size; i++) {
                builder.append((char) ('a' + (i % 26)));
            }
            return builder.toString();
        }
    }

    /**
     * 周期性输出当前时间片的吞吐和延迟摘要，便于长时间压测时观察是否已经进入瓶颈。
     */
    private static final class IntervalReporter implements Runnable {
        private final Stats stats;
        private long lastCalls;
        private long lastSuccess;
        private long lastFailure;
        private long lastTimeNanos = System.nanoTime();

        private IntervalReporter(Stats stats) {
            this.stats = stats;
        }

        @Override
        public void run() {
            long now = System.nanoTime();
            long calls = stats.totalCalls();
            long success = stats.successCount.sum();
            long failure = stats.failureCount.sum();
            long deltaCalls = calls - lastCalls;
            long deltaSuccess = success - lastSuccess;
            long deltaFailure = failure - lastFailure;
            double seconds = Math.max(0.001D, (now - lastTimeNanos) / 1_000_000_000.0D);
            System.out.printf(Locale.ROOT,
                    "区间调用=%d 成功=%d 失败=%d QPS=%.2f 累计调用=%d 平均耗时ms=%.3f 最大耗时ms=%.3f%n",
                    deltaCalls, deltaSuccess, deltaFailure, deltaCalls / seconds, calls,
                    stats.avgLatencyMillis(), Stats.nanosToMillis(stats.maxLatencyNanos.get()));
            lastCalls = calls;
            lastSuccess = success;
            lastFailure = failure;
            lastTimeNanos = now;
        }
    }

    /**
     * 压测统计对象。
     *
     * 成功数、失败数和总耗时使用 LongAdder，减少高并发下的统计竞争。
     * 百分位延迟基于采样队列计算，避免长稳压测时无限保存所有请求耗时。
     */
    private static final class Stats {
        private final LongAdder successCount = new LongAdder();
        private final LongAdder failureCount = new LongAdder();
        private final LongAdder totalLatencyNanos = new LongAdder();
        private final AtomicLong maxLatencyNanos = new AtomicLong();
        private final ConcurrentLinkedQueue<Long> latencySamples = new ConcurrentLinkedQueue<>();
        private final AtomicLong sampleCounter = new AtomicLong();
        private final long sampleLimit;
        private final ConcurrentHashMap<String, LongAdder> errorTypes = new ConcurrentHashMap<>();
        private volatile long startedNanos;
        private volatile long endedNanos;

        private Stats(long sampleLimit) {
            this.sampleLimit = sampleLimit;
        }

        private void markStart() {
            this.startedNanos = System.nanoTime();
        }

        private void markEnd() {
            this.endedNanos = System.nanoTime();
        }

        private void recordSuccess(long latencyNanos) {
            successCount.increment();
            recordLatency(latencyNanos);
        }

        private void recordFailure(long latencyNanos, Throwable throwable) {
            failureCount.increment();
            recordLatency(latencyNanos);
            errorTypes.computeIfAbsent(errorKey(throwable), ignored -> new LongAdder()).increment();
        }

        private void recordLatency(long latencyNanos) {
            totalLatencyNanos.add(latencyNanos);
            maxLatencyNanos.accumulateAndGet(latencyNanos, Math::max);
            // 超过 sampleLimit 后仍会统计平均值和最大值，但不再保存百分位样本，避免压测客户端自身占用过多内存。
            if (sampleCounter.incrementAndGet() <= sampleLimit) {
                latencySamples.add(latencyNanos);
            }
        }

        private long totalCalls() {
            return successCount.sum() + failureCount.sum();
        }

        private double avgLatencyMillis() {
            long total = totalCalls();
            if (total == 0L) {
                return 0.0D;
            }
            return nanosToMillis(totalLatencyNanos.sum() / (double) total);
        }

        private void printFinalReport() {
            long total = totalCalls();
            long success = successCount.sum();
            long failure = failureCount.sum();
            double elapsedSeconds = elapsedSeconds();
            List<Long> samples = new ArrayList<>(latencySamples);
            Collections.sort(samples);

            System.out.println();
            System.out.println("最终压测报告");
            System.out.printf(Locale.ROOT, "实际统计耗时（秒）=%.3f%n", elapsedSeconds);
            System.out.println("总调用数=" + total);
            System.out.println("成功数=" + success);
            System.out.println("失败数=" + failure);
            System.out.printf(Locale.ROOT, "失败率=%.4f%%%n", total == 0L ? 0.0D : failure * 100.0D / total);
            System.out.printf(Locale.ROOT, "QPS=%.2f%n", total / Math.max(0.001D, elapsedSeconds));
            System.out.printf(Locale.ROOT, "平均耗时ms=%.3f%n", avgLatencyMillis());
            System.out.printf(Locale.ROOT, "P50耗时ms=%.3f%n", percentileMillis(samples, 50.0D));
            System.out.printf(Locale.ROOT, "P95耗时ms=%.3f%n", percentileMillis(samples, 95.0D));
            System.out.printf(Locale.ROOT, "P99耗时ms=%.3f%n", percentileMillis(samples, 99.0D));
            System.out.printf(Locale.ROOT, "最大耗时ms=%.3f%n", nanosToMillis(maxLatencyNanos.get()));
            System.out.println("延迟样本数=" + samples.size() + "/" + sampleCounter.get());
            printErrorTypes();
        }

        private void printErrorTypes() {
            if (errorTypes.isEmpty()) {
                return;
            }
            System.out.println("异常类型统计：");
            List<Map.Entry<String, LongAdder>> entries = new ArrayList<>(errorTypes.entrySet());
            entries.sort((left, right) -> Long.compare(right.getValue().sum(), left.getValue().sum()));
            int limit = Math.min(10, entries.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, LongAdder> entry = entries.get(i);
                System.out.println("  " + entry.getKey() + " -> " + entry.getValue().sum());
            }
        }

        private double elapsedSeconds() {
            long end = endedNanos == 0L ? System.nanoTime() : endedNanos;
            if (startedNanos == 0L || end <= startedNanos) {
                return 0.0D;
            }
            return (end - startedNanos) / 1_000_000_000.0D;
        }

        private static double percentileMillis(List<Long> sortedSamples, double percentile) {
            if (sortedSamples.isEmpty()) {
                return 0.0D;
            }
            int index = (int) Math.ceil(percentile / 100.0D * sortedSamples.size()) - 1;
            index = Math.max(0, Math.min(index, sortedSamples.size() - 1));
            return nanosToMillis(sortedSamples.get(index));
        }

        private static double nanosToMillis(double nanos) {
            return nanos / 1_000_000.0D;
        }

        private static String errorKey(Throwable throwable) {
            Throwable target = throwable;
            while (target.getCause() != null && target.getCause() != target) {
                target = target.getCause();
            }
            String message = target.getMessage();
            if (message == null || message.trim().isEmpty()) {
                return target.getClass().getName();
            }
            message = message.replace('\n', ' ').replace('\r', ' ');
            if (message.length() > 120) {
                message = message.substring(0, 120);
            }
            return target.getClass().getName() + ": " + message;
        }
    }

    /**
     * 命令行参数模型。
     *
     * 参数名保持英文，是为了和压测脚本、JMeter 文档以及 README 中的命令保持一致。
     */
    private static final class Options {
        private boolean help;
        private String registry = "8.134.204.101:2181";
        private int threads = 10;
        private long durationSeconds = 300L;
        private long warmupSeconds = 30L;
        private String method = "hello";
        private int payloadSize = 1024;
        private long sleepMillis = 0L;
        private int failurePercent = 0;
        private String serializer = null;
        private String loadBalancer = null;
        private int connectTimeout = -1;
        private int readTimeout = -1;
        private int retryTimes = -1;
        private int maxConnectionsPerAddress = -1;
        private int maxInflightRequestsPerConnection = -1;
        private int maxPendingRequests = -1;
        private long sampleLimit = 1_000_000L;
        private long reportIntervalSeconds = 5L;

        private static Options parse(String[] args) {
            Options options = new Options();
            Map<String, String> values = new HashMap<>();
            for (String arg : args) {
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    options.help = true;
                    return options;
                }
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("参数必须以 -- 开头：" + arg);
                }
                int equals = arg.indexOf('=');
                if (equals < 0) {
                    throw new IllegalArgumentException("参数必须使用 --key=value 格式：" + arg);
                }
                values.put(arg.substring(2, equals), arg.substring(equals + 1));
            }

            options.registry = stringValue(values, "registry", options.registry);
            options.threads = intValue(values, "threads", options.threads, 1, Integer.MAX_VALUE);
            options.durationSeconds = longValue(values, "durationSeconds", options.durationSeconds, 1L, Long.MAX_VALUE);
            options.warmupSeconds = longValue(values, "warmupSeconds", options.warmupSeconds, 0L, Long.MAX_VALUE);
            options.method = stringValue(values, "method", options.method).toLowerCase(Locale.ROOT);
            options.payloadSize = intValue(values, "payloadSize", options.payloadSize, 0, 10 * 1024 * 1024);
            options.sleepMillis = longValue(values, "sleepMillis", options.sleepMillis, 0L, 30_000L);
            options.failurePercent = intValue(values, "failurePercent", options.failurePercent, 0, 100);
            options.serializer = optionalString(values, "serializer");
            options.loadBalancer = optionalString(values, "loadbalancer");
            options.connectTimeout = intValue(values, "connectTimeout", options.connectTimeout, -1, Integer.MAX_VALUE);
            options.readTimeout = intValue(values, "readTimeout", options.readTimeout, -1, Integer.MAX_VALUE);
            options.retryTimes = intValue(values, "retryTimes", options.retryTimes, -1, Integer.MAX_VALUE);
            options.maxConnectionsPerAddress = intValue(values, "maxConnectionsPerAddress",
                    options.maxConnectionsPerAddress, -1, Integer.MAX_VALUE);
            options.maxInflightRequestsPerConnection = intValue(values, "maxInflightRequestsPerConnection",
                    options.maxInflightRequestsPerConnection, -1, Integer.MAX_VALUE);
            options.maxPendingRequests = intValue(values, "maxPendingRequests", options.maxPendingRequests,
                    -1, Integer.MAX_VALUE);
            options.sampleLimit = longValue(values, "sampleLimit", options.sampleLimit, 0L, Long.MAX_VALUE);
            options.reportIntervalSeconds = longValue(values, "reportIntervalSeconds",
                    options.reportIntervalSeconds, 1L, Long.MAX_VALUE);
            validateMethod(options.method);
            return options;
        }

        private void applyTo(RpcFrameworkConfig config) {
            config.setRegistryAddress(registry);
            if (serializer != null) {
                config.setSerializer(serializer);
            }
            if (loadBalancer != null) {
                config.setLoadBalancer(loadBalancer);
            }
            if (connectTimeout >= 0) {
                config.setConnectTimeout(connectTimeout);
            }
            if (readTimeout >= 0) {
                config.setReadTimeout(readTimeout);
            }
            if (retryTimes >= 0) {
                config.setRetryTimes(retryTimes);
            }
            if (maxConnectionsPerAddress >= 0) {
                config.setMaxConnectionsPerAddress(maxConnectionsPerAddress);
            }
            if (maxInflightRequestsPerConnection >= 0) {
                config.setMaxInflightRequestsPerConnection(maxInflightRequestsPerConnection);
            }
            if (maxPendingRequests >= 0) {
                config.setMaxPendingRequests(maxPendingRequests);
            }
        }

        private String describe(RpcFrameworkConfig config) {
            return "注册中心=" + config.getRegistryAddress()
                    + ", 并发线程数=" + threads
                    + ", 压测方法=" + method
                    + ", 序列化器=" + config.getSerializer()
                    + ", 负载均衡器=" + config.getLoadBalancer()
                    + ", 连接超时ms=" + config.getConnectTimeout()
                    + ", 读取超时ms=" + config.getReadTimeout()
                    + ", 重试次数=" + config.getRetryTimes()
                    + ", 单地址最大连接数=" + config.getMaxConnectionsPerAddress()
                    + ", 单连接最大在途请求数=" + config.getMaxInflightRequestsPerConnection()
                    + ", 总pending请求上限=" + config.getMaxPendingRequests()
                    + ", 延迟样本上限=" + sampleLimit;
        }

        private static void validateMethod(String method) {
            if (!"hello".equals(method) && !"hi".equals(method) && !"add".equals(method)
                    && !"payload".equals(method) && !"sleep".equals(method)
                    && !"unstable".equals(method) && !"mixed".equals(method)) {
                throw new IllegalArgumentException("不支持的压测方法：" + method);
            }
        }

        private static void printHelp() {
            System.out.println("用法：");
            System.out.println("  java -jar example-benchmark/target/example-benchmark-1.0-SNAPSHOT.jar --registry=127.0.0.1:2181 --threads=50 --durationSeconds=300 --warmupSeconds=30 --method=hello");
            System.out.println("参数：");
            System.out.println("  --registry=127.0.0.1:2181                      ZooKeeper 注册中心地址");
            System.out.println("  --threads=50                                    并发线程数");
            System.out.println("  --durationSeconds=300                           正式统计持续时间，单位秒");
            System.out.println("  --warmupSeconds=30                              预热时间，单位秒，预热阶段不计入最终统计");
            System.out.println("  --method=hello|hi|add|payload|sleep|unstable|mixed  压测方法");
            System.out.println("  --payloadSize=1024                              payload 字符串大小");
            System.out.println("  --sleepMillis=100                               服务端 sleep 时间，单位毫秒");
            System.out.println("  --failurePercent=10                             unstable 方法的可控失败比例");
            System.out.println("  --serializer=protobuf                           序列化器");
            System.out.println("  --loadbalancer=random                           负载均衡器");
            System.out.println("  --connectTimeout=5000                           连接超时时间，单位毫秒");
            System.out.println("  --readTimeout=10000                             读取超时时间，单位毫秒");
            System.out.println("  --retryTimes=3                                  重试次数");
            System.out.println("  --maxConnectionsPerAddress=2                    单个 provider 地址的最大连接数");
            System.out.println("  --maxInflightRequestsPerConnection=256          单连接最大在途请求数");
            System.out.println("  --maxPendingRequests=10000                      consumer 总 pending request 上限");
            System.out.println("  --sampleLimit=1000000                           最多保存的延迟样本数");
            System.out.println("  --reportIntervalSeconds=5                       区间报告输出间隔，单位秒");
        }

        private static String optionalString(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            return value.trim();
        }

        private static String stringValue(Map<String, String> values, String key, String defaultValue) {
            String value = values.get(key);
            if (value == null || value.trim().isEmpty()) {
                return defaultValue;
            }
            return value.trim();
        }

        private static int intValue(Map<String, String> values, String key, int defaultValue, int min, int max) {
            String value = values.get(key);
            if (value == null || value.trim().isEmpty()) {
                return defaultValue;
            }
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException("参数超出范围：" + key + "=" + parsed);
            }
            return parsed;
        }

        private static long longValue(Map<String, String> values, String key, long defaultValue, long min, long max) {
            String value = values.get(key);
            if (value == null || value.trim().isEmpty()) {
                return defaultValue;
            }
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException("参数超出范围：" + key + "=" + parsed);
            }
            return parsed;
        }
    }
}
