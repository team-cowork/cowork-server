import {plainToInstance} from 'class-transformer';
import {validate} from 'class-validator';
import {SendMessageDto} from './send-message.dto.js';

async function clientMessageIdErrors(clientMessageId: string) {
	const dto = plainToInstance(SendMessageDto, {content: 'hello', clientMessageId});
	const errors = await validate(dto);
	return errors.filter(error => error.property === 'clientMessageId');
}

describe('SendMessageDto clientMessageId', () => {
	it('시스템 메시지 키 접두사로 시작하면 거부한다', async () => {
		expect(await clientMessageIdErrors('system:github.repo.event:0:42:7')).toHaveLength(1);
	});

	it('일반 클라이언트 키는 허용한다', async () => {
		expect(await clientMessageIdErrors('6f1c2a4e-0d3b-4c1a-9b8e-2f7d5a6c3e10')).toHaveLength(0);
		expect(await clientMessageIdErrors('my-system:key')).toHaveLength(0);
	});
});
