/**
 * 채팅 슬래시 커맨드로 시작한 GitHub 이슈 생성 요청을 project에 위임하는 Kafka 커맨드 페이로드.
 *
 * chat 서비스가 발행하고 project 서비스가 소비한다.
 * project는 프로젝트 수정 권한(OWNER/EDITOR 또는 팀 OWNER/ADMIN)을 검증한 뒤
 * 통과하면 `github.issue.create`로 실제 이슈 생성 커맨드를 발행하고,
 * 결과(수락/거부)는 {@link ChatGithubIssueCreateResult}로 역방향 토픽에 응답한다.
 */
export interface ChatGithubIssueCreateCommand {
    /** 커맨드 고유 식별자(UUID). 결과 상관관계 및 중복 처리 방지에 사용 */
    operationId: string;
    /** 중복 발행 방지를 위한 멱등성 키(UUID) */
    idempotencyKey: string;
    projectId: number;
    /** 요청이 발생한 채널 ID */
    channelId: number;
    /** 채널이 속한 팀 ID(채널-프로젝트 팀 경계 검증에 사용) */
    teamId: number;
    /** 이슈 생성을 요청한 사용자 ID */
    requesterId: number;
    title: string;
    /** 이슈 본문(Markdown). 없으면 null */
    body: string | null;
    occurredAt: string;
}

export type ChatGithubIssueCreateResultStatus = 'ACCEPTED' | 'REJECTED';

export interface ChatGithubIssueCreateResultError {
    code: string;
    message: string;
}

/**
 * project가 채팅발 GitHub 이슈 생성 커맨드의 권한 검증 결과를 전달하는 Kafka 이벤트 페이로드.
 *
 * `status`가 `REJECTED`일 때만 `error`가 채워지며, chat은 이 경우에만 거부 시스템 메시지를 렌더링한다.
 * `ACCEPTED`는 권한 검증만 통과했다는 의미이며, 실제 이슈 생성 성공/실패는
 * 기존 `github.issue.result` 토픽으로 별도 전달된다.
 */
export interface ChatGithubIssueCreateResult {
    operationId: string;
    idempotencyKey: string;
    channelId: number;
    teamId: number;
    projectId: number;
    requesterId: number;
    status: ChatGithubIssueCreateResultStatus;
    error: ChatGithubIssueCreateResultError | null;
    occurredAt: string;
}
