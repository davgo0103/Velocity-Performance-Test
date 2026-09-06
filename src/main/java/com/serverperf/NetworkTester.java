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
import java.util.concurrent.ExecutorService;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;

/**
 * 網路測速。
 *
 * <p>全面改用 Java 11 起的 {@link HttpClient}：舊版的 {@code new URL(String)} 自 Java 20 起
 * 已棄用，而 {@code HttpURLConnection} 沒有 HTTP/2、沒有連線池、逾時語意混亂。
 * 所有阻塞式 I/O 都跑在虛擬執行緒上，不再佔用 {@code ForkJoinPool.commonPool}。
 */
final class NetworkTester {

    private static final String USER_AGENT = "VelocityPerformanceTest/2.0 (+https://github.com/davgo0103/ServerPerformanceTest)";
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final int MAX_UPLOAD_ATTEMPTS = 2;
    /** 低於此量的下載回應視為節點失效，而非真實的測速結果。 */
    private static final long MIN_VALID_DOWNLOAD_BYTES = 1024L * 1024L;

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
            Duration elapsed,
            Instant takenAt) {
    }

    Result run() throws InterruptedException {
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();

        // 對外 IP 查詢與節點延遲量測互不相依，平行進行。
        CompletableFuture<Optional<String>> publicIp =
                CompletableFuture.supplyAsync(this::lookupPublicIp, http.executor().orElse(Runnable::run));

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
                Duration.ofNanos(System.nanoTime() - startNanos),
                startedAt);
    }

    private record Probe(PerfConfig.Target target, Latency latency) {
    }

    private record Transfer(long bytes, double mbps) {
    }

    /** 平行量測所有節點的延遲，回傳可連通的節點（依平均延遲排序）。 */
    private List<Probe> probeTargets() {
        List<CompletableFuture<Optional<Probe>>> futures = config.downloadTargets().stream()
                .map(target -> CompletableFuture.supplyAsync(
                        () -> measureLatency(target).map(l -> new Probe(target, l)),
                        http.executor().orElse(Runnable::run)))
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
        for (int i = 0; i < config.pingSamples(); i++) {
            long start = System.nanoTime();
            try {
                HttpResponse<InputStream> response =
                        http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                response.body().close();
                if (response.statusCode() >= 400) {
                    return Optional.empty();
                }
                samples.add(elapsed);
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
     * 下載測速。計時從收到回應標頭之後開始（排除連線建立與 TTFB），
     * 並同時受位元組上限與時間預算約束——舊版只讀到 EOF，選到 100 MB 的節點就會整包抓完。
     */
    private Optional<Transfer> download(PerfConfig.Target target) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.resolveUrl(config.downloadBytes())))
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
                return Optional.empty();
            }

            // 失效的測速節點常常以 HTTP 200 回一頁 HTML 錯誤訊息；
            // 實測 cachefly.cachefly.net 就是這樣回 25 bytes 的 text/html。
            String contentType = response.headers().firstValue("content-type").orElse("");
            if (Format.lower(contentType).startsWith("text/")) {
                logger.warn("節點 {} 回傳的是 {} 而非二進位資料，判定為失效", target.name(), contentType);
                response.body().close();
                return Optional.empty();
            }

            long start = System.nanoTime();
            long deadline = start + config.transferBudget().toNanos();
            long total = 0;
            boolean stoppedByBudget = false;

            try (InputStream body = response.body()) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                while (total < config.downloadBytes() && (read = body.read(buffer)) != -1) {
                    total += read;
                    if (System.nanoTime() >= deadline) {
                        stoppedByBudget = true;
                        break;
                    }
                }
            }

            long elapsed = System.nanoTime() - start;

            // 若不是因為時間預算而中止，卻只拿到遠少於要求的資料量，
            // 代表節點沒有真的提供檔案——此時回報失敗，讓上層改試下一個節點，
            // 而不是拿 25 bytes 去算出一個毫無意義的 Mbps 數字。
            long minimumAcceptable = Math.min(config.downloadBytes(), MIN_VALID_DOWNLOAD_BYTES);
            if (!stoppedByBudget && total < minimumAcceptable) {
                logger.warn("節點 {} 只回傳 {} bytes（要求 {} bytes），判定為失效",
                        target.name(), total, config.downloadBytes());
                return Optional.empty();
            }

            return toTransfer(total, elapsed);
        } catch (IOException e) {
            logger.warn("節點 {} 下載測試失敗：{}", target.name(), e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** 上傳測速。以串流方式送出隨機資料，不需要先在堆積上配置整份 payload。 */
    private Optional<Transfer> upload() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.uploadUrl()))
                .timeout(config.requestTimeout().plus(config.transferBudget()))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new RandomDataStream(config.uploadBytes())))
                .build();

        long start = System.nanoTime();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                logger.warn("上傳測試回應 HTTP {}", response.statusCode());
                return Optional.empty();
            }
            return toTransfer(config.uploadBytes(), System.nanoTime() - start);
        } catch (IOException e) {
            logger.warn("上傳測試失敗：{}", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Optional<Transfer> toTransfer(long bytes, long elapsedNanos) {
        if (bytes <= 0 || elapsedNanos <= 0) {
            return Optional.empty();
        }
        double seconds = elapsedNanos / 1_000_000_000.0;
        double mbps = (bytes * 8.0) / seconds / 1_000_000.0;
        return Optional.of(new Transfer(bytes, mbps));
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

    /** 產生指定長度的偽隨機資料流；重複使用單一區塊以避免每次都呼叫 RNG。 */
    private static final class RandomDataStream extends InputStream {

        private static final int BLOCK_SIZE = 256 * 1024;

        private final byte[] block = new byte[BLOCK_SIZE];
        private long remaining;
        private int offset;

        RandomDataStream(long size) {
            RandomGenerator.getDefault().nextBytes(block);
            this.remaining = size;
        }

        @Override
        public int read() {
            if (remaining <= 0) {
                return -1;
            }
            remaining--;
            int value = block[offset] & 0xFF;
            offset = (offset + 1) % BLOCK_SIZE;
            return value;
        }

        @Override
        public int read(byte[] destination, int off, int len) {
            if (remaining <= 0) {
                return -1;
            }
            int count = (int) Math.min(Math.min(len, remaining), BLOCK_SIZE - offset);
            System.arraycopy(block, offset, destination, off, count);
            offset = (offset + count) % BLOCK_SIZE;
            remaining -= count;
            return count;
        }

        @Override
        public int available() {
            return (int) Math.min(remaining, Integer.MAX_VALUE);
        }
    }
}
