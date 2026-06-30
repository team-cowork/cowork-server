import { Logger } from '@nestjs/common';

/**
 * 마이크로서비스 간 HTTP 통신을 위한 추상 기반 클라이언트.
 *
 * 서비스별 클라이언트는 이 클래스를 상속하여 {@link logger}와 {@link serviceName}을 구현해야 한다.
 * 공통 오류 처리 및 응답 파싱 유틸리티를 제공한다.
 */
export abstract class BaseHttpClient {
    protected abstract readonly logger: Logger;
    protected abstract readonly serviceName: string;

    /**
     * 일시적 장애(5xx, 네트워크 오류, 타임아웃)에 대해 exponential backoff 재시도를 수행하며 fetch를 실행한다.
     *
     * - 4xx 응답은 확정적 에러로 간주해 재시도하지 않고 즉시 반환한다.
     * - 5xx 응답 또는 네트워크/타임아웃 에러는 `maxRetries`회까지 재시도한다.
     * - 대기 시간: 1차 100ms, 2차 200ms, … (100ms × 2^(attempt-1))
     * - 매 시도마다 `AbortSignal.timeout(timeoutMs)`를 새로 생성하므로 타임아웃이 정확히 적용된다.
     *
     * @param url - 요청 대상 URL
     * @param init - fetch RequestInit (signal 제외)
     * @param timeoutMs - 단일 시도의 타임아웃(ms)
     * @param maxRetries - 최대 재시도 횟수 (기본 2 → 최대 3회 시도)
     * @returns fetch Response
     * @throws 마지막 시도에서도 네트워크/타임아웃 에러가 발생한 경우
     */
    protected async fetchWithRetry(
        url: string,
        init: Omit<RequestInit, 'signal'>,
        timeoutMs: number,
        maxRetries = 2,
    ): Promise<Response> {
        for (let attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                await new Promise<void>((resolve) => setTimeout(resolve, 100 * 2 ** (attempt - 1)));
                this.logger.warn(`${this.serviceName} retry ${attempt}/${maxRetries}`);
            }
            let res: Response;
            try {
                res = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
            } catch (err) {
                if (attempt === maxRetries) throw err;
                continue;
            }
            if (res.status >= 500 && attempt < maxRetries) {
                void res.body?.cancel().catch((err) => this.logger.warn(`${this.serviceName} response body cancel failed: ${String(err)}`));
                continue;
            }
            return res;
        }
        throw new Error(`${this.serviceName} fetchWithRetry: unreachable`);
    }

    /**
     * HTTP 오류 응답의 본문을 문자열로 읽는다.
     *
     * 응답 본문 읽기에 실패하더라도 예외를 던지지 않고 `null`을 반환한다.
     * 오류 메시지 구성 시 부재 정보로 인한 추가 예외를 방지하기 위한 설계이다.
     *
     * @param res - 오류 상태인 fetch Response 객체
     * @returns 응답 본문 문자열, 읽기 실패 시 `null`
     */
    protected async readErrorMessage(res: Response): Promise<string | null> {
        try {
            return await res.text();
        } catch (err) {
            this.logger.warn(`${this.serviceName} failed to read error response body: ${String(err)}`);
            return null;
        }
    }

    /**
     * HTTP 응답 본문을 JSON으로 파싱하여 반환한다.
     *
     * JSON 파싱에 실패할 경우 경고 로그를 남기고 예외를 던진다.
     * {@link readErrorMessage}와 달리 파싱 실패 시 복구하지 않으며 호출자에게 오류를 전파한다.
     *
     * @param res - 성공 상태인 fetch Response 객체
     * @returns 파싱된 JSON 객체 (타입 `T`로 캐스팅)
     * @throws {Error} 응답 본문이 유효한 JSON이 아닌 경우
     */
    protected async readJsonBody<T>(res: Response): Promise<T> {
        try {
            return await res.json() as T;
        } catch (err) {
            this.logger.warn(`${this.serviceName} failed to parse JSON response: ${String(err)}`);
            throw new Error(`${this.serviceName} failed to parse response body`, { cause: err });
        }
    }
}
