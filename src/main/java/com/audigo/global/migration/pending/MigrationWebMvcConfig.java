package com.audigo.global.migration.pending;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 모든 경로에 등록한다. 제어 대상이 아닌 요청(헬스 체크·관리 API 등)은 classifier가 BYPASS로 판정한다
@Configuration(proxyBeanMethods = false)
public class MigrationWebMvcConfig implements WebMvcConfigurer {

    private final MigrationWriteGateInterceptor migrationWriteGateInterceptor;

    public MigrationWebMvcConfig(MigrationWriteGateInterceptor migrationWriteGateInterceptor) {
        this.migrationWriteGateInterceptor = migrationWriteGateInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(migrationWriteGateInterceptor).addPathPatterns("/**");
    }
}
