import {type INestApplication} from '@nestjs/common';
import {type DocumentBuilder, SwaggerModule} from '@nestjs/swagger';

export function createChatApiDocument(app: INestApplication, builder: DocumentBuilder) {
	const config = builder
		.setOpenAPIVersion('3.2.1')
		.setTitle('cowork Chat API')
		.setDescription('채널과 개인 대화의 메시지, 첨부 파일, 읽음 상태와 메시지 검색을 관리합니다.')
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

	const attachmentPath = '/chat/channels/{channelId}/meeting-notes/{noteId}/attachments/download-url';
	const meetingNoteAttachment = document.paths[attachmentPath];
	if (meetingNoteAttachment !== undefined) {
		meetingNoteAttachment.servers = [{url: '/api/channel', description: 'Gateway'}];
		document.paths[attachmentPath.replace('/chat/', '/')] = meetingNoteAttachment;
		delete document.paths['/chat/channels/{channelId}/meeting-notes/{noteId}/attachments/download-url'];
	}

	delete document.paths['/metrics'];

	return document;
}
