package com.serverperf;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

/**
 * 所有量測工作的入口。負責執行緒模型、單次併發限制與結果快取。
 *
 * <p>舊版把 50 MB 的阻塞下載丟進 {@code ForkJoinPool.commonPool}，會癱瘓整個 JVM 共用池；
 * 這裡改用 Java 21 的虛擬執行緒，並在插件關閉時一併釋放。
 */
final class PerfService implements AutoCloseable {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final ExecutorService executor;

    private volatile PerfConfig config;
    private volatile NetworkTester tester;

    /** 進行中的測速；同時間只允許一個，後到的呼叫者會共用同一份結果。 */
    private final AtomicReference<CompletableFuture<NetworkTester.Result>> inFlight = new AtomicReference<>();
    private volatile NetworkTester.Result cached;

    PerfService(ProxyServer server, Logger logger, Path dataDirectory, PerfConfig config) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        applyConfig(config);
    }

    private void applyConfig(PerfConfig newConfig) {
        this.config = newConfig;
        this.tester = new NetworkTester(newConfig, logger, executor);
        this.cached = null;
    }

    PerfConfig config() {
        return config;
    }

    /** 重新載入設定檔並丟棄快取結果。 */
    PerfConfig reload() {
        applyConfig(PerfConfig.load(dataDirectory, logger));
        return config;
    }

    CompletableFuture<SystemProbe.Snapshot> specs() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return SystemProbe.capture(server, dataDirectory, Duration.ofMillis(500));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("規格量測被中斷");
            }
        }, executor);
    }

    /** 後端伺服器狀態。全部平行 ping，等到最後一個完成才回傳，結果依名稱排序。 */
    CompletableFuture<List<ServerStatus>> servers() {
        List<CompletableFuture<ServerStatus>> pings = server.getAllServers().stream()
                .map(this::pingServer)
                .toList();

        return CompletableFuture.allOf(pings.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> pings.stream()
                        .map(CompletableFuture::join)
                        .sorted(Comparator.comparing(ServerStatus::name, String.CASE_INSENSITIVE_ORDER))
                        .toList());
    }

    private CompletableFuture<ServerStatus> pingServer(RegisteredServer registered) {
        String name = registered.getServerInfo().getName();
        int connected = registered.getPlayersConnected().size();
        long start = System.nanoTime();

        return registered.ping()
                .orTimeout(5, TimeUnit.SECONDS)
                .handle((ping, error) -> {
                    if (error != null || ping == null) {
                        return ServerStatus.offline(name);
                    }
                    long millis = (System.nanoTime() - start) / 1_000_000L;
                    return new ServerStatus(
                            name,
                            true,
                            ping.getPlayers().map(p -> p.getOnline()).orElse(connected),
                            ping.getPlayers().map(p -> p.getMax()).orElse(0),
                            Optional.ofNullable(ping.getVersion()).map(v -> v.getName()),
                            OptionalLong.of(millis));
                });
    }

    /**
     * 取得測速結果。快取仍新鮮時直接回傳；已有測試在跑時共用該次結果；
     * 否則啟動一次新的測試。這一層同時扮演流量保護的角色。
     */
    CompletableFuture<NetworkTester.Result> network() {
        PerfConfig current = config;
        NetworkTester.Result snapshot = cached;
        if (snapshot != null
                && Duration.between(snapshot.takenAt(), Instant.now()).compareTo(current.resultCacheTtl()) < 0) {
            return CompletableFuture.completedFuture(snapshot);
        }

        CompletableFuture<NetworkTester.Result> existing = inFlight.get();
        if (existing != null && !existing.isDone()) {
            return existing;
        }

        CompletableFuture<NetworkTester.Result> started = new CompletableFuture<>();
        if (!inFlight.compareAndSet(existing, started)) {
            CompletableFuture<NetworkTester.Result> winner = inFlight.get();
            return winner != null ? winner : CompletableFuture.completedFuture(snapshot);
        }

        NetworkTester active = tester;
        executor.execute(() -> {
            try {
                NetworkTester.Result result = active.run();
                cached = result;
                started.complete(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                started.completeExceptionally(e);
            } catch (RuntimeException e) {
                logger.error("測速執行時發生未預期的錯誤", e);
                started.completeExceptionally(e);
            }
        });
        return started;
    }

    /** 是否有測速正在進行（用於提示使用者將共用同一份結果）。 */
    boolean testInProgress() {
        CompletableFuture<NetworkTester.Result> current = inFlight.get();
        return current != null && !current.isDone();
    }

    /** 快取結果是否仍新鮮（用於提示這是快取而非即時量測）。 */
    boolean hasFreshResult() {
        NetworkTester.Result snapshot = cached;
        return snapshot != null
                && Duration.between(snapshot.takenAt(), Instant.now()).compareTo(config.resultCacheTtl()) < 0;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    record ServerStatus(
            String name,
            boolean online,
            int onlinePlayers,
            int maxPlayers,
            Optional<String> version,
            OptionalLong pingMs) {

        static ServerStatus offline(String name) {
            return new ServerStatus(name, false, 0, 0, Optional.empty(), OptionalLong.empty());
        }
    }
}
