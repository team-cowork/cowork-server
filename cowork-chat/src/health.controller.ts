import { Controller, Get } from '@nestjs/common';
import { Public } from './common/guard/public.decorator';

@Public()
@Controller('health')
export class HealthController {
    @Get()
    health() {
        return { status: 'UP' };
    }
}
