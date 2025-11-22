package com.cowork.preference.repository

import com.cowork.preference.domain.ResourceSetting
import com.cowork.preference.domain.ResourceType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface ResourceSettingRepository : JpaRepository<ResourceSetting, Long> {

    fun findByResourceTypeAndResourceId(resourceType: ResourceType, resourceId: Long): ResourceSetting?

    fun findAllByResourceTypeAndResourceIdIn(resourceType: ResourceType, resourceIds: Collection<Long>): List<ResourceSetting>

    @Query(
        value = """
            SELECT * FROM tb_resource_settings
            WHERE resource_type = 'ACCOUNT'
              AND JSON_EXTRACT(settings, '$.status_expires_at') IS NOT NULL
        """,
        nativeQuery = true,
    )
    fun findAccountsWithStatusExpiry(): List<ResourceSetting>

    @Modifying
    @Query("DELETE FROM ResourceSetting s WHERE s.resourceType = :resourceType AND s.resourceId = :resourceId")
    fun deleteByResource(resourceType: ResourceType, resourceId: Long)
}
