package com.cowork.channel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldBeEmpty
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.annotation.AnnotationBeanNameGenerator
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Component

class ComponentBeanNameUniquenessTest :
    StringSpec({
        "component scan bean names are unique" {
            val scanner = ClassPathScanningCandidateComponentProvider(false)
            scanner.addIncludeFilter(AnnotationTypeFilter(Component::class.java))

            val registry = DefaultListableBeanFactory()
            val duplicates = scanner.findCandidateComponents("com.cowork.channel")
                .groupBy { AnnotationBeanNameGenerator.INSTANCE.generateBeanName(it, registry) }
                .filterValues { it.size > 1 }

            duplicates.shouldBeEmpty()
        }
    })
