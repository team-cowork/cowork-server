package com.cowork.channel.meetingnote.repository

import com.cowork.channel.meetingnote.domain.TemplateSection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TemplateSectionRepository : JpaRepository<TemplateSection, Long> {

    fun findAllByTemplateIdOrderByPositionAscIdAsc(templateId: Long): List<TemplateSection>

    fun findAllByTemplateIdInOrderByPositionAscIdAsc(templateIds: Collection<Long>): List<TemplateSection>

    fun findByIdAndTemplateId(id: Long, templateId: Long): TemplateSection?

    @Query("SELECT COALESCE(MAX(s.position), -1) FROM TemplateSection s WHERE s.templateId = :templateId")
    fun findMaxPosition(templateId: Long): Int

    @Modifying
    @Query("DELETE FROM TemplateSection s WHERE s.templateId IN :templateIds")
    fun deleteAllByTemplateIdIn(templateIds: Collection<Long>)
}
