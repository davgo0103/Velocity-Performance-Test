package com.serverperf;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.slf4j.Logger;

/**
 * 網路測速。
 *
 * <p>全面改用 Java 11 起的 {@link HttpClient}：舊版的 {@code new URL(String)} 自 Java 20 起
 * 已棄用，而 {@code HttpURLConnection} 沒有 HTTP/2、沒有連線池、逾時語意混亂。
 * 所有阻塞式 I/O 都跑在虛擬執行緒上，不再佔用 {@code ForkJoinPool.commonPool}。
 *
 * <p>頻寬的量法刻意對齊 Ookla / Cloudflare / LibreSpeed 的做法，而不是天真的
 * 「總位元組 ÷ 總時間」：
 * <ul>
 *   <li><b>多連線</b>——單條 TCP 的吞吐受 BDP（頻寬 × 延遲）壓制，跨國線路上通常卡在
 *       兩三百 Mbps，量到的是 TCP 的上限而不是線路的上限。</li>
 *   <li><b>傳輸強制走 HTTP/1.1</b>——HTTP/2 會把所有請求多工在同一條 TCP 連線上，
 *       開再多串流也只是同一條連線上的 h2 stream，BDP 一點都沒放寬，還多背了每個
 *       stream 的流量控制與 WINDOW_UPDATE 往返，反而比單流更慢。要真的拿到 N 條
 *       TCP，就得讓每個請求各自佔一條連線。</li>
 *   <li><b>共用位元組配額</b>——各串流的速度不會一致，若各自給固定配額，快的先收工，
 *       尾巴只剩一兩條在跑，這段往下掉的斜坡會被算進穩態窗而拉低平均。改成共用一個
 *       總量目標，所有串流幾乎同時收手。</li>
 *   <li><b>排除暖機期</b>——TCP 慢啟動要一秒上下才爬到穩態，算進平均只會低估。</li>
 *   <li><b>排除 TTFB</b>——連線建立與等待第一個位元組的時間不屬於傳輸時間。</li>
 * </ul>
 */
final class NetworkTester {

    private static final String USER_AGENT = "VelocityPerformanceTest/2.0 (+https://github.com/davgo0103/ServerPerformanceTest)";
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final int MAX_UPLOAD_ATTEMPTS = 2;
    /** 低於此量的下載回應視為節點失效，而非真實的測速結果。 */
    private static final long MIN_VALID_DOWNLOAD_BYTES = 1024L * 1024L;
    /** 傳輸過程中記錄累計位元組的間隔。 */
    private static final long SAMPLE_INTERVAL_MILLIS = 100L;

    private final PerfConfig config;
    private final Logger logger;
    private final HttpClient http;

    NetworkTester(PerfConfig config, Logger logger, ExecutorService executor) {
        this.config = config;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
    }

    /** 延遲統計。jitter 為相鄰取樣之間差值的平均絕對值（RFC 3550 的簡化版）。 */
    record Latency(double avgMs, double minMs, double jitterMs) {
    }

    /**
     * 一次測速的完整結果。量測失敗的項目以空的 Optional 表示，
     * 而非舊版那種混入顯示文字的 {@code "N/A (重試 3 次後失敗)"} 字串。
     */
    record Result(
            Optional<String> publicIp,
            Optional<String> targetName,
            Optional<Latency> latency,
            OptionalDouble downloadMbps,
            OptionalDouble uploadMbps,
            long downloadedBytes,
            long uploadedBytes,
            int streams,
            Duration elapsed,
            Instant takenAt) {
    }

    Result run() throws InterruptedException {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();

        // 對外 IP 查詢與節點延遲量測互不相依，平行進行。
        CompletableFuture<Optional<String>> publicIp =
                CompletableFuture.supplyAsync(this::lookupPublicIp, executor());

        List<Probe> probes = probeTargets();

        Optional<Latency> latency = probes.stream().findFirst().map(Probe::latency);
        Optional<String> targetName = Optional.empty();
        OptionalDouble downloadMbps = OptionalDouble.empty();
        long downloadedBytes = 0;

        // 依延遲由低到高依序嘗試，而不是只賭最快的那一個。
        for (Probe probe : probes.subList(0, Math.min(probes.size(), MAX_DOWNLOAD_ATTEMPTS))) {
            Optional<Transfer> transfer = download(probe.target());
            if (transfer.isPresent()) {
                targetName = Optional.of(probe.target().name());
                latency = Optional.of(probe.latency());
                downloadMbps = OptionalDouble.of(transfer.get().mbps());
                downloadedBytes = transfer.get().bytes();
                break;
            }
            logger.warn("下載測試在節點 {} 失敗，改試下一個節點", probe.target().name());
        }

        OptionalDouble uploadMbps = OptionalDouble.empty();
        long uploadedBytes = 0;
        if (config.uploadEnabled()) {
            for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
                Optional<Transfer> transfer = upload();
                if (transfer.isPresent()) {
                    uploadMbps = OptionalDouble.of(transfer.get().mbps());
                    uploadedBytes = transfer.get().bytes();
                    break;
                }
            }
        }

        return new Result(
                publicIp.join(),
                targetName,
                latency,
                downloadMbps,
                uploadMbps,
                downloadedBytes,
                uploadedBytes,
                config.parallelStreams(),
                Duration.ofNanos(System.nanoTime() - startNanos),
                startedAt);
    }

    private record Probe(PerfConfig.Target target, Latency latency) {
    }

    record Transfer(long bytes, double mbps) {
    }

    private Executor executor() {
        return http.executor().orElse(Runnable::run);
    }

    /** 平行量測所有節點的延遲，回傳可連通的節點（依平均延遲排序）。 */
    private List<Probe> probeTargets() {
        List<CompletableFuture<Optional<Probe>>> futures = config.downloadTargets().stream()
                .map(target -> CompletableFuture.supplyAsync(
                        () -> measureLatency(target).map(l -> new Probe(target, l)),
                        executor()))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingDouble(probe -> probe.latency().avgMs()))
                .toList();
    }

    /**
     * 以帶 Range 標頭的 GET 取樣延遲，量測到收到回應標頭為止（TTFB）。
     *
     * <p>刻意不用 HEAD：實測 Hetzner 的測速端點對 HEAD 直接斷線，
     * 用 HEAD 會把可用的節點誤判為離線。而即使伺服器忽略 Range 標頭，
     * 我們也只讀取標頭就關閉串流，HttpClient 會取消其餘的傳輸。
     *
     * <p>第一次往返會包含 TCP、TLS 與 HTTP/2 的握手，動輒是後續 RTT 的十幾倍；
     * 把它算進樣本會同時灌大平均值與 jitter（實測 avg 157 ms / min 40 ms /
     * jitter 145 ms 的落差，幾乎全部來自這一筆）。因此多跑一次當暖機，不計入統計。
     */
    private Optional<Latency> measureLatency(PerfConfig.Target target) {
        URI uri;
        try {
            uri = URI.create(target.resolveUrl(1));
        } catch (IllegalArgumentException e) {
            logger.warn("節點 {} 的 URL 無效：{}", target.name(), e.getMessage());
            return Optional.empty();
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(config.connectTimeout())
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-0")
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        List<Double> samples = new ArrayList<>(config.pingSamples());
        for (int i = 0; i <= config.pingSamples(); i++) {
            long start = System.nanoTime();
            try {
                HttpResponse<InputStream> response =
                        http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                response.body().close();
                if (response.statusCode() >= 400) {
                    return Optional.empty();
                }
                if (i > 0) {
                    samples.add(elapsed);
                }
            } catch (IOException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }

        if (samples.isEmpty()) {
            return Optional.empty();
        }

        double avg = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double min = samples.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double jitter = 0;
        for (int i = 1; i < samples.size(); i++) {
            jitter += Math.abs(samples.get(i) - samples.get(i - 1));
        }
        if (samples.size() > 1) {
            jitter /= samples.size() - 1;
        }
        return Optional.of(new Latency(avg, min, jitter));
    }

    /**
     * 下載測速。以多條連線平行抓取，全程由共用的計數器累計進度，
     * 再交給 {@link #summarise} 排除 TTFB 與慢啟動後估算穩態速率。
     */
    private Optional<Transfer> download(PerfConfig.Target target) {
        long goal = config.downloadBytes();
        AtomicLong counter = new AtomicLong();
        AtomicBoolean rejected = new AtomicBoolean();
        long deadline = System.nanoTime() + config.transferBudget().toNanos();

        // 每條串流都向節點要滿額，實際各拿多少由共用的計數器決定——慢的串流少拿一點，
        // 快的多拿一點，但大家都是在總量達標的同一刻停手。
        CompletableFuture<Void> all = CompletableFuture.allOf(IntStream.range(0, config.parallelStreams())
                .mapToObj(i -> CompletableFuture.runAsync(
                        () -> downloadStream(target, goal, counter, rejected, deadline), executor()))
                .toArray(CompletableFuture[]::new));

        List<long[]> samples;
        try {
            samples = awaitAndSample(counter, all, deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

        if (rejected.get()) {
            return Optional.empty();
        }

        // 若不是因為時間預算而中止，卻只拿到遠少於要求的資料量，
        // 代表節點沒有真的提供檔案——此時回報失敗，讓上層改試下一個節點，
        // 而不是拿 25 bytes 去算出一個毫無意義的 Mbps 數字。
        long total = counter.get();
        long minimumAcceptable = Math.min(config.downloadBytes(), MIN_VALID_DOWNLOAD_BYTES);
        if (System.nanoTime() < deadline && total < minimumAcceptable) {
            logger.warn("節點 {} 只回傳 {} bytes（要求 {} bytes），判定為失效",
                    target.name(), total, config.downloadBytes());
            return Optional.empty();
        }

        return summarise(samples, config.warmup().toNanos());
    }

    /**
     * 單一下載串流。硬性的失敗（4xx、回傳 HTML 錯誤頁）會設下 {@code rejected} 旗標，
     * 讓整個節點被判定失效；個別串流的 I/O 錯誤則只是少貢獻一些位元組。
     */
    private void downloadStream(
            PerfConfig.Target target, long goal, AtomicLong counter, AtomicBoolean rejected, long deadline) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.resolveUrl(goal)))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(config.requestTimeout())
                .header("User-Agent", USER_AGENT)
                .header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "identity")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                logger.warn("節點 {} 回應 HTTP {}", target.name(), response.statusCode());
                response.body().close();
                rejected.set(true);
                return;
            }

            // 失效的測速節點常常以 HTTP 200 回一頁 HTML 錯誤訊息；
            // 實測 cachefly.cachefly.net 就是這樣回 25 bytes 的 text/html。
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (Format.lower(contentType).startsWith("text/")) {
                logger.warn("節點 {} 回傳的是 {} 而非二進位資料，判定為失效", target.name(), contentType);
                response.body().close();
                rejected.set(true);
                return;
            }

            try (InputStream body = response.body()) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int count;
                while (counter.get() < goal && System.nanoTime() < deadline && (count = body.read(buffer)) != -1) {
                    counter.addAndGet(count);
                }
            }
        } catch (IOException e) {
            logger.warn("節點 {} 的下載串流失敗：{}", target.name(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 上傳測速。同樣以多條連線送出，且計時只涵蓋位元組真正流出去的期間——
     * 舊版從 {@code send()} 之前就開始計時，把握手與等待回應的時間都算進了傳輸時間，
     * 又不論實際送出多少都固定拿設定值當分子，兩者都會讓數字失真。
     */
    private Optional<Transfer> upload() {
        long goal = config.uploadBytes();
        AtomicLong counter = new AtomicLong();
        AtomicBoolean failed = new AtomicBoolean();
        long deadline = System.nanoTime() + config.transferBudget().toNanos();

        CompletableFuture<Void> all = CompletableFuture.allOf(IntStream.range(0, config.parallelStreams())
                .mapToObj(i -> CompletableFuture.runAsync(
                        () -> uploadStream(goal, counter, failed, deadline), executor()))
                .toArray(CompletableFuture[]::new));

        List<long[]> samples;
        try {
            samples = awaitAndSample(counter, all, deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }

        // 全部串流都失敗才算這次上傳失敗；部分成功仍是有效的量測。
        if (failed.get() && counter.get() == 0) {
            return Optional.empty();
        }
        return summarise(samples, config.warmup().toNanos());
    }

    private void uploadStream(long goal, AtomicLong counter, AtomicBoolean failed, long deadline) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.uploadUrl()))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(config.requestTimeout().plus(config.transferBudget()))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new RandomDataStream(goal, counter, deadline)))
                .build();

        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                logger.warn("上傳測試回應 HTTP {}", response.statusCode());
                failed.set(true);
            }
        } catch (IOException e) {
            logger.warn("上傳串流失敗：{}", e.toString());
            failed.set(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed.set(true);
        }
    }

    /**
     * 傳輸期間每 {@value #SAMPLE_INTERVAL_MILLIS} ms 記錄一次累計位元組，
     * 直到所有串流結束或超出時間預算；串流本身也會各自檢查 deadline 並收工。
     *
     * @return {@code {nanoTime, 累計位元組}} 的取樣序列，至少含頭尾兩筆
     */
    private static List<long[]> awaitAndSample(AtomicLong counter, CompletableFuture<?> streams, long deadline)
            throws InterruptedException {
        List<long[]> samples = new ArrayList<>();
        samples.add(new long[] {System.nanoTime(), 0L});

        while (!streams.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(SAMPLE_INTERVAL_MILLIS);
            samples.add(new long[] {System.nanoTime(), counter.get()});
        }
        try {
            streams.join();
        } catch (CompletionException e) {
            // 個別串流的失敗已在該串流內記錄過，這裡只需要停止等待。
        }

        samples.add(new long[] {System.nanoTime(), counter.get()});
        return samples;
    }

    /**
     * 由取樣序列估算穩態吞吐量。
     *
     * <p>先跳過還沒有任何位元組流動的取樣（連線建立、TLS 握手與 TTFB 不屬於傳輸時間），
     * 再從第一個有資料的時間點往後扣掉暖機期，只用剩下的窗口計算速率。暖機期上限取
     * 傳輸時長的三分之一，讓短測試也還留得下夠長的量測窗。
     */
    static Optional<Transfer> summarise(List<long[]> samples, long warmupNanos) {
        if (samples.size() < 2) {
            return Optional.empty();
        }
        long[] last = samples.getLast();
        long total = last[1];
        if (total <= 0) {
            return Optional.empty();
        }

        int origin = 0;
        while (origin + 1 < samples.size() && samples.get(origin + 1)[1] == 0) {
            origin++;
        }
        long[] from = samples.get(origin);
        long span = last[0] - from[0];
        if (span <= 0) {
            return Optional.empty();
        }

        long warmup = Math.min(warmupNanos, span / 3);
        long[] start = from;
        for (int i = origin; i < samples.size(); i++) {
            if (samples.get(i)[0] - from[0] > warmup) {
                break;
            }
            start = samples.get(i);
        }

        long windowNanos = last[0] - start[0];
        long windowBytes = total - start[1];
        if (windowNanos <= 0 || windowBytes <= 0) {
            // 傳輸短到暖機期內就結束了，只好退回整段平均。
            return toTransfer(total, total, span);
        }
        return toTransfer(total, windowBytes, windowNanos);
    }

    private static Optional<Transfer> toTransfer(long totalBytes, long windowBytes, long windowNanos) {
        if (windowBytes <= 0 || windowNanos <= 0) {
            return Optional.empty();
        }
        double seconds = windowNanos / 1_000_000_000.0;
        double mbps = (windowBytes * 8.0) / seconds / 1_000_000.0;
        return Optional.of(new Transfer(totalBytes, mbps));
    }

    private Optional<String> lookupPublicIp() {
        for (String service : config.publicIpServices()) {
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(URI.create(service))
                        .timeout(config.connectTimeout())
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();
            } catch (IllegalArgumentException e) {
                logger.warn("對外 IP 服務 URL 無效：{}", service);
                continue;
            }

            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    continue;
                }
                String candidate = response.body().strip().lines().findFirst().orElse("").strip();
                // Java 22 起的 InetAddress.ofLiteral：純字面值解析，不會觸發 DNS 查詢。
                InetAddress.ofLiteral(candidate);
                return Optional.of(candidate);
            } catch (IOException | IllegalArgumentException e) {
                // 換下一個服務。
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * 產生指定長度的偽隨機資料流；重複使用單一區塊以避免每次都呼叫 RNG。
     *
     * <p>同時擔任上傳端的計量點：HttpClient 是在通道可寫時才來取資料，因此這裡的
     * 讀取進度就等同於位元組送進 socket 的進度。超出時間預算即回傳 EOF 收工——
     * body 走 chunked 編碼，提早結束是合法的。
     */
    private static final class RandomDataStream extends InputStream {

        private static final int BLOCK_SIZE = 256 * 1024;

        private final byte[] block = new byte[BLOCK_SIZE];
        private final AtomicLong counter;
        private final long goal;
        private final long deadline;
        private int offset;

        RandomDataStream(long goal, AtomicLong counter, long deadline) {
            RandomGenerator.getDefault().nextBytes(block);
            this.goal = goal;
            this.counter = counter;
            this.deadline = deadline;
        }

        @Override
        public int read() {
            if (exhausted()) {
                return -1;
            }
            int value = block[offset] & 0xFF;
            offset = (offset + 1) % BLOCK_SIZE;
            counter.incrementAndGet();
            return value;
        }

        @Override
        public int read(byte[] destination, int off, int len) {
            if (exhausted()) {
                return -1;
            }
            // 計數器是多條串流共用的，exhausted() 之後別條可能已經把它推過目標，
            // 因此這裡要重新確認還有空間，否則 arraycopy 會收到負數長度。
            long room = goal - counter.get();
            if (room <= 0) {
                return -1;
            }
            int count = (int) Math.min(Math.min(len, room), BLOCK_SIZE - offset);
            System.arraycopy(block, offset, destination, off, count);
            offset = (offset + count) % BLOCK_SIZE;
            counter.addAndGet(count);
            return count;
        }

        /** 終止條件是所有串流的「共用」進度，因此各串流會在同一刻一起收手。 */
        private boolean exhausted() {
            return counter.get() >= goal || System.nanoTime() >= deadline;
        }

        @Override
        public int available() {
            return (int) Math.clamp(goal - counter.get(), 0, Integer.MAX_VALUE);
        }
    }
}
