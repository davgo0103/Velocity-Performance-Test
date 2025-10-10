package com.serverperf;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

@Plugin(
    id = "velocityperf",
    name = "Velocity Performance Test",
    version = "1.0.0",
    authors = {"shi0103"}
)
public class VelocityPerfPlugin {

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public VelocityPerfPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        CommandManager commandManager = server.getCommandManager();
        commandManager.register("vperf", new PerfCommand(server, this));
        logger.info("Velocity Performance Test plugin loaded!");
    }

    public Logger getLogger() {
        return logger;
    }
}
