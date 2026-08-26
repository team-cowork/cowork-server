package com.cowork.project.domain.github.event

import com.cowork.project.global.consumer.Topics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * project가 직접 트리거하는 GitHub 쓰기 작업을 cowork-github-app에 Kafka로 발행한다.
 * REST(Feign) 동기 호출을 대체하며, 결과는 이 흐름에서 project로 회신되지 않는다(fire-and-forget).
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
        kafkaTemplate.send(topic, key, event)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error("GitHub 커맨드 발행 실패 [topic={}, key={}]", topic, key, ex)
                } else {
                    log.info("GitHub 커맨드 발행 성공 [topic={}, key={}, offset={}]", topic, key, result.recordMetadata.offset())
                }
            }
    }
}
