/**
 * GitHub 저장소 활동(push/issues/pull_request) 이벤트 페이로드.
 *
 * 외부 `github-app` 서비스가 발행하고 `cowork-chat`이 소비한다.
 * 저장소가 연결된 프로젝트의 GitHub 알림 채널에 `summary`를 SYSTEM 메시지로 게시한다.
 */
export interface GithubRepoEvent {
    /** GitHub 리포지터리 소유자 (유저명 또는 조직명) */
    owner: string;
    repo: string;
    /** GitHub 이벤트 종류 (예: `push`, `issues`, `pull_request`) */
    eventType: string;
    /** 이벤트 세부 액션 (예: `opened`, `closed`) */
    action: string;
    /** 사람이 읽을 한글 요약 텍스트 */
    summary: string;
}
