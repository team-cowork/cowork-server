package com.cowork.project.domain.calendar.event

import com.cowork.project.domain.calendar.entity.ProjectCalendarCategory
import com.cowork.project.domain.calendar.entity.ProjectCalendarEvent
import com.cowork.project.domain.project.event.nextMonotonicStateVersion
import com.cowork.project.global.outbox.OutboxWriter
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ProjectCalendarEventPublisher(private val entityManager: EntityManager, private val outboxWriter: OutboxWriter) {

    /** 일정의 현재 상태를 새 버전으로 발행한다. 삭제한 일정은 DELETE로 나간다. */
    fun publishChanged(
        event: ProjectCalendarEvent,
        category: ProjectCalendarCategory?,
        occurredAt: Instant = Instant.now(),
    ) {
        event.stateOccurredAt = nextMonotonicStateVersion(event.stateOccurredAt, occurredAt)
        publish(event, category, snapshot = false)
    }

    fun publishSnapshot(event: ProjectCalendarEvent, category: ProjectCalendarCategory?) =
        publish(event, category, snapshot = true)

    private fun publish(event: ProjectCalendarEvent, category: ProjectCalendarCategory?, snapshot: Boolean) {
        entityManager.flush()
        val recurrence = event.recurrence
        val payload = ProjectCalendarEventStatePayload(
            eventType = if (event.deleted) CalendarStateEventType.DELETE else CalendarStateEventType.UPSERT,
            eventId = event.id,
            projectId = event.projectId,
            teamId = event.teamId,
            createdBy = event.createdBy,
            title = event.title,
            description = event.description,
            startAt = event.startAt,
            endAt = event.endAt,
            allDay = event.allDay,
            timeZone = event.timeZone,
            recurrence = recurrence?.let {
                ProjectCalendarEventStatePayload.Recurrence(
                    it.frequency.name,
                    it.interval,
                    it.byDay.sorted().map { day -> day.name },
                    it.until,
                    it.count,
                )
            },
            exceptions = event.exceptionModels().map {
                ProjectCalendarEventStatePayload.OccurrenceException(
                    it.originalStartAt,
                    it.cancelled,
                    it.title,
                    it.description,
                    it.startAt,
                    it.endAt,
                )
            },
            category = category?.takeIf { it.id == event.categoryId }?.let {
                ProjectCalendarEventStatePayload.Category(it.id, it.name, it.colorHex)
            },
            attendeeUserIds = event.attendeeUserIds.sorted(),
            reminderMinutes = event.reminders,
            occurredAt = event.stateOccurredAt,
            snapshot = snapshot,
        )
        outboxWriter.enqueue(PROJECT_CALENDAR_EVENT_STATE_TOPIC, event.id.toString(), payload)
    }
}
