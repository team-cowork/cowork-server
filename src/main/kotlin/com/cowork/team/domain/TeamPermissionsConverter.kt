package com.cowork.team.domain

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class TeamPermissionsConverter : AttributeConverter<Set<TeamPermission>, String> {

    override fun convertToDatabaseColumn(attribute: Set<TeamPermission>?): String =
        attribute.orEmpty().sortedBy { it.ordinal }.joinToString(",") { it.name }

    override fun convertToEntityAttribute(dbData: String?): Set<TeamPermission> =
        dbData.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { name -> TeamPermission.entries.firstOrNull { it.name == name } }
            .toSet()
}
