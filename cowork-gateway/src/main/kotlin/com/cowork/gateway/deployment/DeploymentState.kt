package com.cowork.gateway.deployment

data class DeploymentState(val runId: String, val phase: String) {
    init {
        require(runId.isNotBlank() && runId.length <= 128)
        require(phase == "deploying" || phase == "recovering")
    }
}
