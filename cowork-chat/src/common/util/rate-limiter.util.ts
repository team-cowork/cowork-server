/**
 * 사용자별 슬라이딩 윈도우 rate limiter.
 * 파일 업로드(`MinioService`)에서 쓰던 in-memory 카운팅 패턴을 재사용 가능하게 추출한 것이다.
 * 단일 프로세스 내 in-memory 상태이므로 인스턴스가 여러 개(수평 확장)면 사용자별 한도가 인스턴스 수만큼 늘어난다.
 */
export class SlidingWindowRateLimiter {
    private readonly buckets = new Map<number, number[]>();
    private readonly cleanupTimer: ReturnType<typeof setInterval>;

    constructor(
        private readonly windowMs: number,
        private readonly maxRequests: number,
    ) {
        this.cleanupTimer = setInterval(() => this.cleanupStaleEntries(), this.windowMs);
        this.cleanupTimer.unref?.();
    }

    /**
     * @param key - 사용자 ID 등 요청 주체 식별자
     * @returns 시간창 내 허용 한도를 초과하지 않았으면 `true`(요청 1건 소비), 초과했으면 `false`
     */
    tryAcquire(key: number): boolean {
        const now = Date.now();
        const windowStart = now - this.windowMs;
        const recentRequests = (this.buckets.get(key) ?? []).filter((requestedAt) => requestedAt > windowStart);

        if (recentRequests.length >= this.maxRequests) {
            this.buckets.set(key, recentRequests);
            return false;
        }

        recentRequests.push(now);
        this.buckets.set(key, recentRequests);
        return true;
    }

    dispose(): void {
        clearInterval(this.cleanupTimer);
    }

    private cleanupStaleEntries(): void {
        const windowStart = Date.now() - this.windowMs;
        for (const [key, timestamps] of this.buckets) {
            if (timestamps.every((requestedAt) => requestedAt <= windowStart)) {
                this.buckets.delete(key);
            }
        }
    }
}
