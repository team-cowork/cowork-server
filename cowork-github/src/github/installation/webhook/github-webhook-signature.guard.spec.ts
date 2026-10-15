import { ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { createHmac } from 'crypto';
import { GithubWebhookSignatureGuard } from './github-webhook-signature.guard';
import { AppConfigService } from '../../../config/app-config.service';

describe('GithubWebhookSignatureGuard', () => {
  let guard: GithubWebhookSignatureGuard;
  let config: { githubWebhookSecret: string };

  const sign = (secret: string, body: string): string =>
    'sha256=' + createHmac('sha256', secret).update(body).digest('hex');

  const createContext = (
    headers: Record<string, string>,
    rawBody?: Buffer,
  ): ExecutionContext =>
    ({
      switchToHttp: () => ({
        getRequest: () => ({ headers, rawBody }),
      }),
    }) as unknown as ExecutionContext;

  beforeEach(() => {
    config = { githubWebhookSecret: 'webhook-secret' };
    guard = new GithubWebhookSignatureGuard(
      config as unknown as AppConfigService,
    );
  });

  it('올바른 서명이면 통과한다', () => {
    const rawBody = Buffer.from(JSON.stringify({ hello: 'world' }));
    const signature = sign('webhook-secret', rawBody.toString());
    const context = createContext(
      { 'x-hub-signature-256': signature },
      rawBody,
    );

    expect(guard.canActivate(context)).toBe(true);
  });

  it('서명이 없으면 UnauthorizedException을 던진다', () => {
    const rawBody = Buffer.from(JSON.stringify({ hello: 'world' }));
    const context = createContext({}, rawBody);

    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });

  it('rawBody가 없으면 UnauthorizedException을 던진다', () => {
    const signature = sign('webhook-secret', '{}');
    const context = createContext({ 'x-hub-signature-256': signature });

    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });

  it('시크릿이 다르면 UnauthorizedException을 던진다', () => {
    const rawBody = Buffer.from(JSON.stringify({ hello: 'world' }));
    const signature = sign('wrong-secret', rawBody.toString());
    const context = createContext(
      { 'x-hub-signature-256': signature },
      rawBody,
    );

    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });

  it('본문이 변조되면 UnauthorizedException을 던진다', () => {
    const rawBody = Buffer.from(JSON.stringify({ hello: 'world' }));
    const signature = sign('webhook-secret', rawBody.toString());
    const tamperedBody = Buffer.from(JSON.stringify({ hello: 'tampered' }));
    const context = createContext(
      { 'x-hub-signature-256': signature },
      tamperedBody,
    );

    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });

  it('서명 길이가 다르면 UnauthorizedException을 던진다', () => {
    const rawBody = Buffer.from(JSON.stringify({ hello: 'world' }));
    const context = createContext(
      { 'x-hub-signature-256': 'sha256=short' },
      rawBody,
    );

    expect(() => guard.canActivate(context)).toThrow(UnauthorizedException);
  });
});
