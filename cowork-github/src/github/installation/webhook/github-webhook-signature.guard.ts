import {
  CanActivate,
  ExecutionContext,
  Injectable,
  RawBodyRequest,
  UnauthorizedException,
} from '@nestjs/common';
import { createHmac, timingSafeEqual } from 'crypto';
import { Request } from 'express';
import { AppConfigService } from '../../../config/app-config.service';

@Injectable()
export class GithubWebhookSignatureGuard implements CanActivate {
  constructor(private readonly config: AppConfigService) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context
      .switchToHttp()
      .getRequest<RawBodyRequest<Request>>();
    const signature = request.headers['x-hub-signature-256'];
    const rawBody = request.rawBody;

    if (typeof signature !== 'string' || !rawBody) {
      throw new UnauthorizedException('Missing signature or raw body');
    }

    const expected =
      'sha256=' +
      createHmac('sha256', this.config.githubWebhookSecret)
        .update(rawBody)
        .digest('hex');
    const sigBuffer = Buffer.from(signature);
    const expectedBuffer = Buffer.from(expected);

    if (
      sigBuffer.length !== expectedBuffer.length ||
      !timingSafeEqual(sigBuffer, expectedBuffer)
    ) {
      throw new UnauthorizedException('Invalid webhook signature');
    }
    return true;
  }
}
