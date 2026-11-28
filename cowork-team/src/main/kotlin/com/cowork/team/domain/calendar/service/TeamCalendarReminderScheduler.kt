package com.cowork.team.domain.calendar.service

import com.cowork.team.domain.calendar.entity.TeamCalendarReminderCursor
import com.cowork.team.domain.calendar.entity.TeamCalendarReminderDelivery
import com.cowork.team.domain.calendar.model.CalendarReminderPolicy
import com.cowork.team.domain.calendar.repository.TeamCalendarEventRepository
import com.cowork.team.domain.calendar.repository.TeamCalendarReminderCursorRepository
import com.cowork.team.domain.calendar.repository.TeamCalendarReminderDeliveryRepository
import com.cowork.team.global.outbox.NotificationTriggerEvent
import com.cowork.team.global.outbox.OutboxWriter
import com.cowork.team.global.outbox.Topics
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import team.themoment.sdk.logging.logger.logger
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 1분마다 보낼 시각이 온 팀 일정 리마인더를 outbox로 `notification.trigger`에 발행한다.
 * 프로젝트 일정의 리마인더는 cowork-project가 보내므로 여기서는 팀 일정만 본다.
 * 서비스는 단일 인스턴스로 배포하므로 다중 인스턴스 잠금을 쓰지 않는다. 커서 행 잠금은 같은 인스턴스의 겹친 실행만 막는다.
 */
@Component
class TeamCalendarReminderScheduler(
    private val teamCalendarEventRepository: TeamCalendarEventRepository,
    private val teamCalendarReminderDeliveryRepository: TeamCalendarReminderDeliveryRepository,
    private val teamCalendarReminderCursorRepository: TeamCalendarReminderCursorRepository,
    private val teamCalendarAccessGuard: TeamCalendarAccessGuard,
    private val outboxWriter: OutboxWriter,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Scheduled(initialDelay = INTERVAL_MS, fixedDelay = INTERVAL_MS)
    fun publishDueReminders() {
        transaction.executeWithoutResult { publishInTransaction() }
    }

    private fun publishInTransaction() {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val cursor = teamCalendarReminderCursorRepository.findForUpdate(TeamCalendarReminderCursor.SINGLETON_ID)
            ?: teamCalendarReminderCursorRepository.save(TeamCalendarReminderCursor(checkedAt = now))
        // 오래 멈췄다면 지난 리마인더를 한꺼번에 보내지 않고 최근 것만 보낸다.
        val after = maxOf(cursor.checkedAt, now.minus(MAX_CATCH_UP))
        if (!after.isBefore(now)) return
        val candidates = teamCalendarEventRepository.findReminderCandidates(
            after,
            now.plus(CalendarReminderPolicy.MAX_LEAD).plusSeconds(1),
        )
        val members = mutableMapOf<Long, Set<Long>>()
        var published = 0
        candidates.forEach { event ->
            CalendarReminderPolicy.due(event.series(), event.reminders, after, now).forEach { due ->
                val alreadySent = teamCalendarReminderDeliveryRepository
                    .existsByEventIdAndOriginalStartAtAndStartAtAndMinutesBefore(
                        event.id,
                        due.occurrence.originalStartAt,
                        due.occurrence.startAt,
                        due.minutesBefore,
                    )
                if (alreadySent) return@forEach
                teamCalendarReminderDeliveryRepository.save(
                    TeamCalendarReminderDelivery(
                        eventId = event.id,
                        originalStartAt = due.occurrence.originalStartAt,
                        startAt = due.occurrence.startAt,
                        minutesBefore = due.minutesBefore,
                        deliveredAt = now,
                    ),
                )
                val teamMembers = members.getOrPut(event.teamId) { teamCalendarAccessGuard.memberIds(event.teamId) }
                val recipients = CalendarReminderPolicy.recipients(event.createdBy, event.attendeeUserIds, teamMembers)
                if (recipients.isEmpty()) return@forEach
                // 알림 서비스는 eventId로 중복을 거르므로 옮긴 발생분의 리마인더가 걸러지지 않게 실제 시작 시각도 넣는다.
                val notificationId = "team-calendar-reminder:${event.id}:${due.occurrence.originalStartAt}:" +
                    "${due.occurrence.startAt}:${due.minutesBefore}"
                outboxWriter.enqueue(
                    Topics.NOTIFICATION_TRIGGER,
                    notificationId,
                    NotificationTriggerEvent(
                        eventId = notificationId,
                        type = CALENDAR_EVENT_REMINDER,
                        targetUserIds = recipients,
                        data = mapOf(
                            "teamId" to event.teamId,
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
        teamCalendarReminderDeliveryRepository.deleteDeliveredBefore(now.minus(DELIVERY_RETENTION))
        if (published > 0) logger().info("Published {} team calendar reminders", published)
    }

    private companion object {
        const val INTERVAL_MS = 60_000L
        const val CALENDAR_EVENT_REMINDER = "CALENDAR_EVENT_REMINDER"
        val MAX_CATCH_UP: Duration = Duration.ofMinutes(10)

        /** 보낸 기록은 커서가 다시 볼 수 있는 기간보다 길게만 남기면 된다. */
        val DELIVERY_RETENTION: Duration = Duration.ofDays(2)
    }
}
