package com.cowork.project.domain.calendar.service

import com.cowork.project.domain.calendar.entity.ProjectCalendarReminderCursor
import com.cowork.project.domain.calendar.entity.ProjectCalendarReminderDelivery
import com.cowork.project.domain.calendar.model.CalendarReminderPolicy
import com.cowork.project.domain.calendar.repository.ProjectCalendarEventRepository
import com.cowork.project.domain.calendar.repository.ProjectCalendarReminderCursorRepository
import com.cowork.project.domain.calendar.repository.ProjectCalendarReminderDeliveryRepository
import com.cowork.project.domain.github.event.NotificationTriggerEvent
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.global.outbox.OutboxWriter
import com.cowork.project.global.projection.ProjectionReadinessState
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 1분마다 보낼 시각이 온 리마인더를 outbox로 `notification.trigger`에 발행한다.
 * 서비스는 단일 인스턴스로 배포하므로 다중 인스턴스 잠금을 쓰지 않는다. 커서 행 잠금은 같은 인스턴스의 겹친 실행만 막는다.
 */
@Component
class ProjectCalendarReminderScheduler(
    private val projectCalendarEventRepository: ProjectCalendarEventRepository,
    private val projectCalendarReminderDeliveryRepository: ProjectCalendarReminderDeliveryRepository,
    private val projectCalendarReminderCursorRepository: ProjectCalendarReminderCursorRepository,
    private val projectRepository: ProjectRepository,
    private val outboxWriter: OutboxWriter,
    private val projectionReadinessState: ProjectionReadinessState,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(ProjectCalendarReminderScheduler::class.java)
    private val transaction = TransactionTemplate(transactionManager)

    @Scheduled(initialDelay = INTERVAL_MS, fixedDelay = INTERVAL_MS)
    fun publishDueReminders() {
        // 받는 사람은 읽기 권한 projection으로 거르므로 projection이 준비되기 전에는 기다린다.
        if (!projectionReadinessState.isReady()) return
        transaction.executeWithoutResult { publishInTransaction() }
    }

    private fun publishInTransaction() {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val cursor = projectCalendarReminderCursorRepository.findForUpdate(ProjectCalendarReminderCursor.SINGLETON_ID)
            ?: projectCalendarReminderCursorRepository.save(ProjectCalendarReminderCursor(checkedAt = now))
        // 오래 멈췄다면 지난 리마인더를 한꺼번에 보내지 않고 최근 것만 보낸다.
        val after = maxOf(cursor.checkedAt, now.minus(MAX_CATCH_UP))
        if (!after.isBefore(now)) return
        val candidates = projectCalendarEventRepository.findReminderCandidates(
            after,
            now.plus(CalendarReminderPolicy.MAX_LEAD).plusSeconds(1),
        )
        val readers = mutableMapOf<Long, Set<Long>>()
        var published = 0
        candidates.forEach { event ->
            CalendarReminderPolicy.due(event.series(), event.reminders, after, now).forEach { due ->
                val alreadySent = projectCalendarReminderDeliveryRepository
                    .existsByEventIdAndOriginalStartAtAndStartAtAndMinutesBeforeEquals(
                        event.id,
                        due.occurrence.originalStartAt,
                        due.occurrence.startAt,
                        due.minutesBefore,
                    )
                if (alreadySent) return@forEach
                projectCalendarReminderDeliveryRepository.save(
                    ProjectCalendarReminderDelivery(
                        eventId = event.id,
                        originalStartAt = due.occurrence.originalStartAt,
                        startAt = due.occurrence.startAt,
                        minutesBefore = due.minutesBefore,
                        deliveredAt = now,
                    ),
                )
                val projectReaders = readers.getOrPut(event.projectId) {
                    projectRepository.findReadableUserIds(event.projectId).toSet()
                }
                val recipients = CalendarReminderPolicy.recipients(
                    event.createdBy,
                    event.attendeeUserIds,
                    projectReaders,
                )
                if (recipients.isEmpty()) return@forEach
                // 알림 서비스는 eventId로 중복을 거르므로 옮긴 발생분의 리마인더가 걸러지지 않게 실제 시작 시각도 넣는다.
                val notificationId = "calendar-reminder:${event.id}:${due.occurrence.originalStartAt}:" +
                    "${due.occurrence.startAt}:${due.minutesBefore}"
                outboxWriter.enqueue(
                    NOTIFICATION_TOPIC,
                    notificationId,
                    NotificationTriggerEvent(
                        eventId = notificationId,
                        type = CALENDAR_EVENT_REMINDER,
                        targetUserIds = recipients,
                        data = mapOf(
                            "teamId" to event.teamId,
                            "projectId" to event.projectId,
                            "calendarEventId" to event.id,
                            "title" to (due.occurrence.title ?: event.title),
                            "startAt" to due.occurrence.startAt.toString(),
                            "endAt" to due.occurrence.endAt.toString(),
                            "allDay" to event.allDay,
                            "timeZone" to event.timeZone,
                            "minutesBefore" to due.minutesBefore,
                        ),
                    ),
                )
                published++
            }
        }
        cursor.checkedAt = now
        projectCalendarReminderDeliveryRepository.deleteDeliveredBefore(now.minus(DELIVERY_RETENTION))
        if (published > 0) log.info("Published {} calendar reminders", published)
    }

    private companion object {
        const val INTERVAL_MS = 60_000L
        const val NOTIFICATION_TOPIC = "notification.trigger"
        const val CALENDAR_EVENT_REMINDER = "CALENDAR_EVENT_REMINDER"
        val MAX_CATCH_UP: Duration = Duration.ofMinutes(10)

        /** 보낸 기록은 커서가 다시 볼 수 있는 기간보다 길게만 남기면 된다. */
        val DELIVERY_RETENTION: Duration = Duration.ofDays(2)
    }
}
