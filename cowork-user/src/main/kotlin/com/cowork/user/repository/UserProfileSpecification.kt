package com.cowork.user.repository

import com.cowork.user.domain.Major
import com.cowork.user.domain.Role
import com.cowork.user.domain.UserProfile
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

object UserProfileSpecification {

    fun nameLike(name: String?): Specification<UserProfile>? =
        name?.takeIf { it.isNotBlank() }?.let { n ->
            Specification { root, _, cb -> cb.like(root.get("name"), "%$n%") }
        }

    fun majorEq(major: String?): Specification<UserProfile>? =
        major?.takeIf { it.isNotBlank() }
            ?.let { value ->
                runCatching { Major.valueOf(value) }
                    .getOrElse { throw ExpectedException("유효하지 않은 major 값입니다: $value", HttpStatus.BAD_REQUEST) }
            }
            ?.let { m -> Specification { root, _, cb -> cb.equal(root.get<Major>("major"), m) } }

    fun gradeEq(grade: Byte?): Specification<UserProfile>? =
        grade?.let { g ->
            Specification { root, _, cb -> cb.equal(root.get<Byte>("grade"), g) }
        }

    fun classEq(cls: Byte?): Specification<UserProfile>? =
        cls?.let { c ->
            Specification { root, _, cb -> cb.equal(root.get<Byte>("class"), c) }
        }

    fun roleEq(role: String?): Specification<UserProfile>? =
        role?.takeIf { it.isNotBlank() }
            ?.let { value ->
                runCatching { Role.valueOf(value) }
                    .getOrElse { throw ExpectedException("유효하지 않은 role 값입니다: $value", HttpStatus.BAD_REQUEST) }
            }
            ?.let { r -> Specification { root, _, cb -> cb.equal(root.get<Role>("role"), r) } }

    fun build(
        name: String?,
        major: String?,
        grade: Byte?,
        cls: Byte?,
        role: String?,
    ): Specification<UserProfile> =
        listOfNotNull(nameLike(name), majorEq(major), gradeEq(grade), classEq(cls), roleEq(role))
            .fold(Specification.where(null)) { acc, spec -> acc.and(spec) }
}
