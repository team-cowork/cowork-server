const lastSentAt = new Map<string, number>();

/**
동일 key에 대해 cooldownMs가 지나지 않았으면 false를 반환해 알림 발송을 막는다.
*/
export function shouldAlert(key: string, cooldownMs: number): boolean {
	const now = Date.now();
	const last = lastSentAt.get(key);
	if (last !== undefined && now - last < cooldownMs) {
		return false;
	}

	lastSentAt.set(key, now);
	return true;
}
