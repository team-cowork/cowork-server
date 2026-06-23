package com.cowork.channel.domain.meetingNote.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_meeting_note_template_sections")
class TemplateSection(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "template_id", nullable = false)
    val templateId: Long,

    @Column(nullable = false, length = 100)
    var title: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: SectionType,

    @Column(length = 255)
    var placeholder: String? = null,

    @Column(name = "is_required", nullable = false)
    var isRequired: Boolean = false,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(title: String?, type: SectionType?, placeholder: String?, isRequired: Boolean?) {
        title?.let { this.title = it }
        type?.let { this.type = it }
        placeholder?.let { this.placeholder = it }
        isRequired?.let { this.isRequired = it }
    }
}
