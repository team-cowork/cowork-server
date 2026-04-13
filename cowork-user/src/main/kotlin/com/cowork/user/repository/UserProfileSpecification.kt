package com.cowork.user.repository

import com.cowork.user.domain.UserProfile
import org.springframework.data.jpa.domain.Specification

object UserProfileSpecification {

    fun nameLike(name: String?): Specification<UserProfile>? =
        name?.takeIf { it.isNotBlank() }?.let { n ->
            Specification { root, _, cb -> cb.like(root.get("name"), "%$n%") }
        }

    fun majorEq(major: String?): Specification<UserProfile>? =
        major?.takeIf { it.isNotBlank() }?.let { m ->
            Specification { root, _, cb -> cb.equal(root.get<String>("major"), m) }
        }

    fun gradeEq(grade: Byte?): Specification<UserProfile>? =
        grade?.let { g ->
            Specification { root, _, cb -> cb.equal(root.get<Byte>("grade"), g) }
        }

    fun classEq(cls: Byte?): Specification<UserProfile>? =
        cls?.let { c ->
            Specification { root, _, cb -> cb.equal(root.get<Byte>("class"), c) }
        }

    fun roleEq(role: String?): Specification<UserProfile>? =
        role?.takeIf { it.isNotBlank() }?.let { r ->
            Specification { root, _, cb -> cb.equal(root.get<String>("role"), r) }
        }

    fun build(
        name: String?,
        major: String?,
        grade: Byte?,
        cls: Byte?,
        role: String?,
    ): Specification<UserProfile> {
        val specs = listOfNotNull(
            nameLike(name),
            majorEq(major),
            gradeEq(grade),
            classEq(cls),
            roleEq(role),
        )
        return specs.fold(Specification.where(null)) { acc, spec -> acc.and(spec) }
    }
}
