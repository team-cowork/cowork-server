package com.cowork.project.domain

enum class ProjectMemberRole {
    OWNER,
    EDITOR,
    VIEWER,
    ;

    val canEdit: Boolean get() = this == OWNER || this == EDITOR
}
