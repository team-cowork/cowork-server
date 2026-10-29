/**
`<@userId>` 형식의 멘션에서 중복을 뺀 사용자 ID 목록을 돌려준다.
*/
export function parseMentions(content: string): number[] {
	return [...new Set(content.matchAll(/<@(?<userId>\d+)>/gv).map(match => Number(match.groups?.userId)))];
}
