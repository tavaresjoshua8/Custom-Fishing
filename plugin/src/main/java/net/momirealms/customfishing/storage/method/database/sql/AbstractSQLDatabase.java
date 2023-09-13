/*
 *  Copyright (C) <2022> <XiaoMoMi>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.momirealms.customfishing.storage.method.database.sql;

import net.momirealms.customfishing.api.CustomFishingPlugin;
import net.momirealms.customfishing.api.data.PlayerData;
import net.momirealms.customfishing.api.data.user.OfflineUser;
import net.momirealms.customfishing.api.util.LogUtils;
import net.momirealms.customfishing.setting.CFConfig;
import net.momirealms.customfishing.storage.method.AbstractStorage;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractSQLDatabase extends AbstractStorage {

    protected String tablePrefix;

    public AbstractSQLDatabase(CustomFishingPlugin plugin) {
        super(plugin);
    }

    public abstract Connection getConnection() throws SQLException;

    public void createTableIfNotExist() {
        try (Connection connection = getConnection()) {
            final String[] databaseSchema = getSchema(getStorageType().name().toLowerCase(Locale.ENGLISH));
            try (Statement statement = connection.createStatement()) {
                for (String tableCreationStatement : databaseSchema) {
                    statement.execute(tableCreationStatement);
                }
            } catch (SQLException e) {
                LogUtils.warn("Failed to create tables", e);
            }
        } catch (SQLException e) {
            LogUtils.warn("Failed to get sql connection", e);
        } catch (IOException e) {
            LogUtils.warn("Failed to get schema resource", e);
        }
    }

    private String[] getSchema(@NotNull String fileName) throws IOException {
        return replaceSchemaPlaceholder(new String(Objects.requireNonNull(plugin.getResource("schema/" + fileName + ".sql"))
               .readAllBytes(), StandardCharsets.UTF_8)).split(";");
    }

    private String replaceSchemaPlaceholder(@NotNull String sql) {
        return sql.replace("{prefix}", tablePrefix);
    }

    public String getTableName(String sub) {
        return getTablePrefix() + "_" + sub;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public CompletableFuture<Optional<PlayerData>> getPlayerData(UUID uuid, boolean lock) {
        var future = new CompletableFuture<Optional<PlayerData>>();
        plugin.getScheduler().runTaskAsync(() -> {
        try (
            Connection connection = getConnection();
            PreparedStatement statement = connection.prepareStatement(String.format(SqlConstants.SQL_SELECT_BY_UUID, getTableName("data")))
        ) {
            statement.setString(1, uuid.toString());
            ResultSet rs = statement.executeQuery();
            if (rs.next()) {
                int lockValue = rs.getInt(2);
                if (lockValue != 0 && getCurrentSeconds() - CFConfig.dataSaveInterval <= lockValue) {
                    connection.close();
                    future.complete(Optional.of(PlayerData.LOCKED));
                    return;
                }
                final Blob blob = rs.getBlob("data");
                final byte[] dataByteArray = blob.getBytes(1, (int) blob.length());
                blob.free();
                if (lock) lockPlayerData(uuid, true);
                future.complete(Optional.of(plugin.getStorageManager().fromBytes(dataByteArray)));
            } else if (Bukkit.getPlayer(uuid) != null) {
                var data = PlayerData.empty();
                insertPlayerData(uuid, data, lock);
                future.complete(Optional.of(data));
            } else {
                future.complete(Optional.empty());
            }
        } catch (SQLException e) {
            LogUtils.warn("Failed to get " + uuid + "'s data.", e);
            future.completeExceptionally(e);
        }
        });
        return future;
    }

    @Override
    public CompletableFuture<Boolean> updatePlayerData(UUID uuid, PlayerData playerData, boolean unlock) {
        var future = new CompletableFuture<Boolean>();
        plugin.getScheduler().runTaskAsync(() -> {
        try (
            Connection connection = getConnection();
            PreparedStatement statement = connection.prepareStatement(String.format(SqlConstants.SQL_UPDATE_BY_UUID, getTableName("data")))
        ) {
            statement.setInt(1, unlock ? 0 : getCurrentSeconds());
            statement.setBlob(2, new ByteArrayInputStream(plugin.getStorageManager().toBytes(playerData)));
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
            future.complete(true);
            plugin.debug("SQL data saved for " + uuid + "; unlock: " + unlock);
        } catch (SQLException e) {
            LogUtils.warn("Failed to update " + uuid + "'s data.", e);
            future.completeExceptionally(e);
        }
        });
        return future;
    }

    @Override
    public void updateManyPlayersData(Collection<? extends OfflineUser> users, boolean unlock) {
        String sql = String.format(SqlConstants.SQL_UPDATE_BY_UUID, getTableName("data"));
        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (OfflineUser user : users) {
                    statement.setInt(1, unlock ? 0 : getCurrentSeconds());
                    statement.setBlob(2, new ByteArrayInputStream(plugin.getStorageManager().toBytes(user.getPlayerData())));
                    statement.setString(3, user.getUUID().toString());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                LogUtils.warn("Failed to update data for online players", e);
            }
        } catch (SQLException e) {
            LogUtils.warn("Failed to get connection when saving online players' data", e);
        }
    }

    public void insertPlayerData(UUID uuid, PlayerData playerData, boolean lock) {
        try (
            Connection connection = getConnection();
            PreparedStatement statement = connection.prepareStatement(String.format(SqlConstants.SQL_INSERT_DATA_BY_UUID, getTableName("data")))
        ) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, lock ? getCurrentSeconds() : 0);
            statement.setBlob(3, new ByteArrayInputStream(plugin.getStorageManager().toBytes(playerData)));
            statement.execute();
        } catch (SQLException e) {
            LogUtils.warn("Failed to insert " + uuid + "'s data.", e);
        }
    }

    @Override
    public void lockPlayerData(UUID uuid, boolean lock) {
        try (
            Connection connection = getConnection();
            PreparedStatement statement = connection.prepareStatement(String.format(SqlConstants.SQL_LOCK_BY_UUID, getTableName("data")))
        ) {
            statement.setInt(1, lock ? getCurrentSeconds() : 0);
            statement.setString(2, uuid.toString());
            statement.execute();
        } catch (SQLException e) {
            LogUtils.warn("Failed to lock " + uuid + "'s data.", e);
        }
    }

    @Override
    public CompletableFuture<Boolean> updateOrInsertPlayerData(UUID uuid, PlayerData playerData, boolean unlock) {
        var future = new CompletableFuture<Boolean>();
        plugin.getScheduler().runTaskAsync(() -> {
            try (
                Connection connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(String.format(SqlConstants.SQL_SELECT_BY_UUID, getTableName("data")))
            ) {
                statement.setString(1, uuid.toString());
                ResultSet rs = statement.executeQuery();
                if (rs.next()) {
                    updatePlayerData(uuid, playerData, unlock).thenRun(() -> future.complete(true));
                } else {
                    insertPlayerData(uuid, playerData, !unlock);
                    future.complete(true);
                }
            } catch (SQLException e) {
                LogUtils.warn("Failed to get " + uuid + "'s data.", e);
            }
        });
        return future;
    }

    @Override
    public Set<UUID> getUniqueUsers(boolean legacy) {
        Set<UUID> uuids = new HashSet<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(String.format(SqlConstants.SQL_SELECT_ALL_UUID, legacy ? getTableName("fishingbag") : getTableName("data")))) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    uuids.add(uuid);
                }
            }
        } catch (SQLException e) {
            LogUtils.warn("Failed to get unique data.", e);
        }
        return uuids;
    }

    public static class SqlConstants {
        public static final String SQL_SELECT_BY_UUID = "SELECT * FROM `%s` WHERE `uuid` = ?";
        public static final String SQL_SELECT_ALL_UUID = "SELECT uuid FROM `%s`";
        public static final String SQL_UPDATE_BY_UUID = "UPDATE `%s` SET `lock` = ?, `data` = ? WHERE `uuid` = ?";
        public static final String SQL_LOCK_BY_UUID = "UPDATE `%s` SET `lock` = ? WHERE `uuid` = ?";
        public static final String SQL_INSERT_DATA_BY_UUID = "INSERT INTO `%s`(`uuid`, `lock`, `data`) VALUES(?, ?, ?)";
    }
}
