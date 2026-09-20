package com.cowork.gateway.response.body.action

import org.springframework.core.io.buffer.DataBuffer

internal data class RelayCollectAction(val dataBuffer: DataBuffer) : CollectAction
