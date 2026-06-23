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
            this.logger.warn(`${this.serviceName} 오류 응답 본문 읽기 실패: ${String(err)}`);
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
            this.logger.warn(`${this.serviceName} JSON 응답 파싱 실패: ${String(err)}`);
            throw new Error(`${this.serviceName} 응답 본문 파싱에 실패했습니다`, { cause: err });
        }
    }
}
