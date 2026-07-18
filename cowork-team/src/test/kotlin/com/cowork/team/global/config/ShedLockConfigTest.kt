package com.cowork.team.global.config

import io.mockk.mockk
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import javax.sql.DataSource

class ShedLockConfigTest {

    @Test
    fun `registers a locking task executor for programmatic locks`() {
        AnnotationConfigApplicationContext().use { context ->
            context.beanFactory.registerSingleton("dataSource", mockk<DataSource>())
            context.register(ShedLockConfig::class.java)
            context.refresh()

            assertThat(context.getBeansOfType(LockingTaskExecutor::class.java).values).hasSize(1)
        }
    }
}
