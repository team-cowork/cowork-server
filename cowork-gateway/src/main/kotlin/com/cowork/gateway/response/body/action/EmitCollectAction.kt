package com.cowork.gateway.response.body.action

import org.springframework.core.io.buffer.DataBuffer

internal data class EmitCollectAction(val buffers: List<DataBuffer>, val totalBytes: Int) : CollectAction
