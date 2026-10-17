package com.audigo.global.config;

import com.audigo.global.migration.tracking.MigrationAwareJpaTransactionManager;
import com.audigo.global.migration.tracking.TransactionWriteObserver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

// Boot 자동 구성의 JpaTransactionManager 대신 쓰기 트랜잭션을 세는 하위 클래스를 등록한다
@Configuration(proxyBeanMethods = false)
public class TransactionManagerConfig {

    @Bean
    public PlatformTransactionManager transactionManager(
            TransactionWriteObserver transactionWriteObserver,
            ObjectProvider<TransactionManagerCustomizers> transactionManagerCustomizers
    ) {
        // EntityManagerFactory는 자동 구성과 같이 BeanFactory에서 찾는다
        MigrationAwareJpaTransactionManager transactionManager =
                new MigrationAwareJpaTransactionManager(transactionWriteObserver);
        transactionManagerCustomizers.ifAvailable(customizers -> customizers.customize(transactionManager));
        return transactionManager;
    }
}
