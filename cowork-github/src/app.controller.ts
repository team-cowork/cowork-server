import {Controller as controller, Get as httpGet} from '@nestjs/common';

@controller()
export class AppController {
	@httpGet('health')
	health(): {status: string} {
		return {status: 'ok'};
	}
}
