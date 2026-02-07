package com.cowork.user.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_users")
class User(
    @Column(name = "datagsm_id", nullable = false, unique = true)
    val dataGsmId: Long,

    @Column(nullable = false, length = 50)
    var name: String,

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(nullable = false, length = 10)
    var sex: String,

    var grade: Int? = null,

    @Column(name = "class_num")
    var classNum: Int? = null,

    @Column(name = "student_number")
    var studentNumber: Int? = null,

    @Column(length = 30)
    var major: String? = null,

    @Column(name = "student_role", length = 30)
    var studentRole: String? = null,

    @Column(name = "github_id", length = 100)
    var githubId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: UserRole = UserRole.MEMBER,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    @Column(length = 50)
    var nickname: String? = null
        protected set

    @Column(length = 500)
    var description: String? = null
        protected set

    @Column(length = 255)
    var specialty: String? = null
        protected set

    @Column(name = "profile_image_key", length = 500)
    var profileImageKey: String? = null
        protected set

    fun syncStudentInfo(
        name: String,
        email: String,
        sex: String,
        grade: Int?,
        classNum: Int?,
        studentNumber: Int?,
        major: String?,
        studentRole: String?,
        githubId: String?,
    ) {
        this.name = name
        this.email = email
        this.sex = sex
        this.grade = grade
        this.classNum = classNum
        this.studentNumber = studentNumber
        this.major = major
        this.studentRole = studentRole
        this.githubId = githubId
    }

    fun updateProfile(nickname: String?, description: String?, specialty: String?) {
        nickname?.let { this.nickname = it.ifBlank { null } }
        description?.let { this.description = it.ifBlank { null } }
        specialty?.let { this.specialty = it.ifBlank { null } }
    }

    fun changeProfileImage(objectKey: String?) {
        this.profileImageKey = objectKey
    }

    fun changeRole(role: UserRole) {
        this.role = role
    }
}
