package com.cowork.preference.repository

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple

object PreferenceOutboxOrdering {
    const val LOCK_ID = 0x434F574F524B5052L

    suspend fun acquireForWrite(connection: SqlConnection) {
        connection.preparedQuery("SELECT pg_advisory_xact_lock(${'$'}1)")
            .execute(Tuple.of(LOCK_ID))
            .coAwait()
    }
}
