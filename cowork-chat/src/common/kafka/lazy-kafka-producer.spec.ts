import {Buffer} from 'node:buffer';
import {createPartitioner} from './lazy-kafka-producer.js';

const message = {topic: 'test.topic'};
const context = {metadata: undefined};

describe('createPartitioner', () => {
	// KafkaJS 2.2.4 Partitioners.DefaultPartitioner가 partition 수 1, 3, 6, 12에서 고른 partition.
	// '1', '1024', '1234567890123', 'éclair-✓'은 murmur2가 음수인 key다.
	it.each([
		['1', [0, 0, 3, 3]],
		['2', [0, 2, 2, 8]],
		['42', [0, 1, 4, 4]],
		['1024', [0, 2, 5, 5]],
		['987654321', [0, 2, 5, 11]],
		['1234567890123', [0, 0, 0, 6]],
		['채널-한글', [0, 2, 5, 11]],
		['éclair-✓', [0, 1, 1, 7]],
	])('key %s는 KafkaJS DefaultPartitioner와 같은 partition으로 보낸다', (key, expected) => {
		const partition = createPartitioner()(message, Buffer.from(key), context);
		expect([1, 3, 6, 12].map(partitions => partition % partitions)).toEqual(expected);
	});

	it('key가 없으면 partition을 차례로 돌아가며 고른다', () => {
		const partitioner = createPartitioner();
		expect([1, 2, 3, 4].map(() => partitioner(message, undefined, context))).toEqual([0, 1, 2, 3]);
	});
});
