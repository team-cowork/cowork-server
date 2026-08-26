package com.cowork.project.domain.github.event

import com.cowork.project.global.consumer.Topics
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException
import java.util.concurrent.TimeUnit

private const val SEND_TIMEOUT_SECONDS = 5L

/**
 * project가 직접 트리거하는 GitHub 쓰기 작업을 cowork-github-app에 Kafka로 발행한다.
 * REST(Feign) 동기 호출을 대체하며, 처리 결과(성공/실패)는 이 흐름에서 project로 회신되지 않는다(fire-and-forget).
 * 다만 발행 자체(브로커에 적재)는 동기로 확인한다 — 그래야 호출자가 202를 받았을 때 "적재는 됐다"를 보장할 수 있다.
 */
@Component
class GithubActionCommandPublisher(private val kafkaTemplate: KafkaTemplate<String, Any>) {
    private val log = LoggerFactory.getLogger(GithubActionCommandPublisher::class.java)

    fun publishIssueCreate(command: GithubIssueCreateCommand) =
        send(Topics.GITHUB_ISSUE_CREATE, "${command.owner}/${command.repo}", command)

    fun publishPullRequestMerge(command: GithubPullRequestActionCommand) =
        send(Topics.GITHUB_PR_MERGE, "${command.owner}/${command.repo}", command)

    fun publishPullRequestApprove(command: GithubPullRequestActionCommand) =
        send(Topics.GITHUB_PR_APPROVE, "${command.owner}/${command.repo}", command)

    private fun send(topic: String, key: String, event: Any) {
        try {
            val result = kafkaTemplate.send(topic, key, event).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            log.info("GitHub 커맨드 발행 성공 [topic={}, key={}, offset={}]", topic, key, result.recordMetadata.offset())
        } catch (ex: Exception) {
            log.error("GitHub 커맨드 발행 실패 [topic={}, key={}]", topic, key, ex)
            throw ExpectedException("GitHub 연동 서버와 통신할 수 없습니다.", HttpStatus.BAD_GATEWAY)
        }
    }
}
