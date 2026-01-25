package com.cowork.channel.meetingnote.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "tb_meeting_notes")
class MeetingNote(
    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "template_id", nullable = false)
    val templateId: Long,

    @Column(nullable = false, length = 200)
    val title: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSON")
    val content: List<NoteSection>,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity()

/**
 * 회의록 작성 시점의 섹션 정보를 함께 저장하여 템플릿이 바뀌어도 기존 회의록이 유지되게 한다.
 */
data class NoteSection(
    val sectionId: Long,
    val title: String,
    val type: SectionType,
    val value: Any?,
)
