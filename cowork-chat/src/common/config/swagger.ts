import {type INestApplication} from '@nestjs/common';
import {type DocumentBuilder, SwaggerModule} from '@nestjs/swagger';

export function createChatApiDocument(app: INestApplication, builder: DocumentBuilder) {
	const config = builder
		.setTitle('cowork Chat API')
		.setDescription(
			'메시지는 REST API로 보내고 Socket.IO의 /chat 네임스페이스에서 수신합니다. '
			+ 'WebSocket 연결 경로는 /ws/chat입니다. 인증에 실패하면 connect_error를 받습니다. '
			+ '[WebSocket 이벤트 명세](/api/chat/asyncapi.json)에서 채널 참가, 입력 상태, 메시지 변경 이벤트를 확인할 수 있습니다.\n\n'
			+ '팀 메시지와 채널을 함께 검색하려면 POST /api/chat/graphql의 unifiedSearch를 사용합니다.',
		)
		.addServer('/api/chat', 'Gateway')
		.addBearerAuth(
			{
				type: 'http',
				scheme: 'bearer',
				bearerFormat: 'JWT',
				description: 'Cowork 액세스 토큰',
			},
			'bearer',
		)
		.addSecurityRequirements('bearer')
		.build();

	const document = SwaggerModule.createDocument(app, config);
	const webhook = document.paths['/chat/webhooks/{executionId}/messages'];
	if (webhook !== undefined) {
		document.paths['/chat/webhooks/{executionId}/{signature}/messages'] = webhook;
		delete document.paths['/chat/webhooks/{executionId}/messages'];
	}

	// Only the liveness endpoint is routed at /api/chat/health.
	delete document.paths['/health/ready'];
	if (document.paths['/health']?.get) {
		document.paths['/health'].get.security = [];
	}

	return document;
}
