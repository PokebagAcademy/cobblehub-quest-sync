package com.cobblehub.questsync;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * MySQL access layer. Owns a HikariCP pool, runs schema migrations at startup,
 * and exposes typed read/write methods used by the rest of the mod.
 * <p>
 * All blocking I/O lives here. Callers running on the server thread must offload
 * (see {@link CobblehubQuestSyncMod#async(Runnable)}).
 */
public final class Database implements AutoCloseable {

    private final Config.MysqlConfig cfg;
    private final HikariDataSource pool;

    public Database(Config.MysqlConfig cfg) {
        this.cfg = cfg;

        HikariConfig hk = new HikariConfig();
        hk.setJdbcUrl("jdbc:mariadb://" + cfg.host + ":" + cfg.port + "/" + cfg.database
                + "?useUnicode=true&characterEncoding=utf8&autoReconnect=true");
        hk.setUsername(cfg.username);
        hk.setPassword(cfg.password);
        hk.setMaximumPoolSize(cfg.poolMaxSize);
        hk.setMinimumIdle(cfg.poolMinIdle);
        hk.setConnectionTimeout(cfg.connectionTimeoutMs);
        hk.setPoolName("cobblehub-quest-sync");
        // SPI driver discovery does not survive shadow relocation, so we pin the driver
        // class name. Shadow rewrites string literals matching relocated package prefixes.
        hk.setDriverClassName("org.mariadb.jdbc.Driver");

        this.pool = new HikariDataSource(hk);
    }

    /** Creates all required tables if they do not already exist. Safe to run repeatedly. */
    public void migrate() throws SQLException {
        String flagsTable = cfg.tablePrefix + "player_flags";
        String questTable = cfg.tablePrefix + "quest_progress";

        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                      player_uuid CHAR(36)    NOT NULL,
                      flag_key    VARCHAR(64) NOT NULL,
                      flag_value  TINYINT(1)  NOT NULL DEFAULT 1,
                      set_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (player_uuid, flag_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(flagsTable));

            // Quest progress table is created now so we don't need a v2 migration later.
            // It will be exercised in chantier 4 of the project plan.
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                      player_uuid  CHAR(36)    NOT NULL,
                      task_id      VARCHAR(96) NOT NULL,
                      progress     BIGINT      NOT NULL DEFAULT 0,
                      completed    TINYINT(1)  NOT NULL DEFAULT 0,
                      last_updated TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      last_server  VARCHAR(32) NULL,
                      PRIMARY KEY (player_uuid, task_id),
                      INDEX idx_player_completed (player_uuid, completed)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.formatted(questTable));
        }
    }

    /** @return true if the flag exists and is set to 1 for the given player. */
    public boolean hasFlag(UUID playerUuid, String flagKey) throws SQLException {
        String sql = "SELECT flag_value FROM " + cfg.tablePrefix + "player_flags "
                + "WHERE player_uuid = ? AND flag_key = ?";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, flagKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return rs.getInt(1) == 1;
            }
        }
    }

    /** Atomic upsert. Sets {@code flag_value = 1} for the (player, flag) pair. */
    public void setFlag(UUID playerUuid, String flagKey) throws SQLException {
        String sql = "INSERT INTO " + cfg.tablePrefix + "player_flags "
                + "(player_uuid, flag_key, flag_value) VALUES (?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE flag_value = 1";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, flagKey);
            ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }
}
