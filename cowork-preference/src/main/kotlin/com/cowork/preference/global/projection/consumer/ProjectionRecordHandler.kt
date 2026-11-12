package com.cowork.preference.global.projection.consumer

import com.cowork.preference.global.projection.checkpoint.ProjectionCheckpoint

fun interface ProjectionRecordHandler {
    /** 유효한 record를 checkpoint와 같은 transaction에 반영하고, 계약 위반이면 격리 사유를 반환한다. */
    suspend fun apply(checkpoint: ProjectionCheckpoint, key: String?, value: String?): String?
}
