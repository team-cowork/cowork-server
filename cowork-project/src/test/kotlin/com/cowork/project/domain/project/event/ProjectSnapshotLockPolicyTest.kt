package com.cowork.project.domain.project.event

import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock

class ProjectSnapshotLockPolicyTest :
    StringSpec({
        "project snapshot은 source row를 비관적 쓰기 잠금한다" {
            val method = ProjectRepository::class.java.getMethod(
                "findSnapshotBatch",
                Long::class.javaPrimitiveType,
                Pageable::class.java,
            )

            method.getAnnotation(Lock::class.java).value shouldBe LockModeType.PESSIMISTIC_WRITE
        }

        "project member snapshot은 source row를 비관적 쓰기 잠금한다" {
            val method = ProjectMemberRepository::class.java.getMethod(
                "findSnapshotByProjectId",
                Long::class.javaPrimitiveType,
            )

            method.getAnnotation(Lock::class.java).value shouldBe LockModeType.PESSIMISTIC_WRITE
        }
    })
