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
		.addTag('Chat', '채널과 프로젝트의 메시지, 첨부 파일, 읽음 상태를 관리합니다.')
		.addTag('DM', '개인 대화 목록을 조회하고 대화를 숨깁니다.')
		.addTag('Search', '팀 내 메시지를 검색합니다.')
		.addTag('block', '사용자 차단 목록을 조회하고 차단을 설정하거나 해제합니다.')
		.addTag('웹훅 수신', '웹훅으로 받은 메시지를 채널에 전송합니다.')
		.addTag('Chat Admin', '메시지 검색 색인과 격리된 메시지를 관리합니다.')
		.addTag('Projection Admin', '데이터 동기화 상태를 조회하고 재구축을 요청합니다.')
		.addBearerAuth(
			{
				type: 'http',
				scheme: 'bearer',
				bearerFormat: 'JWT',
				description: 'cowork 액세스 토큰',
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

	delete document.paths['/health'];
	delete document.paths['/health/ready'];
	delete document.paths['/metrics'];

	return document;
}
