import { Logger } from '@nestjs/common';

export abstract class BaseHttpClient {
    protected abstract readonly logger: Logger;
    protected abstract readonly serviceName: string;

    protected async readErrorMessage(res: Response): Promise<string | null> {
        try {
            return await res.text();
        } catch (err) {
            this.logger.warn(`${this.serviceName} 오류 응답 본문 읽기 실패: ${String(err)}`);
            return null;
        }
    }

    protected async readJsonBody<T>(res: Response): Promise<T> {
        try {
            return await res.json() as T;
        } catch (err) {
            this.logger.warn(`${this.serviceName} JSON 응답 파싱 실패: ${String(err)}`);
            throw new Error(`${this.serviceName} 응답 본문 파싱에 실패했습니다`);
        }
    }
}
