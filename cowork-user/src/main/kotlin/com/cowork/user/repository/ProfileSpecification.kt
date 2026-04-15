package com.cowork.user.repository

import com.cowork.user.domain.Profile
import org.springframework.data.jpa.domain.Specification

object ProfileSpecification {

    fun nameLike(name: String?): Specification<Profile>? =
        name?.takeIf { it.isNotBlank() }?.let { value ->
            Specification { root, _, cb -> cb.like(root.join<Any, Any>("account").get("name"), "%$value%") }
        }

    fun nicknameLike(nickname: String?): Specification<Profile>? =
        nickname?.takeIf { it.isNotBlank() }?.let { value ->
            Specification { root, _, cb -> cb.like(root.get("nickname"), "%$value%") }
        }

    fun majorEq(major: String?): Specification<Profile>? =
        major?.takeIf { it.isNotBlank() }?.let { value ->
            Specification { root, _, cb -> cb.equal(root.join<Any, Any>("account").get<String>("major"), value) }
        }

    fun stRoleEq(stRole: String?): Specification<Profile>? =
        stRole?.takeIf { it.isNotBlank() }?.let { value ->
            Specification { root, _, cb -> cb.equal(root.join<Any, Any>("account").get<String>("stRole"), value) }
        }

    fun statusEq(status: String?): Specification<Profile>? =
        status?.takeIf { it.isNotBlank() }?.let { value ->
            Specification { root, _, cb -> cb.equal(root.join<Any, Any>("account").get<String>("status"), value) }
        }

    fun roleEq(role: String?): Specification<Profile>? =
        role?.takeIf { it.isNotBlank() }?.let { value ->
            Specification { root, query, cb ->
                query?.distinct(true)
                cb.equal(root.joinSet<String>("roles"), value)
            }
        }

    fun build(
        name: String?,
        nickname: String?,
        major: String?,
        stRole: String?,
        status: String?,
        role: String?,
    ): Specification<Profile> =
        listOfNotNull(
            nameLike(name),
            nicknameLike(nickname),
            majorEq(major),
            stRoleEq(stRole),
            statusEq(status),
            roleEq(role),
        ).fold(Specification.where(null)) { acc, spec -> acc.and(spec) }
}
