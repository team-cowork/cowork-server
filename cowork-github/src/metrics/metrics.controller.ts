import {getHeapStatistics} from 'node:v8';
import {Controller, Get, Header} from '@nestjs/common';
import {collectDefaultMetrics, Gauge, register} from 'prom-client';

@Controller('metrics')
export class MetricsController {
    constructor() {
        // The global registry rejects duplicate default collectors when the module is created again.
        if (register.getSingleMetric('process_cpu_user_seconds_total') !== undefined) {
            return;
        }

        collectDefaultMetrics();
        // The heap alert needs the V8 limit; nodejs_heap_size_total_bytes is only the currently reserved heap.
        new Gauge({
            name: 'cowork_nodejs_heap_size_limit_bytes',
            help: 'V8 heap size limit in bytes.',
        }).set(getHeapStatistics().heap_size_limit);
    }

    @Get()
    @Header('Content-Type', register.contentType)
    async metrics(): Promise<string> {
        return register.metrics();
    }
}
