package com.cowork.config

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.annotation.AnnotationBeanNameGenerator

class EurekaServerConfigurationTest {
    @Test
    fun `custom Eureka configuration does not use Spring Cloud's bean name`() {
        val beanName =
            AnnotationBeanNameGenerator.INSTANCE.generateBeanName(
                AnnotatedGenericBeanDefinition(EurekaServerBootstrapConfiguration::class.java),
                DefaultListableBeanFactory(),
            )

        assertNotEquals("eurekaServerConfig", beanName)
    }
}
