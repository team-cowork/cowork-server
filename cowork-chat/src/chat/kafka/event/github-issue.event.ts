/**
 * GitHub 이슈 생성을 요청하는 Kafka 이벤트 페이로드.
 *
 * chat 서비스가 발행하고 github-integration 서비스가 소비한다.
 * 처리 결과는 {@link GithubIssueResultEvent}로 역방향 토픽에 응답된다.
 */
export interface GithubIssueCreateEvent {
    channelId: number;
    teamId: number;
    projectId: number;
    /** GitHub 리포지터리 소유자 (유저명 또는 조직명) */
    owner: string;
    repo: string;
    title: string;
    /** 이슈 본문(Markdown). 생략 시 빈 본문으로 생성 */
    body?: string;
    /** 이슈 생성을 요청한 사용자 ID; 알림 대상 특정에 사용 */
    requesterId: number;
}

/**
 * GitHub 이슈 생성 처리 결과를 전달하는 Kafka 이벤트 페이로드.
 *
 * github-integration 서비스가 발행하고 chat 서비스가 소비한다.
 * `success`가 false인 경우 `error` 필드에 실패 사유가 담긴다.
 */
export interface GithubIssueResultEvent {
    channelId: number;
    teamId: number;
    projectId?: number;
    /** 이슈 생성 성공 여부 */
    success: boolean;
    /** 생성된 이슈의 GitHub URL; `success`가 true일 때만 존재 */
    issueUrl?: string;
    /** 생성된 이슈 번호; `success`가 true일 때만 존재 */
    issueNumber?: number;
    /** 실패 사유; `success`가 false일 때만 존재 */
    error?: string;
}
