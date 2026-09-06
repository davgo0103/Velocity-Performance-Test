package com.serverperf;

import com.moandjiezana.toml.Toml;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;

/**
 * 插件設定。以不可變 record 表示，來源為資料目錄下的 {@code config.toml}；
 * 任何讀取或解析失敗都會退回 {@link #defaults()}，確保插件永遠能啟動。
 */
record PerfConfig(
        List<Target> downloadTargets,
        String uploadUrl,
        List<String> publicIpServices,
        long downloadBytes,
        long uploadBytes,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration transferBudget,
        int pingSamples,
        int parallelStreams,
        Duration warmup,
        Duration resultCacheTtl,
        Duration commandCooldown) {

    /** 一個下載測速節點。URL 中的 {@code {bytes}} 會被替換成實際要求的位元組數。 */
    record Target(String name, String url) {
        Target {
            name = name.strip();
            url = url.strip();
            if (name.isEmpty() || url.isEmpty()) {
                throw new IllegalArgumentException("測速節點的 name 與 url 皆不可為空");
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("測速節點 URL 必須是 http(s)：" + url);
            }
        }

        String resolveUrl(long bytes) {
            return url.replace("{bytes}", Long.toString(bytes));
        }
    }

    PerfConfig {
        downloadTargets = List.copyOf(downloadTargets);
        publicIpServices = List.copyOf(publicIpServices);
        if (downloadTargets.isEmpty()) {
            throw new IllegalArgumentException("至少需要一個下載測速節點");
        }
        if (downloadBytes <= 0 || uploadBytes < 0) {
            throw new IllegalArgumentException("測速位元組數必須為正數");
        }
        if (pingSamples < 1) {
            throw new IllegalArgumentException("ping-samples 至少為 1");
        }
        if (parallelStreams < 1) {
            throw new IllegalArgumentException("parallel-streams 至少為 1");
        }
        if (warmup.isNegative()) {
            throw new IllegalArgumentException("warmup-millis 不可為負數");
        }
    }

    static final String RESOURCE_NAME = "config.toml";

    static PerfConfig defaults() {
        try (InputStream in = PerfConfig.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException("內建的 " + RESOURCE_NAME + " 不存在於 JAR 中");
            }
            return parse(new Toml().read(in));
        } catch (IOException e) {
            throw new UncheckedIOException("無法讀取內建設定", e);
        }
    }

    /**
     * 從資料目錄載入設定；檔案不存在時會先寫出一份帶註解的預設檔。
     * 讀取或解析失敗一律降級為預設值並記錄警告，絕不讓插件因設定問題而無法載入。
     */
    static PerfConfig load(Path dataDirectory, Logger logger) {
        Path file = dataDirectory.resolve(RESOURCE_NAME);
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(dataDirectory);
                try (InputStream in = PerfConfig.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
                    if (in != null) {
                        Files.copy(in, file);
                        logger.info("已建立預設設定檔：{}", file);
                    }
                }
            }
            if (Files.exists(file)) {
                return parse(new Toml().read(file.toFile()));
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            logger.warn("讀取 {} 失敗，改用內建預設值：{}", file, e.toString());
        }
        return defaults();
    }

    static PerfConfig parse(Toml toml) {
        Toml net = toml.getTable("network");
        if (net == null) {
            throw new IllegalArgumentException("設定檔缺少 [network] 區段");
        }

        // toml4j 在鍵不存在時回傳 null，因此不能直接對回傳值開 stream。
        List<Toml> targetTables = net.getTables("download-targets");
        List<Target> targets = targetTables == null
                ? List.of()
                : targetTables.stream()
                        .map(table -> new Target(
                                requireString(table, "name", "download-targets"),
                                requireString(table, "url", "download-targets")))
                        .toList();

        List<String> configured = net.getList("public-ip-services", List.<String>of());
        List<String> ipServices = configured == null
                ? List.of()
                : configured.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(String::strip)
                        .filter(service -> !service.isEmpty())
                        .toList();

        return new PerfConfig(
                targets,
                net.getString("upload-url", "").strip(),
                ipServices,
                mib(net, "download-mib", 50),
                mib(net, "upload-mib", 25),
                seconds(net, "connect-timeout-seconds", 5),
                seconds(net, "request-timeout-seconds", 30),
                seconds(net, "transfer-budget-seconds", 20),
                Math.toIntExact(net.getLong("ping-samples", 5L)),
                // 上限 16：再多只會讓彼此爭搶頻寬，卻多開一堆連線。
                (int) Math.clamp(net.getLong("parallel-streams", 4L), 1L, 16L),
                millis(net, "warmup-millis", 1_000),
                seconds(net, "result-cache-seconds", 60),
                seconds(net, "command-cooldown-seconds", 10));
    }

    private static String requireString(Toml table, String key, String section) {
        String value = table.getString(key);
        if (value == null) {
            throw new IllegalArgumentException("[[%s]] 區段缺少必要欄位 %s".formatted(section, key));
        }
        return value;
    }

    private static long mib(Toml toml, String key, long fallbackMib) {
        return Math.max(0L, toml.getLong(key, fallbackMib)) * 1024L * 1024L;
    }

    private static Duration millis(Toml toml, String key, long fallback) {
        return Duration.ofMillis(Math.max(0L, toml.getLong(key, fallback)));
    }

    private static Duration seconds(Toml toml, String key, long fallback) {
        return Duration.ofSeconds(Math.max(1L, toml.getLong(key, fallback)));
    }

    boolean uploadEnabled() {
        return uploadBytes > 0
                && uploadUrl.toLowerCase(Locale.ROOT).startsWith("http");
    }
}
