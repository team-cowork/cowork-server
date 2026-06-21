import {
  CanActivate,
  ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';
import { timingSafeEqual } from 'crypto';
import { Request } from 'express';
import { AppConfigService } from '../../../config/app-config.service';

@Injectable()
export class InternalApiKeyGuard implements CanActivate {
  constructor(private readonly config: AppConfigService) {}

  canActivate(context: ExecutionContext): boolean {
    const request = context.switchToHttp().getRequest<Request>();
    const provided = request.headers['x-internal-api-key'];

    if (typeof provided !== 'string' || !this.matches(provided)) {
      throw new UnauthorizedException('Invalid internal API key');
    }
    return true;
  }

  private matches(provided: string): boolean {
    const expected = this.config.internalApiKey;
    const providedBuffer = Buffer.from(provided);
    const expectedBuffer = Buffer.from(expected);

    if (providedBuffer.length !== expectedBuffer.length) return false;
    return timingSafeEqual(providedBuffer, expectedBuffer);
  }
}
