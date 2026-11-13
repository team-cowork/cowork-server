import {type INestApplication} from '@nestjs/common';
import {DocumentBuilder, SwaggerModule} from '@nestjs/swagger';
import {InstallationModule} from '../github/installation/installation.module.js';
import {PullRequestModule} from '../github/pull-request/pull-request.module.js';

export function setupSwagger(app: INestApplication, version: string) {
	const publicDocument = SwaggerModule.createDocument(app, new DocumentBuilder()
		.setTitle('cowork GitHub API')
		.setDescription('GitHub App 설치 결과와 웹훅 이벤트를 수신합니다.')
		.setVersion(version)
		.addServer('/api/github', 'Gateway')
		.addTag('installation', 'GitHub App 설치 결과를 cowork 팀에 연결합니다.')
		.addTag('webhook', 'GitHub 이벤트를 수신하고 cowork에 반영합니다.')
		.build(), {include: [InstallationModule], autoTagControllers: false});

	SwaggerModule.setup('api', app, publicDocument, {jsonDocumentUrl: 'v3/api-docs', ui: false, raw: ['json']});

	const internalDocument = SwaggerModule.createDocument(app, new DocumentBuilder()
		.setTitle('cowork GitHub Internal API')
		.setDescription('PR 실시간 상태 조회/머지/승인 API입니다. 내부 연결과 X-Internal-Api-Key가 필요합니다.')
		.setVersion(version)
		.addServer('/', 'GitHub 모듈 직접 접속')
		.addApiKey({type: 'apiKey', in: 'header', name: 'X-Internal-Api-Key'}, 'internal-api-key')
		.addTag('pull-request', 'PR의 실시간 상태를 조회하고 머지/승인합니다.')
		.build(), {include: [PullRequestModule], autoTagControllers: false});

	SwaggerModule.setup('api/internal', app, internalDocument, {jsonDocumentUrl: 'v3/api-docs/internal', raw: ['json']});
}
