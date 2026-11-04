/**
`items`를 앞에서부터 하나씩 처리하고, 앞 작업이 끝난 뒤 다음 작업을 시작한다.
GitHub secondary rate limit처럼 요청을 동시에 보내면 안 되는 곳에서 쓴다. 작업이 실패하면 남은 항목은 처리하지 않는다.
*/
export async function forEachSequential<T>(items: readonly T[], action: (item: T) => Promise<void>, index = 0): Promise<void> {
	if (index >= items.length) {
		return;
	}

	await action(items[index]);
	await forEachSequential(items, action, index + 1);
}
