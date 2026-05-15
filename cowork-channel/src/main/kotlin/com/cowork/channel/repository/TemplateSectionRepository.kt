package com.cowork.channel.repository

import com.cowork.channel.domain.TemplateSection
import org.springframework.data.jpa.repository.JpaRepository

interface TemplateSectionRepository : JpaRepository<TemplateSection, Long> {

    fun findAllByTemplateIdOrderByIdAsc(templateId: Long): List<TemplateSection>

    fun findAllByTemplateIdInOrderByIdAsc(templateIds: List<Long>): List<TemplateSection>
}
