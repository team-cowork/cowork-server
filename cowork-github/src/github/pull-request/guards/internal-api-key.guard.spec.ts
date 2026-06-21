import { ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { InternalApiKeyGuard } from './internal-api-key.guard';
import { AppConfigService } from '../../../config/app-config.service';

describe('InternalApiKeyGuard', () => {
  let guard: InternalApiKeyGuard;
  let config: { internalApiKey: string };

  const createContext = (headers: Record<string, string>): ExecutionContext =>
    ({
      switchToHttp: () => ({
        getRequest: () => ({ headers }),
      }),
    }) as unknown as ExecutionContext;

  beforeEach(() => {
    config = { internalApiKey: 'secret-key' };
    guard = new InternalApiKeyGuard(config as unknown as AppConfigService);
  });

  it('올바른 키가 헤더에 있으면 통과한다', () => {
    const context = createContext({ 'x-internal-api-key': 'secret-key' });
    expect(guard.canActivate(context)).toBe(true);
  });

  it('키가 없으면 UnauthorizedException을 던진다', () => {
    const context = createContext({});
    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });

  it('키가 다르면 UnauthorizedException을 던진다', () => {
    const context = createContext({ 'x-internal-api-key': 'wrong-key' });
    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });

  it('키 길이가 다르면 UnauthorizedException을 던진다', () => {
    const context = createContext({ 'x-internal-api-key': 'short' });
    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });
});
