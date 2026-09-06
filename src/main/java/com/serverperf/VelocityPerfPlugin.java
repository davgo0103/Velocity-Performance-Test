package com.serverperf;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import org.slf4j.Logger;

@Plugin(
        id = "velocityperf",
        name = "Velocity Performance Test",
        version = VelocityPerfPlugin.VERSION,
        description = "檢測 Proxy 主機規格、網路速度與後端伺服器狀態",
        url = "https://github.com/davgo0103/ServerPerformanceTest",
        authors = {"shi0103"})
public final class VelocityPerfPlugin {

    static final String VERSION = "2.0.0";

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PerfService service;

    @Inject
    public VelocityPerfPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        PerfConfig config = PerfConfig.load(dataDirectory, logger);
        this.service = new PerfService(server, logger, dataDirectory, config);

        var commandManager = server.getCommandManager();
        var command = new PerfCommand(service, logger, VERSION).build();
        CommandMeta meta = commandManager.metaBuilder(command)
                .aliases("vp", "serverperf")
                .plugin(this)
                .build();
        commandManager.register(meta, command);

        logger.info("Velocity Performance Test v{} 已載入（{} 個測速節點）",
                VERSION, config.downloadTargets().size());
    }

    /** 釋放虛擬執行緒池與 HttpClient；舊版沒有關閉路徑，重載插件會留下懸空的執行緒。 */
    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (service != null) {
            service.close();
            service = null;
        }
    }
}
