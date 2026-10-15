import {Controller, Get, Header} from '@nestjs/common';
import {collectDefaultMetrics, register} from 'prom-client';

@Controller('metrics')
export class MetricsController {
    constructor() {
        // The global registry rejects duplicate default collectors when the module is created again.
        if (register.getSingleMetric('process_cpu_user_seconds_total') === undefined) {
            collectDefaultMetrics();
        }
    }

    @Get()
    @Header('Content-Type', register.contentType)
    async metrics(): Promise<string> {
        return register.metrics();
    }
}
