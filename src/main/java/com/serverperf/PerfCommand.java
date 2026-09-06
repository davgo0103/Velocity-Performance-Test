package com.serverperf;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

/**
 * {@code /vperf} 指令。
 *
 * <p>改用 Brigadier 註冊：子指令補全由指令樹自動提供，權限也能逐節點設定，
 * 不必再手寫 {@code suggest()}。所有輸出都合併成單一 Component 一次送出，
 * 而不是舊版那樣連續呼叫二十幾次 {@code sendMessage}。
 */
final class PerfCommand {

    static final String PERMISSION = "velocityperf.use";
    static final String ADMIN_PERMISSION = "velocityperf.admin";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PerfService service;
    private final Logger logger;
    private final String version;
    private final Map<String, Instant> lastUse = new ConcurrentHashMap<>();

    PerfCommand(PerfService service, Logger logger, String version) {
        this.service = service;
        this.logger = logger;
        this.version = version;
    }

    BrigadierCommand build() {
        LiteralArgumentBuilder<CommandSource> root = BrigadierCommand.literalArgumentBuilder("vperf")
                .requires(source -> source.hasPermission(PERMISSION))
                .executes(context -> {
                    context.getSource().sendMessage(help());
                    return Command.SINGLE_SUCCESS;
                })
                .then(subcommand("specs", this::showSpecs))
                .then(subcommand("network", this::showNetwork))
                .then(subcommand("servers", this::showServers))
                .then(subcommand("all", this::showAll))
                .then(BrigadierCommand.literalArgumentBuilder("reload")
                        .requires(source -> source.hasPermission(ADMIN_PERMISSION))
                        .executes(context -> {
                            reload(context.getSource());
                            return Command.SINGLE_SUCCESS;
                        }));

        return new BrigadierCommand(root);
    }

    private LiteralArgumentBuilder<CommandSource> subcommand(String name, Consumer<CommandSource> action) {
        return BrigadierCommand.literalArgumentBuilder(name)
                .executes(context -> {
                    CommandSource source = context.getSource();
                    if (onCooldown(source)) {
                        return Command.SINGLE_SUCCESS;
                    }
                    action.accept(source);
                    return Command.SINGLE_SUCCESS;
                });
    }

    // ---------------------------------------------------------------- 冷卻

    private boolean onCooldown(CommandSource source) {
        Duration cooldown = service.config().commandCooldown();
        if (cooldown.isZero() || !(source instanceof Player player)) {
            return false;
        }

        String key = player.getUniqueId().toString();
        Instant now = Instant.now();
        Instant previous = lastUse.get(key);
        if (previous != null && Duration.between(previous, now).compareTo(cooldown) < 0) {
            long remaining = cooldown.minus(Duration.between(previous, now)).toSeconds() + 1;
            source.sendMessage(MM.deserialize(
                    "<red>請稍候 <yellow>" + remaining + "</yellow> 秒後再試。"));
            return true;
        }
        lastUse.put(key, now);
        return false;
    }

    // ---------------------------------------------------------------- 子指令

    private void showSpecs(CommandSource source) {
        source.sendMessage(MM.deserialize("<gray>正在擷取主機規格…"));
        service.specs()
                .thenAccept(snapshot -> source.sendMessage(specsPanel(snapshot)))
                .exceptionally(error -> report(source, "主機規格擷取失敗", error));
    }

    private void showNetwork(CommandSource source) {
        if (service.hasFreshResult()) {
            source.sendMessage(MM.deserialize("<gray>使用 <yellow>快取<gray> 中的測速結果。"));
        } else if (service.testInProgress()) {
            source.sendMessage(MM.deserialize("<gray>已有測速進行中，將共用該次結果…"));
        } else {
            source.sendMessage(MM.deserialize("<gray>正在測速，這可能需要數十秒…"));
        }

        service.network()
                .thenAccept(result -> source.sendMessage(networkPanel(source, result)))
                .exceptionally(error -> report(source, "網路測速失敗", error));
    }

    private void showServers(CommandSource source) {
        service.servers()
                .thenAccept(statuses -> source.sendMessage(serversPanel(statuses)))
                .exceptionally(error -> report(source, "後端伺服器查詢失敗", error));
    }

    /**
     * 依序執行三項測試。舊版直接連續呼叫三個方法，但測速是非同步的，
     * 結果會插在其他區塊中間；這裡用 future 串接保證輸出順序。
     */
    private void showAll(CommandSource source) {
        source.sendMessage(MM.deserialize("<gray>正在執行完整測試…"));
        service.specs()
                .thenAccept(snapshot -> source.sendMessage(specsPanel(snapshot)))
                .thenCompose(ignored -> service.servers())
                .thenAccept(statuses -> source.sendMessage(serversPanel(statuses)))
                .thenCompose(ignored -> service.network())
                .thenAccept(result -> source.sendMessage(networkPanel(source, result)))
                .exceptionally(error -> report(source, "完整測試失敗", error));
    }

    private void reload(CommandSource source) {
        PerfConfig reloaded = service.reload();
        source.sendMessage(MM.deserialize(
                "<green>設定已重新載入：<white>" + reloaded.downloadTargets().size()
                        + "</white> 個測速節點，快取已清除。"));
    }

    private Void report(CommandSource source, String what, Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        source.sendMessage(MM.deserialize("<red>✖ " + escape(what) + "：<yellow>"
                + escape(String.valueOf(cause.getMessage()))));
        logger.error("{} ", what, cause);
        return null;
    }

    // ---------------------------------------------------------------- 版面

    private Component help() {
        return new Panel()
                .header("Velocity 效能測試工具", version)
                .blank()
                .raw("  <click:run_command:'/vperf specs'><hover:show_text:'<gray>點擊執行'>"
                        + "<yellow>/vperf specs</yellow></hover></click> <dark_gray>— <white>Proxy 主機規格")
                .raw("  <click:run_command:'/vperf network'><hover:show_text:'<gray>點擊執行'>"
                        + "<yellow>/vperf network</yellow></hover></click> <dark_gray>— <white>網路測速")
                .raw("  <click:run_command:'/vperf servers'><hover:show_text:'<gray>點擊執行'>"
                        + "<yellow>/vperf servers</yellow></hover></click> <dark_gray>— <white>後端伺服器狀態")
                .raw("  <click:run_command:'/vperf all'><hover:show_text:'<gray>點擊執行'>"
                        + "<yellow>/vperf all</yellow></hover></click> <dark_gray>— <white>完整報告")
                .raw("  <yellow>/vperf reload</yellow> <dark_gray>— <white>重新載入設定 "
                        + "<dark_gray>(需 " + ADMIN_PERMISSION + ")")
                .rule()
                .build();
    }

    private Component specsPanel(SystemProbe.Snapshot info) {
        Panel panel = new Panel().header("Proxy 主機規格", version);

        panel.section("處理器");
        panel.row("型號", info.cpuModel());
        panel.row("邏輯處理器", Format.count(info.logicalProcessors(), "執行緒"));
        info.physicalCores().ifPresent(cores -> panel.row("實體核心", Format.count(cores, "核心")));
        info.systemCpuLoad().ifPresent(load ->
                panel.gauge("系統負載", Format.percent(load), load));
        info.processCpuLoad().ifPresent(load ->
                panel.gauge("本行程負載", Format.percent(load), load));

        panel.section("記憶體");
        double heapRatio = Format.ratio(info.jvmUsedBytes(), info.jvmMaxBytes());
        panel.gauge("JVM 堆積",
                "%s / %s".formatted(Format.bytes(info.jvmUsedBytes()), Format.bytes(info.jvmMaxBytes())),
                heapRatio);
        panel.row("已提交", Format.bytes(info.jvmCommittedBytes()));
        if (info.systemTotalMemory().isPresent()) {
            long total = info.systemTotalMemory().getAsLong();
            long free = info.systemFreeMemory().orElse(0);
            panel.gauge("系統實體記憶體",
                    "%s / %s".formatted(Format.bytes(total - free), Format.bytes(total)),
                    Format.ratio(total - free, total));
        }

        panel.section("儲存空間");
        if (info.diskTotalBytes().isPresent()) {
            long total = info.diskTotalBytes().getAsLong();
            long usable = info.diskUsableBytes().orElse(0);
            panel.gauge("插件所在磁碟",
                    "%s / %s".formatted(Format.bytes(total - usable), Format.bytes(total)),
                    Format.ratio(total - usable, total));
            panel.row("可用空間", Format.bytes(usable));
        } else {
            panel.row("狀態", "無法查詢（容器或權限限制）");
        }

        panel.section("系統");
        panel.row("作業系統", "%s %s (%s)".formatted(info.osName(), info.osVersion(), info.osArch()));
        panel.row("Java", "%s — %s".formatted(info.javaVersion(), info.javaVendor()));
        panel.row("JVM 執行時間", Format.duration(info.jvmUptime()));

        panel.section("Velocity");
        panel.row("線上玩家", Format.count(info.onlinePlayers(), "人"));
        panel.row("後端伺服器", Format.count(info.backendServers(), "個"));

        return panel.rule().build();
    }

    private Component networkPanel(CommandSource source, NetworkTester.Result result) {
        Panel panel = new Panel().header("網路測速結果", version);

        panel.section("連線");
        panel.row("對外 IP", result.publicIp().orElse(Format.placeholder()));
        panel.row("測速節點", result.targetName().orElse("全部節點皆無法連線"));
        if (source instanceof Player player) {
            panel.row("你到 Proxy 的延遲", player.getPing() + " ms");
        }

        panel.section("頻寬");
        panel.row("下載", bandwidth(result.downloadMbps(), result.downloadedBytes()));
        if (service.config().uploadEnabled()) {
            panel.row("上傳", bandwidth(result.uploadMbps(), result.uploadedBytes()));
        } else {
            panel.row("上傳", "已於設定中停用");
        }
        panel.row("並行連線", Format.count(result.streams(), "條"));

        panel.section("延遲");
        result.latency().ifPresentOrElse(latency -> {
            panel.row("平均", Format.millis(latency.avgMs()));
            panel.row("最低", Format.millis(latency.minMs()));
            panel.row("抖動 (jitter)", Format.millis(latency.jitterMs()));
        }, () -> panel.row("狀態", Format.placeholder()));

        panel.section("本次測試");
        panel.row("耗時", Format.duration(result.elapsed()));
        panel.row("量測時間",
                Format.duration(Duration.between(result.takenAt(), Instant.now())) + "前");

        return panel.rule().build();
    }

    /** 速率後面附上實際傳輸量；速率本身已排除 TTFB 與慢啟動，兩者不會互相整除。 */
    private static String bandwidth(OptionalDouble mbps, long bytes) {
        return Format.mbps(mbps) + (bytes > 0 ? "  (%s)".formatted(Format.bytes(bytes)) : "");
    }

    private Component serversPanel(List<PerfService.ServerStatus> statuses) {
        Panel panel = new Panel().header("後端伺服器狀態", version);

        if (statuses.isEmpty()) {
            panel.blank().raw("  <red>目前沒有註冊任何後端伺服器。");
            return panel.rule().build();
        }

        long online = statuses.stream().filter(PerfService.ServerStatus::online).count();
        panel.blank().raw("  <gray>共 <white>%d</white> 個伺服器，<green>%d</green> 個在線。"
                .formatted(statuses.size(), online));

        for (PerfService.ServerStatus status : statuses) {
            panel.section(status.name());
            if (status.online()) {
                panel.row("狀態", "● 在線");
                panel.row("玩家", "%d / %d".formatted(status.onlinePlayers(), status.maxPlayers()));
                status.version().ifPresent(v -> panel.row("版本", v));
                status.pingMs().ifPresent(ms -> panel.row("回應時間", ms + " ms"));
            } else {
                panel.raw("<dark_gray>  ▪ <aqua>狀態 <red>● 離線");
            }
        }

        return panel.rule().build();
    }

    private static String escape(String value) {
        return MM.escapeTags(value);
    }

    /**
     * 逐行累積 MiniMessage 標記，最後一次反序列化成單一 Component。
     * 動態內容一律經過 {@link MiniMessage#escapeTags(String)}，避免伺服器名稱或
     * CPU 型號裡的角括號被解讀成標籤。
     */
    private static final class Panel {

        private static final String RULE =
                "<dark_gray><strikethrough>                                                            ";

        private final List<String> lines = new ArrayList<>();

        Panel header(String title, String version) {
            lines.add(RULE);
            lines.add("<gold><bold>  ⚡ " + escape(title) + "</bold> <dark_gray>v" + escape(version));
            lines.add(RULE);
            return this;
        }

        Panel section(String name) {
            lines.add("");
            lines.add("<yellow><bold>▎ " + escape(name));
            return this;
        }

        Panel row(String label, String value) {
            lines.add("<dark_gray>  ▪ <aqua>" + escape(label) + " <white>" + escape(value));
            return this;
        }

        Panel gauge(String label, String value, double ratio) {
            row(label, value);
            lines.add("<dark_gray>    " + Format.bar(ratio));
            return this;
        }

        Panel raw(String markup) {
            lines.add(markup);
            return this;
        }

        Panel blank() {
            lines.add("");
            return this;
        }

        Panel rule() {
            lines.add(RULE);
            return this;
        }

        Component build() {
            return MM.deserialize(String.join("\n", lines));
        }
    }
}
