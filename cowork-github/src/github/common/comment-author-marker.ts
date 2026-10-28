// GitHub App 설치 토큰으로 댓글을 작성하면 GitHub상 작성자는 항상 이 앱(bot) 계정으로 보인다.
// 실제 작성자를 보존하기 위해 본문 끝에 렌더링되지 않는 HTML 주석으로 마커를 남기고,
// 읽을 때 이 마커를 읽어 author를 실제 작성자로 덮어쓴다.
// 댓글 생성 명령의 operationId도 같은 마커에 남겨, 결과를 모르는 재처리가 이미 만든 댓글을 찾는 근거로 쓴다.
const AUTHOR_MARKER_PATTERN = /<!--\s*cowork:author=(?<author>\S+)(?:\s+operation=(?<operationId>\S+))?\s*-->\s*$/v;

export function stripTrailingNewlines(text: string): string {
    let end = text.length;
    while (end > 0 && text[end - 1] === '\n') {
        end--;
    }

    return text.slice(0, end);
}

export function extractCommentAuthor(body: string, fallbackLogin: string): {
    author: string;
    body: string;
    operationId?: string;
} {
    const match = AUTHOR_MARKER_PATTERN.exec(body);
    if (match?.groups === undefined) {
        return {author: fallbackLogin, body};
    }

    return {
        author: match.groups.author,
        body: stripTrailingNewlines(body.slice(0, match.index)),
        ...(match.groups.operationId !== undefined && {operationId: match.groups.operationId}),
    };
}

export function appendAuthorMarker(body: string, username: string, operationId?: string): string {
    const match = AUTHOR_MARKER_PATTERN.exec(body);
    const stripped = match === null
        ? body
        : stripTrailingNewlines(body.slice(0, match.index));
    const operation = operationId === undefined ? '' : ` operation=${operationId}`;
    return `${stripped}\n\n<!-- cowork:author=${username}${operation} -->`;
}
