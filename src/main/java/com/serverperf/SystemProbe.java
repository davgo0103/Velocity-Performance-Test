package com.serverperf;

import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 收集主機與 JVM 規格。
 *
 * <p>取代舊版的 {@code Runtime.exec("wmic ...")} / {@code exec("cat /proc/cpuinfo")}：
 * {@code Runtime.exec(String)} 自 Java 18 起已棄用，而 {@code wmic} 在
 * Windows 11 24H2 與 Server 2025 起已從系統移除。改用 NIO 直接讀檔、環境變數，
 * 以及 {@code com.sun.management.OperatingSystemMXBean} 這個跨平台的標準介面。
 */
final class SystemProbe {

    private static final Path PROC_CPUINFO = Path.of("/proc/cpuinfo");

    private SystemProbe() {
    }

    /** 一次系統快照。可能取不到的欄位一律以 Optional 表示，而非用 0 或 "Unknown" 混淆。 */
    record Snapshot(
            String cpuModel,
            int logicalProcessors,
            OptionalInt physicalCores,
            OptionalDouble systemCpuLoad,
            OptionalDouble processCpuLoad,
            long jvmUsedBytes,
            long jvmCommittedBytes,
            long jvmMaxBytes,
            OptionalLong systemTotalMemory,
            OptionalLong systemFreeMemory,
            OptionalLong diskTotalBytes,
            OptionalLong diskUsableBytes,
            String osName,
            String osVersion,
            String osArch,
            String javaVersion,
            String javaVendor,
            Duration jvmUptime,
            int onlinePlayers,
            int backendServers) {
    }

    /**
     * 擷取快照。CPU 使用率需要兩次取樣才有意義，因此本方法會阻塞約
     * {@code sampleWindow}；請務必在非同步執行緒（虛擬執行緒）中呼叫。
     */
    static Snapshot capture(ProxyServer server, Path diskAnchor, Duration sampleWindow)
            throws InterruptedException {
        var runtime = Runtime.getRuntime();
        var osBean = ManagementFactory.getOperatingSystemMXBean();

        // 堆積數據取自 Runtime 而非 MemoryMXBean：在 G1 之下
        // getHeapMemoryUsage().getUsed() 只統計已封存的 region，
        // JVM 剛啟動時會回報 0，實測於 JDK 25 仍是如此。
        long heapCommitted = runtime.totalMemory();
        long heapUsed = heapCommitted - runtime.freeMemory();
        long heapMax = runtime.maxMemory();

        OptionalDouble systemLoad = OptionalDouble.empty();
        OptionalDouble processLoad = OptionalDouble.empty();
        OptionalLong totalMemory = OptionalLong.empty();
        OptionalLong freeMemory = OptionalLong.empty();

        if (osBean instanceof com.sun.management.OperatingSystemMXBean extended) {
            // 第一次呼叫只是建立基準點，回傳值通常為負數而無意義。
            extended.getCpuLoad();
            extended.getProcessCpuLoad();
            TimeUnit.MILLISECONDS.sleep(Math.max(1L, sampleWindow.toMillis()));

            systemLoad = ratio(extended.getCpuLoad());
            processLoad = ratio(extended.getProcessCpuLoad());
            totalMemory = positive(extended.getTotalMemorySize());
            // Linux 的 MemFree 不含可回收的 page cache，直接拿來算使用率會嚴重高估；
            // 有 MemAvailable 就優先採用。
            OptionalLong available = availableMemory();
            freeMemory = available.isPresent() ? available : positive(extended.getFreeMemorySize());
        }

        OptionalLong diskTotal = OptionalLong.empty();
        OptionalLong diskUsable = OptionalLong.empty();
        try {
            // 舊版用 new File("/")：在 Windows 上語意錯誤，也不見得是插件所在的磁碟。
            var store = Files.getFileStore(diskAnchor.toAbsolutePath());
            diskTotal = positive(store.getTotalSpace());
            diskUsable = positive(store.getUsableSpace());
        } catch (IOException | RuntimeException ignored) {
            // 某些容器環境無法查詢 FileStore，留空即可。
        }

        return new Snapshot(
                cpuModel(),
                runtime.availableProcessors(),
                physicalCores(),
                systemLoad,
                processLoad,
                heapUsed,
                heapCommitted,
                heapMax,
                totalMemory,
                freeMemory,
                diskTotal,
                diskUsable,
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"),
                Runtime.version().toString(),
                System.getProperty("java.vendor", "unknown"),
                Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()),
                server.getAllPlayers().size(),
                server.getAllServers().size());
    }

    private static String cpuModel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Optional<String> model;
        if (os.contains("linux")) {
            model = cpuInfoValue("model name");
        } else if (os.contains("win")) {
            model = Optional.ofNullable(System.getenv("PROCESSOR_IDENTIFIER"));
        } else if (os.contains("mac") || os.contains("darwin")) {
            model = sysctl("machdep.cpu.brand_string");
        } else {
            model = Optional.empty();
        }

        return model.map(String::strip)
                .filter(value -> !value.isEmpty())
                .orElse("未知 (" + System.getProperty("os.arch", "?") + ")");
    }

    /** 實體核心數：計算 /proc/cpuinfo 中不重複的 (physical id, core id) 組合。 */
    private static OptionalInt physicalCores() {
        if (Files.notExists(PROC_CPUINFO)) {
            return OptionalInt.empty();
        }
        try (Stream<String> lines = Files.lines(PROC_CPUINFO, StandardCharsets.UTF_8)) {
            Set<String> cores = new HashSet<>();
            String socket = "0";
            for (String line : (Iterable<String>) lines::iterator) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = line.substring(0, colon).strip();
                String value = line.substring(colon + 1).strip();
                if ("physical id".equals(key)) {
                    socket = value;
                } else if ("core id".equals(key)) {
                    cores.add(socket + "/" + value);
                }
            }
            return cores.isEmpty() ? OptionalInt.empty() : OptionalInt.of(cores.size());
        } catch (IOException | UncheckedIOException e) {
            return OptionalInt.empty();
        }
    }

    /** Linux 的 MemAvailable（kB）：核心估算的「真正可用」記憶體，含可回收的 cache。 */
    private static OptionalLong availableMemory() {
        Path meminfo = Path.of("/proc/meminfo");
        if (Files.notExists(meminfo)) {
            return OptionalLong.empty();
        }
        try (Stream<String> lines = Files.lines(meminfo, StandardCharsets.UTF_8)) {
            return lines.filter(line -> line.startsWith("MemAvailable:"))
                    .mapToLong(line -> {
                        String[] parts = line.split("\\s+");
                        return parts.length >= 2 ? Long.parseLong(parts[1]) * 1024L : -1L;
                    })
                    .filter(value -> value > 0)
                    .findFirst();
        } catch (IOException | UncheckedIOException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static Optional<String> cpuInfoValue(String key) {
        if (Files.notExists(PROC_CPUINFO)) {
            return Optional.empty();
        }
        try (Stream<String> lines = Files.lines(PROC_CPUINFO, StandardCharsets.UTF_8)) {
            return lines.filter(line -> line.startsWith(key))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip())
                    .filter(value -> !value.isEmpty())
                    .findFirst();
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    /** macOS 沒有 /proc，只能呼叫 sysctl；用 ProcessBuilder 取代已棄用的 Runtime.exec(String)。 */
    private static Optional<String> sysctl(String key) {
        Process process = null;
        try {
            process = new ProcessBuilder("/usr/sbin/sysctl", "-n", key).start();
            String output;
            try (var in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            return output.isEmpty() ? Optional.empty() : Optional.of(output);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static OptionalDouble ratio(double value) {
        return !Double.isNaN(value) && value >= 0 && value <= 1
                ? OptionalDouble.of(value)
                : OptionalDouble.empty();
    }

    private static OptionalLong positive(long value) {
        return value > 0 ? OptionalLong.of(value) : OptionalLong.empty();
    }
}
