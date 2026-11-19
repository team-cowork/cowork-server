import mongoose from 'mongoose';
import {messageSchema} from './message.schema.js';

const MESSAGE_MODEL = mongoose.model('MessageSchemaSpec', messageSchema);

describe('messageSchema content', () => {
	it('첨부파일만 보낸 메시지의 빈 본문을 허용한다', async () => {
		const message = new MESSAGE_MODEL({content: '', editHistory: [{content: '', editedAt: new Date()}]});

		await expect(message.validate(['content', 'editHistory'])).resolves.toBeUndefined();
	});

	it('본문이 없으면 거부한다', async () => {
		const message = new MESSAGE_MODEL({});

		await expect(message.validate(['content'])).rejects.toThrow('Path `content` is required.');
	});

	it('편집 이력에 이전 본문이 없으면 거부한다', async () => {
		const message = new MESSAGE_MODEL({content: 'hello', editHistory: [{editedAt: new Date()}]});

		await expect(message.validate(['editHistory'])).rejects.toThrow('Path `content` is required.');
	});
});
