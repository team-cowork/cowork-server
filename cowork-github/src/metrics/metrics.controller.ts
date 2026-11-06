import {Controller as controller, Get as httpGet, Header as responseHeader} from '@nestjs/common';
import {collectDefaultMetrics, register} from 'prom-client';

@controller('metrics')
export class MetricsController {
	constructor() {
		// The global registry rejects duplicate default collectors when the module is created again.
		if (register.getSingleMetric('process_cpu_user_seconds_total') !== undefined) {
			return;
		}

		collectDefaultMetrics();
	}

	@httpGet()
	@responseHeader('Content-Type', register.contentType)
	async metrics(): Promise<string> {
		return register.metrics();
	}
}
