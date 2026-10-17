package com.audigo.global.migration.pending;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// 컷오버로 ProxySQL 목적지가 바뀐 뒤 커넥션 풀을 새 커넥션으로 교체한다.
// ProxySQL은 multiplexing이 꺼진 세션을 이전 백엔드에 묶어 두므로, 기존 커넥션을 재사용하면
// 원본으로 쓰기가 가거나(OFFLINE_SOFT) 끊긴 연결로 요청이 실패한다(OFFLINE_HARD).
// soft evict는 쉬는 커넥션을 바로 닫고 사용 중인 커넥션은 반납될 때 닫으므로 진행 중인 요청을 끊지 않는다
@Component
public class ConnectionPoolRefresher {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolRefresher.class);

    private final DataSource dataSource;

    public ConnectionPoolRefresher(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void refresh(String reason) {
        HikariPoolMXBean pool = hikariPool();
        if (pool == null) {
            log.warn("migration_connection_pool_refresh_skipped reason={} cause=not_hikari_or_not_started", reason);
            return;
        }
        int active = pool.getActiveConnections();
        int idle = pool.getIdleConnections();
        pool.softEvictConnections();
        log.info("migration_connection_pool_refreshed reason={} active={} idle={}", reason, active, idle);
    }

    private HikariPoolMXBean hikariPool() {
        try {
            if (!dataSource.isWrapperFor(HikariDataSource.class)) {
                return null;
            }
            return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        } catch (SQLException exception) {
            log.warn("migration_connection_pool_refresh_failed cause={}", exception.toString());
            return null;
        }
    }
}
