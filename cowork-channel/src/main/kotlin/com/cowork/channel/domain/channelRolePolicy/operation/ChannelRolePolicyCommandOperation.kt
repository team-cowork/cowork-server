package com.cowork.channel.domain.channelRolePolicy.operation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_channel_role_policy_operations")
class ChannelRolePolicyCommandOperation(
    @Id
    @Column(name = "operation_id", nullable = false, length = 36)
    val operationId: String,

    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "role_id", nullable = false)
    val roleId: Long,

    @Column(name = "actor_id", nullable = false)
    val actorId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, length = 20)
    val commandType: ChannelRolePolicyCommandType,

    @Column(name = "request_hash", nullable = false, length = 64)
    val requestHash: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ChannelRolePolicyOperationStatus = ChannelRolePolicyOperationStatus.PENDING,

    @Column(name = "result_permissions_json", length = 100)
    var resultPermissionsJson: String? = null,

    @Column(name = "error_code", length = 100)
    var errorCode: String? = null,

    @Column(name = "error_message", length = 1000)
    var errorMessage: String? = null,

    @Column(name = "expected_projection_key", length = 120)
    var expectedProjectionKey: String? = null,

    @Column(name = "expected_projection_occurred_at")
    var expectedProjectionOccurredAt: Instant? = null,

    @Column(name = "expected_projection_deleted")
    var expectedProjectionDeleted: Boolean? = null,
) {
    fun matches(
        teamId: Long,
        channelId: Long,
        roleId: Long,
        actorId: Long,
        commandType: ChannelRolePolicyCommandType,
        requestHash: String,
    ): Boolean = this.teamId == teamId &&
        this.channelId == channelId &&
        this.roleId == roleId &&
        this.actorId == actorId &&
        this.commandType == commandType &&
        this.requestHash == requestHash

    fun markProcessing(resultPermissionsJson: String?, expectation: ChannelRolePolicyProjectionExpectation) {
        if (status == ChannelRolePolicyOperationStatus.PROCESSING ||
            status == ChannelRolePolicyOperationStatus.SUCCEEDED
        ) {
            require(this.resultPermissionsJson == resultPermissionsJson) {
                "같은 operation에 상충하는 channel role policy result가 수신되었습니다."
            }
            require(
                expectedProjectionKey == expectation.key &&
                    expectedProjectionOccurredAt == expectation.occurredAt &&
                    expectedProjectionDeleted == expectation.deleted,
            ) { "같은 operation에 상충하는 projection expectation이 수신되었습니다." }
            require(errorCode == null && errorMessage == null) {
                "성공 처리 중인 operation에 error 상태가 함께 저장되어 있습니다."
            }
            return
        }
        require(status == ChannelRolePolicyOperationStatus.PENDING) {
            "실패한 operation을 성공 result로 변경할 수 없습니다."
        }
        status = ChannelRolePolicyOperationStatus.PROCESSING
        this.resultPermissionsJson = resultPermissionsJson
        errorCode = null
        errorMessage = null
        expectedProjectionKey = expectation.key
        expectedProjectionOccurredAt = expectation.occurredAt
        expectedProjectionDeleted = expectation.deleted
    }

    fun markSucceeded() {
        if (status == ChannelRolePolicyOperationStatus.PROCESSING) {
            status = ChannelRolePolicyOperationStatus.SUCCEEDED
        }
    }

    fun markFailed(code: String, message: String) {
        require(code.isNotBlank() && code.length <= 100) { "error code는 1~100자여야 합니다." }
        require(message.isNotBlank() && message.length <= 1000) { "error message는 1~1000자여야 합니다." }
        if (status == ChannelRolePolicyOperationStatus.FAILED) {
            require(errorCode == code && errorMessage == message) {
                "같은 operation에 상충하는 실패 result가 수신되었습니다."
            }
            return
        }
        require(status == ChannelRolePolicyOperationStatus.PENDING) {
            "성공 처리 중이거나 완료된 operation을 실패 result로 변경할 수 없습니다."
        }
        status = ChannelRolePolicyOperationStatus.FAILED
        resultPermissionsJson = null
        errorCode = code
        errorMessage = message
        expectedProjectionKey = null
        expectedProjectionOccurredAt = null
        expectedProjectionDeleted = null
    }
}
