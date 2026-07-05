package com.w4nya.hamreporter.server.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class SqliteDatabaseManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SqliteDatabaseManager.class);
    
    private final HikariDataSource dataSource;

    public SqliteDatabaseManager(String dbFilePath) {
        HikariConfig config = new HikariConfig();

        logger.info("Initializing SQLite database at: {}", dbFilePath);
        
        config.setJdbcUrl("jdbc:sqlite:" + dbFilePath);
        
        config.setMaximumPoolSize(5); 
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("foreign_keys", "ON");
        config.addDataSourceProperty("busy_timeout", "5000");

        this.dataSource = new HikariDataSource(config);
        logger.info("SQLite database initialized successfully.");
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @FunctionalInterface
    public interface ResultSetHandler<T> {
        T handle(ResultSet rs) throws SQLException;
    }

    public <T> T executeQuery(String sql, ResultSetHandler<T> handler, Object... params) throws SQLException {
        try (Connection conn = getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            for (int i = 0; i < params.length; i++) {
                pstmt.setObject(i + 1, params[i]);
            }
            
            try (ResultSet rs = pstmt.executeQuery()) {
                return handler.handle(rs);
            }
        }
    }

    public int executeUpdate(String sql, Object... params) throws SQLException {
        try (Connection conn = getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            for (int i = 0; i < params.length; i++) {
                if (params[i] == null) {
                    pstmt.setNull(i + 1, java.sql.Types.NULL);
                } else {
                    pstmt.setObject(i + 1, params[i]);
                }
            }
            
            return pstmt.executeUpdate();
        }
    }
}
