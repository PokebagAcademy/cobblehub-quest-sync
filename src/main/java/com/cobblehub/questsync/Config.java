package com.cobblehub.questsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * JSON-backed configuration for CobblehubQuestSync.
 * <p>
 * File location: {@code config/cobblehub-quest-sync.json}.
 * If absent at startup, the file is created with default values and the server
 * operator is expected to edit it before MySQL features can be enabled.
 */
public final class Config {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** Logical name of this server instance (e.g. "main", "monde"). Used as last_server marker and to scope flags. */
    public String serverName = "main";

    public MysqlConfig mysql = new MysqlConfig();
    public FirstJoinConfig firstJoin = new FirstJoinConfig();

    public static final class MysqlConfig {
        /** Set to false to start the mod without attempting any DB connection (useful for first-boot config generation). */
        public boolean enabled = false;
        public String host = "sql3.minestrator.com";
        public int port = 3306;
        public String database = "CHANGE_ME";
        public String username = "CHANGE_ME";
        public String password = "CHANGE_ME";
        public String tablePrefix = "cobblehub_";
        /** Connections in the Hikari pool. Keep low: Mystrator imposes per-database limits. */
        public int poolMaxSize = 4;
        public int poolMinIdle = 1;
        public int connectionTimeoutMs = 5000;
    }

    public static final class FirstJoinConfig {
        /** Master switch for the first-join teleport feature. */
        public boolean enabled = true;
        /**
         * Flag key written to the DB once the player has been teleported.
         * Should be unique per server (e.g. "first_join_main" vs "first_join_monde").
         */
        public String flagKey = "first_join_main";
        public TutorialLocation tutorialLocation = new TutorialLocation();
    }

    public static final class TutorialLocation {
        public String dimension = "minecraft:overworld";
        public double x = 0.5;
        public double y = 100.0;
        public double z = 0.5;
        public float yaw = 0.0f;
        public float pitch = 0.0f;
    }

    // ---------------- IO ----------------

    public static Config loadOrCreate() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path file = dir.resolve("cobblehub-quest-sync.json");

        if (!Files.exists(file)) {
            Config def = new Config();
            try {
                Files.createDirectories(dir);
                Files.writeString(file, GSON.toJson(def),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                CobblehubQuestSyncMod.LOGGER.info("Created default config at {}", file);
            } catch (IOException e) {
                CobblehubQuestSyncMod.LOGGER.error("Failed to write default config at {}", file, e);
            }
            return def;
        }

        try {
            String content = Files.readString(file);
            Config loaded = GSON.fromJson(content, Config.class);
            if (loaded == null) {
                CobblehubQuestSyncMod.LOGGER.warn("Config file was empty, using defaults.");
                return new Config();
            }
            // Backfill nullable subsections to survive partial configs.
            if (loaded.mysql == null) loaded.mysql = new MysqlConfig();
            if (loaded.firstJoin == null) loaded.firstJoin = new FirstJoinConfig();
            if (loaded.firstJoin.tutorialLocation == null) loaded.firstJoin.tutorialLocation = new TutorialLocation();
            return loaded;
        } catch (IOException | JsonSyntaxException e) {
            CobblehubQuestSyncMod.LOGGER.error("Failed to parse {}, falling back to defaults.", file, e);
            return new Config();
        }
    }
}
