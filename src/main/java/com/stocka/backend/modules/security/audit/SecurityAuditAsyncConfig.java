package com.stocka.backend.modules.security.audit;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Bounded executor backing {@link SecurityAuditListener}. Sized small on
 * purpose: audit writes are cheap, low-throughput and must not balloon
 * memory under a login storm. The bounded queue keeps a bad spike from
 * exhausting the heap; when full, Spring falls back to the caller thread
 * which slows the request down a hair instead of dropping events.
 */
@Configuration
public class SecurityAuditAsyncConfig {

    @Bean(name = "securityAuditExecutor")
    public Executor securityAuditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("security-audit-");
        executor.initialize();
        return executor;
    }
}
