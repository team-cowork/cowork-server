package com.cowork.channel.global.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.mockk
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import javax.sql.DataSource

class ShedLockConfigTest :
    StringSpec({
        "registers a locking task executor for programmatic locks" {
            AnnotationConfigApplicationContext().use { context ->
                context.beanFactory.registerSingleton("dataSource", mockk<DataSource>())
                context.register(ShedLockConfig::class.java)
                context.refresh()

                context.getBeansOfType(LockingTaskExecutor::class.java).values.shouldHaveSize(1)
            }
        }
    })
