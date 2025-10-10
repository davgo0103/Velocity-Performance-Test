package com.serverperf;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PerfCommand implements SimpleCommand {

    private final ProxyServer server;
    private final VelocityPerfPlugin plugin;

    public PerfCommand(ProxyServer server, VelocityPerfPlugin plugin) {
        this.server = server;
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "specs":
                showSpecs(source);
                break;
            case "network":
                testNetwork(source);
                break;
            case "servers":
                showServers(source);
                break;
            case "all":
                showAll(source);
                break;
            default:
                sendHelp(source);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("specs", "network", "servers", "all");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("velocityperf.use");
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
        source.sendMessage(Component.text("    Velocity 性能測試工具", NamedTextColor.GOLD));
        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
        source.sendMessage(Component.text("/vperf specs    - 顯示 Proxy 主機規格", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/vperf network  - 測試網路狀態", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/vperf servers  - 顯示後端伺服器狀態", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("/vperf all      - 執行完整測試", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
    }

    private void showSpecs(CommandSource source) {
        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
        source.sendMessage(Component.text("    Proxy 主機規格", NamedTextColor.GOLD));
        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));

        // CPU 資訊
        SystemInfo systemInfo = getSystemInfo();
        source.sendMessage(Component.text(""));
        source.sendMessage(Component.text("【 處理器 】", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("CPU 型號: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.cpuModel, NamedTextColor.WHITE)));
        source.sendMessage(Component.text("處理器核心: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.cpuCores + " 核心", NamedTextColor.GREEN)));
        source.sendMessage(Component.text("邏輯處理器: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.cpuThreads + " 執行緒", NamedTextColor.GREEN)));

        // 記憶體資訊
        source.sendMessage(Component.text(""));
        source.sendMessage(Component.text("【 記憶體 】", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("JVM 記憶體: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.usedMemMB + " MB / " + systemInfo.maxMemMB + " MB (" + systemInfo.memUsagePercent + "%)", NamedTextColor.GREEN)));
        source.sendMessage(Component.text("可用記憶體: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.freeMemMB + " MB", NamedTextColor.GREEN)));
        if (systemInfo.totalSystemMemGB > 0) {
            source.sendMessage(Component.text("系統總記憶體: ", NamedTextColor.AQUA)
                .append(Component.text(systemInfo.totalSystemMemGB + " GB", NamedTextColor.WHITE)));
        }

        // 儲存空間
        source.sendMessage(Component.text(""));
        source.sendMessage(Component.text("【 儲存空間 】", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("已使用: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.usedDiskGB + " GB / " + systemInfo.totalDiskGB + " GB (" + systemInfo.diskUsagePercent + "%)", NamedTextColor.GREEN)));
        source.sendMessage(Component.text("可用空間: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.freeDiskGB + " GB", NamedTextColor.GREEN)));

        // 系統資訊
        source.sendMessage(Component.text(""));
        source.sendMessage(Component.text("【 系統 】", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("作業系統: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.osName + " " + systemInfo.osVersion, NamedTextColor.WHITE)));
        source.sendMessage(Component.text("系統架構: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.osArch, NamedTextColor.WHITE)));
        source.sendMessage(Component.text("Java 版本: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.javaVersion + " (" + systemInfo.javaVendor + ")", NamedTextColor.WHITE)));

        // Velocity 資訊
        source.sendMessage(Component.text(""));
        source.sendMessage(Component.text("【 Velocity 】", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("線上玩家: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.onlinePlayers + " 人", NamedTextColor.GREEN)));
        source.sendMessage(Component.text("後端伺服器: ", NamedTextColor.AQUA)
            .append(Component.text(systemInfo.backendServers + " 個", NamedTextColor.GREEN)));

        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
    }

    private SystemInfo getSystemInfo() {
        SystemInfo info = new SystemInfo();
        Runtime runtime = Runtime.getRuntime();

        // CPU 資訊
        try {
            info.cpuModel = getCpuModel();
        } catch (Exception e) {
            info.cpuModel = "Unknown";
        }
        info.cpuCores = runtime.availableProcessors();
        info.cpuThreads = runtime.availableProcessors();

        // 記憶體資訊（JVM）
        info.maxMemMB = runtime.maxMemory() / (1024 * 1024);
        info.totalMemMB = runtime.totalMemory() / (1024 * 1024);
        info.freeMemMB = runtime.freeMemory() / (1024 * 1024);
        info.usedMemMB = info.totalMemMB - info.freeMemMB;
        info.memUsagePercent = (int) ((info.usedMemMB * 100) / info.maxMemMB);

        // 系統總記憶體
        try {
            info.totalSystemMemGB = getSystemMemoryGB();
        } catch (Exception e) {
            info.totalSystemMemGB = 0;
        }

        // 儲存空間
        try {
            java.io.File root = new java.io.File("/");
            info.totalDiskGB = root.getTotalSpace() / (1024 * 1024 * 1024);
            info.freeDiskGB = root.getUsableSpace() / (1024 * 1024 * 1024);
            info.usedDiskGB = info.totalDiskGB - info.freeDiskGB;
            info.diskUsagePercent = (int) ((info.usedDiskGB * 100) / info.totalDiskGB);
        } catch (Exception e) {
            info.totalDiskGB = 0;
            info.freeDiskGB = 0;
            info.usedDiskGB = 0;
            info.diskUsagePercent = 0;
        }

        // 系統資訊
        info.osName = System.getProperty("os.name");
        info.osVersion = System.getProperty("os.version");
        info.osArch = System.getProperty("os.arch");
        info.javaVersion = System.getProperty("java.version");
        info.javaVendor = System.getProperty("java.vendor");

        // Velocity 資訊
        info.onlinePlayers = server.getAllPlayers().size();
        info.backendServers = server.getAllServers().size();

        return info;
    }

    private String getCpuModel() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("linux")) {
                Process process = Runtime.getRuntime().exec("cat /proc/cpuinfo");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("model name")) {
                        return line.split(":")[1].trim();
                    }
                }
            } else if (os.contains("windows")) {
                Process process = Runtime.getRuntime().exec("wmic cpu get name");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
                reader.readLine(); // 跳過標題
                return reader.readLine().trim();
            }
        } catch (Exception e) {
            // 忽略錯誤
        }
        return "Unknown CPU";
    }

    private long getSystemMemoryGB() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("linux")) {
                Process process = Runtime.getRuntime().exec("cat /proc/meminfo");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                if (line != null && line.startsWith("MemTotal:")) {
                    String[] parts = line.split("\\s+");
                    long memKB = Long.parseLong(parts[1]);
                    return memKB / (1024 * 1024); // 轉換為 GB
                }
            }
        } catch (Exception e) {
            // 忽略錯誤
        }
        return 0;
    }

    private static class SystemInfo {
        String cpuModel;
        int cpuCores;
        int cpuThreads;
        long maxMemMB;
        long totalMemMB;
        long freeMemMB;
        long usedMemMB;
        int memUsagePercent;
        long totalSystemMemGB;
        long totalDiskGB;
        long freeDiskGB;
        long usedDiskGB;
        int diskUsagePercent;
        String osName;
        String osVersion;
        String osArch;
        String javaVersion;
        String javaVendor;
        int onlinePlayers;
        int backendServers;
    }

    private void testNetwork(CommandSource source) {
        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
        source.sendMessage(Component.text("    網路狀態測試", NamedTextColor.GOLD));
        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));

        // 如果是玩家，顯示其延遲
        if (source instanceof Player player) {
            source.sendMessage(Component.text("你的延遲: ", NamedTextColor.AQUA)
                .append(Component.text(player.getPing() + " ms", NamedTextColor.GREEN)));
            source.sendMessage(Component.text(""));
        }

        source.sendMessage(Component.text("⏳ 正在選擇最佳測試伺服器...", NamedTextColor.YELLOW));

        // 非同步測試網速
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                NetworkTester tester = new NetworkTester();
                NetworkResult result = tester.testSpeed();
                long duration = (System.currentTimeMillis() - startTime) / 1000;

                source.sendMessage(Component.text("✓ 測試完成！(耗時: " + duration + " 秒)", NamedTextColor.GREEN));
                source.sendMessage(Component.text(""));
                source.sendMessage(Component.text("📥 下載速度: ", NamedTextColor.AQUA)
                    .append(Component.text(result.downloadSpeed + " Mbps", NamedTextColor.GREEN)));
                if ("N/A".equals(result.uploadSpeed)) {
                    source.sendMessage(Component.text("📤 上傳速度: ", NamedTextColor.AQUA)
                        .append(Component.text(result.uploadSpeed, NamedTextColor.YELLOW)));
                } else {
                    source.sendMessage(Component.text("📤 上傳速度: ", NamedTextColor.AQUA)
                        .append(Component.text(result.uploadSpeed + " Mbps", NamedTextColor.GREEN)));
                }
                source.sendMessage(Component.text("📶 延遲 (Ping): ", NamedTextColor.AQUA)
                    .append(Component.text(result.latency + " ms", NamedTextColor.GREEN)));

                source.sendMessage(Component.text(""));
                source.sendMessage(Component.text("🌐 測試伺服器: ", NamedTextColor.AQUA)
                    .append(Component.text(result.testFile, NamedTextColor.GRAY)));
                source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
            } catch (Exception e) {
                source.sendMessage(Component.text(""));
                source.sendMessage(Component.text("✗ 網速測試失敗", NamedTextColor.RED));
                source.sendMessage(Component.text("原因: " + e.getMessage(), NamedTextColor.YELLOW));
                source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
                plugin.getLogger().error("Network test failed", e);
            }
        });
    }

    private void showServers(CommandSource source) {
        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
        source.sendMessage(Component.text("    後端伺服器狀態", NamedTextColor.GOLD));
        source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));

        if (server.getAllServers().isEmpty()) {
            source.sendMessage(Component.text("沒有註冊的後端伺服器", NamedTextColor.RED));
            source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
            return;
        }

        server.getAllServers().forEach(registeredServer -> {
            String serverName = registeredServer.getServerInfo().getName();

            registeredServer.ping().thenAccept(serverPing -> {
                int onlinePlayers = serverPing.getPlayers()
                    .map(players -> players.getOnline())
                    .orElse(0);

                int maxPlayers = serverPing.getPlayers()
                    .map(players -> players.getMax())
                    .orElse(0);

                String version = serverPing.getVersion().getName();

                source.sendMessage(Component.text(""));
                source.sendMessage(Component.text("伺服器: ", NamedTextColor.AQUA)
                    .append(Component.text(serverName, NamedTextColor.GOLD)));
                source.sendMessage(Component.text("  狀態: ", NamedTextColor.GRAY)
                    .append(Component.text("● 在線", NamedTextColor.GREEN)));
                source.sendMessage(Component.text("  玩家: ", NamedTextColor.GRAY)
                    .append(Component.text(onlinePlayers + "/" + maxPlayers, NamedTextColor.WHITE)));
                source.sendMessage(Component.text("  版本: ", NamedTextColor.GRAY)
                    .append(Component.text(version, NamedTextColor.WHITE)));
            }).exceptionally(throwable -> {
                source.sendMessage(Component.text(""));
                source.sendMessage(Component.text("伺服器: ", NamedTextColor.AQUA)
                    .append(Component.text(serverName, NamedTextColor.GOLD)));
                source.sendMessage(Component.text("  狀態: ", NamedTextColor.GRAY)
                    .append(Component.text("● 離線", NamedTextColor.RED)));
                return null;
            });
        });

        // 延遲顯示結束線，等待異步操作完成
        CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
            source.sendMessage(Component.text("========================================", NamedTextColor.GOLD));
        });
    }

    private void showAll(CommandSource source) {
        showSpecs(source);
        source.sendMessage(Component.text(""));
        testNetwork(source);
        source.sendMessage(Component.text(""));
        showServers(source);
    }
}
