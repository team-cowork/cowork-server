import { ArgumentsHost, ForbiddenException } from '@nestjs/common';
import { GlobalExceptionFilter } from './global-exception.filter';

const makeHttpHost = () => {
    const json = jest.fn();
    const status = jest.fn(() => ({ json }));
    const response = { status };
    const host = {
        getType: jest.fn(() => 'http'),
        switchToHttp: jest.fn(() => ({ getResponse: () => response })),
    };
    return { host: host as unknown as ArgumentsHost, status, json };
};

describe('GlobalExceptionFilter', () => {
    let filter: GlobalExceptionFilter;

    beforeEach(() => {
        filter = new GlobalExceptionFilter();
    });

    it('HttpException은 원래 statusCode와 응답 바디를 그대로 내려준다', () => {
        const { host, status, json } = makeHttpHost();

        filter.catch(new ForbiddenException('채널 접근 권한이 없습니다'), host);

        expect(status).toHaveBeenCalledWith(403);
        expect(json).toHaveBeenCalledWith(
            expect.objectContaining({ statusCode: 403, message: '채널 접근 권한이 없습니다' }),
        );
    });

    it('일반 Error는 500과 일반화된 메시지로 응답하고 원본 메시지를 노출하지 않는다', () => {
        const { host, status, json } = makeHttpHost();

        filter.catch(new Error('secret internal detail'), host);

        expect(status).toHaveBeenCalledWith(500);
        expect(json).toHaveBeenCalledWith({ statusCode: 500, message: '서버 오류가 발생했습니다' });
    });

    it('WebSocket 컨텍스트는 client.emit(\'exception\', ...)으로 위임한다', () => {
        const emit = jest.fn();
        const host = {
            getType: jest.fn(() => 'ws'),
            switchToWs: jest.fn(() => ({
                getClient: () => ({ emit }),
                getPattern: () => 'join',
                getData: () => ({ channelId: 1 }),
            })),
        } as unknown as ArgumentsHost;

        filter.catch(new Error('unexpected'), host);

        expect(emit).toHaveBeenCalledWith('exception', expect.objectContaining({ status: 'error' }));
    });

    it('http/ws가 아닌 컨텍스트(GraphQL 등)는 그대로 rethrow해 자체 처리 파이프라인에 맡긴다', () => {
        const host = {
            getType: jest.fn(() => 'graphql'),
        } as unknown as ArgumentsHost;
        const error = new Error('resolver failed');

        expect(() => filter.catch(error, host)).toThrow(error);
    });
});
