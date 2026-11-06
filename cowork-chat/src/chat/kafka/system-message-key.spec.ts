import {systemMessageKey} from './system-message-key.js';

describe('systemMessageKey', () => {
	it('같은 Kafka 메시지와 채널이면 같은 키를 만든다', () => {
		const source = {topic: 'github.repo.event', partition: 1, offset: '42'};

		expect(systemMessageKey(source, 7)).toBe(systemMessageKey({...source}, 7));
		expect(systemMessageKey(source, 7)).toBe('system:github.repo.event:1:42:7');
	});

	it('채널이나 offset이 다르면 다른 키를 만든다', () => {
		const source = {topic: 'github.repo.event', partition: 1, offset: '42'};

		expect(systemMessageKey(source, 7)).not.toBe(systemMessageKey(source, 8));
		expect(systemMessageKey(source, 7)).not.toBe(systemMessageKey({...source, offset: '43'}, 7));
	});
});
