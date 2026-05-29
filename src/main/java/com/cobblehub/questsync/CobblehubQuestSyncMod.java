package com.cobblehub.questsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point for the CobblehubQuestSync mod. Loads the config, opens the DB pool,
 * registers event listeners. v0 only ships the first-join teleport feature;
 * quest sync hooks land in a later version once FTB Quests events are wired in.
 */
public final class CobblehubQuestSyncMod implements ModInitializer {

    public static final String MOD_ID = "cobblehub_quest_sync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static CobblehubQuestSyncMod instance;
    /** Off-thread worker for DB calls. Sized small — DB ops are infrequent and serial-safe. */
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        AtomicInteger ix = new AtomicInteger();
        Thread t = new Thread(r, "cobblehub-quest-sync-worker-" + ix.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private Config config;
    private Database database;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("CobblehubQuestSync v0 starting...");

        this.config = Config.loadOrCreate();
        LOGGER.info("Loaded config. serverName='{}', mysql.enabled={}, firstJoin.enabled={}, firstJoin.flagKey='{}'",
                config.serverName, config.mysql.enabled, config.firstJoin.enabled, config.firstJoin.flagKey);

        if (config.mysql.enabled) {
            try {
                this.database = new Database(config.mysql);
                this.database.migrate();
                LOGGER.info("MySQL pool ready and schema migrated (prefix='{}').", config.mysql.tablePrefix);
            } catch (Exception e) {
                LOGGER.error("MySQL init failed — mod will run without DB-backed features. "
                        + "Edit config/cobblehub-quest-sync.json and restart.", e);
                if (database != null) {
                    database.close();
                    database = null;
                }
            }
        } else {
            LOGGER.warn("MySQL is disabled in config. Edit config/cobblehub-quest-sync.json, "
                    + "set mysql.enabled=true and provide credentials, then restart.");
        }

        new PlayerJoinHandler(this).register();

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping — shutting down CobblehubQuestSync...");
            if (database != null) {
                database.close();
            }
            WORKER.shutdown();
            try {
                if (!WORKER.awaitTermination(5, TimeUnit.SECONDS)) {
                    WORKER.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                WORKER.shutdownNow();
            }
        });

        LOGGER.info("CobblehubQuestSync v0 ready.");
    }

    public Config config() { return config; }
    public Database database() { return database; }

    public static CobblehubQuestSyncMod get() { return instance; }

    /** Run a task off the server thread. Use for any DB call from inside an event handler. */
    public static void async(Runnable task) {
        WORKER.submit(task);
    }
}
