import {systemMessageKey, voiceSystemMessageKey} from './system-message-key.js';

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

describe('voiceSystemMessageKey', () => {
	it('같은 event_id와 채널이면 offset과 관계없이 같은 키를 만든다', () => {
		expect(voiceSystemMessageKey('6afa6d0fff8500e6a244c3b9', 7)).toBe('system:voice:6afa6d0fff8500e6a244c3b9:7');
		expect(voiceSystemMessageKey('6afa6d0fff8500e6a244c3b9', 7)).not.toBe(voiceSystemMessageKey('6afa6d0fff8500e6a244c3ba', 7));
	});
});
