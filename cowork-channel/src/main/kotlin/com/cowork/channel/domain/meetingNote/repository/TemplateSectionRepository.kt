package com.cowork.channel.domain.meetingNote.repository

import com.cowork.channel.domain.meetingNote.entity.TemplateSection
import org.springframework.data.jpa.repository.JpaRepository

interface TemplateSectionRepository : JpaRepository<TemplateSection, Long> {

    fun findAllByTemplateIdOrderByIdAsc(templateId: Long): List<TemplateSection>

    fun findAllByTemplateIdInOrderByIdAsc(templateIds: List<Long>): List<TemplateSection>
}
