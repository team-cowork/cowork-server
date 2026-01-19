package com.cowork.channel.meetingnote.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_meeting_note_template_sections")
class TemplateSection(
    @Column(name = "template_id", nullable = false)
    val templateId: Long,

    @Column(nullable = false, length = 100)
    var title: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: SectionType,

    @Column(length = 255)
    var placeholder: String?,

    @Column(nullable = false)
    var required: Boolean,

    @Column(nullable = false)
    var position: Int,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) {
    fun update(title: String?, type: SectionType?, placeholder: String?, required: Boolean?, position: Int?) {
        title?.let { this.title = it }
        type?.let { this.type = it }
        placeholder?.let { this.placeholder = it.ifBlank { null } }
        required?.let { this.required = it }
        position?.let { this.position = it }
    }
}
