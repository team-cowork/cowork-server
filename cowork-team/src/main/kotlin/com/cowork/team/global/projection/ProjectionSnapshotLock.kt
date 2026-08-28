package com.cowork.team.global.projection

import org.springframework.stereotype.Component
import java.sql.Connection
import javax.sql.DataSource

@Component
class ProjectionSnapshotLock(private val dataSource: DataSource) {
    fun tryRun(lockName: String, action: () -> Unit): Boolean {
        require(lockName.isNotBlank() && lockName.length <= MAX_LOCK_NAME_LENGTH) {
            "MySQL snapshot lock name must contain 1..$MAX_LOCK_NAME_LENGTH characters."
        }
        dataSource.connection.use { connection ->
            if (!tryAcquire(connection, lockName)) return false
            runWithRelease(connection, lockName, action)
        }
        return true
    }

    private fun runWithRelease(connection: Connection, lockName: String, action: () -> Unit) {
        var actionFailure: Throwable? = null
        try {
            action()
        } catch (exception: Throwable) {
            actionFailure = exception
            throw exception
        } finally {
            try {
                release(connection, lockName)
            } catch (releaseFailure: Throwable) {
                actionFailure?.addSuppressed(releaseFailure) ?: throw releaseFailure
            }
        }
    }

    private fun tryAcquire(connection: Connection, lockName: String): Boolean =
        connection.prepareStatement("SELECT GET_LOCK(?, 0)").use { statement ->
            statement.setString(1, lockName)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "MySQL did not return a snapshot lock result." }
                val result = resultSet.getInt(1)
                check(!resultSet.wasNull()) { "MySQL could not evaluate the snapshot lock." }
                when (result) {
                    1 -> true
                    0 -> false
                    else -> error("Unexpected MySQL snapshot lock result: $result")
                }
            }
        }

    private fun release(connection: Connection, lockName: String) {
        connection.prepareStatement("SELECT RELEASE_LOCK(?)").use { statement ->
            statement.setString(1, lockName)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "MySQL did not return a snapshot lock release result." }
                val result = resultSet.getInt(1)
                check(!resultSet.wasNull() && result == 1) { "MySQL snapshot lock was not released." }
            }
        }
    }

    private companion object {
        const val MAX_LOCK_NAME_LENGTH = 64
    }
}
