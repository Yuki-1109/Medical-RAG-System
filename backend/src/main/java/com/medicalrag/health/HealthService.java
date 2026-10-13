package com.medicalrag.health;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import javax.sql.DataSource;

import org.springframework.stereotype.Service;

import static com.medicalrag.health.HealthResponse.Component;

@Service
public class HealthService {
    private final DataSource dataSource;

    public HealthService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public HealthResponse check() {
        Component backend = Component.up("Spring Boot 后端已响应", null);
        try (Connection connection = dataSource.getConnection()) {
            String databaseVersion = querySingle(connection, "SELECT current_setting('server_version')");
            Component database = Component.up("PostgreSQL 实际连接成功", databaseVersion);
            Component vector = checkVector(connection);
            Component migrations = checkMigrations(connection);
            String status = "UP".equals(vector.status()) && "UP".equals(migrations.status()) ? "UP" : "DOWN";
            return new HealthResponse(status, Instant.now(), backend, database, vector, migrations);
        } catch (SQLException exception) {
            // Never send SQL errors, JDBC URLs, usernames or credentials to the browser.
            return new HealthResponse("DOWN", Instant.now(), backend,
                    Component.down("无法连接 PostgreSQL，请检查数据库运行状态和本机配置"),
                    Component.unknown("数据库不可用，尚无法检查 pgvector"),
                    Component.unknown("数据库不可用，尚无法读取迁移记录"));
        }
    }

    private Component checkVector(Connection connection) {
        try {
            String version = querySingle(connection, "SELECT extversion FROM pg_extension WHERE extname = 'vector'");
            if (version == null) {
                return Component.down("当前数据库未启用 pgvector，请检查扩展安装和迁移");
            }
            // Verify the type/operator works, rather than only trusting the extension registry.
            String distance = querySingle(connection, "SELECT '[1,0]'::vector <-> '[1,0]'::vector");
            return "0".equals(distance)
                    ? Component.up("pgvector 扩展及向量运算可用", version)
                    : Component.down("pgvector 向量运算检查失败");
        } catch (SQLException exception) {
            return Component.down("pgvector 检查失败，请检查扩展和数据库权限");
        }
    }

    private Component checkMigrations(Connection connection) {
        try {
            String version = querySingle(connection,
                    "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
            String failed = querySingle(connection, "SELECT count(*) FROM flyway_schema_history WHERE success = false");
            if (version == null || !"0".equals(failed)) {
                return Component.down("迁移记录缺失或包含失败记录，请检查后端启动日志");
            }
            return Component.up("Flyway 已执行迁移，当前迁移记录可读", version);
        } catch (SQLException exception) {
            return Component.down("无法读取 Flyway 迁移记录，请检查数据库初始化");
        }
    }

    private String querySingle(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            try (ResultSet result = statement.executeQuery(sql)) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }
}
