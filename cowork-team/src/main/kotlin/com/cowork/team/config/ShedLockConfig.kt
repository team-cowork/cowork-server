package com.cowork.team.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class ShedLockConfig {

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider = JdbcTemplateLockProvider(dataSource)
}
