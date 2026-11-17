package com.cowork.channel.domain.thread.service

import com.cowork.channel.domain.thread.event.ThreadParentResult

interface ResolveThreadParentService {
    fun resolve(result: ThreadParentResult)
}
