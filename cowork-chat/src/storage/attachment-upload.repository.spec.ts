import {AttachmentUploadRepository} from './attachment-upload.repository.js';
import {ATTACHMENT_UPLOAD_TTL_SECONDS} from './attachment-upload.schema.js';

const OLD_TTL_SECONDS = 7 * 24 * 60 * 60;

describe('AttachmentUploadRepository TTL 인덱스 동기화', () => {
	const collection = {indexes: jest.fn(), dropIndex: jest.fn(), createIndex: jest.fn()};
	const repository = () => new AttachmentUploadRepository({
		db: {db: {collection: () => collection}},
		collection: {collectionName: 'attachment_uploads'},
	} as never);

	beforeEach(() => {
		jest.resetAllMocks();
		collection.dropIndex.mockResolvedValue(undefined);
		collection.createIndex.mockResolvedValue('createdAt_1');
	});

	it('만료 시간이 이미 맞으면 인덱스를 바꾸지 않는다', async () => {
		collection.indexes.mockResolvedValue([{name: 'createdAt_1', key: {createdAt: 1}, expireAfterSeconds: ATTACHMENT_UPLOAD_TTL_SECONDS}]);

		await repository().onModuleInit();

		expect(collection.dropIndex).not.toHaveBeenCalled();
		expect(collection.createIndex).not.toHaveBeenCalled();
	});

	it('만료 시간이 다르면 인덱스를 지우고 새 만료 시간으로 만든다', async () => {
		collection.indexes.mockResolvedValue([{name: 'createdAt_1', key: {createdAt: 1}, expireAfterSeconds: OLD_TTL_SECONDS}]);

		await repository().onModuleInit();

		expect(collection.dropIndex).toHaveBeenCalledWith('createdAt_1');
		expect(collection.createIndex).toHaveBeenCalledWith({createdAt: 1}, {expireAfterSeconds: ATTACHMENT_UPLOAD_TTL_SECONDS});
	});

	it('다른 인스턴스가 먼저 인덱스를 지웠어도 새 인덱스를 만든다', async () => {
		collection.indexes.mockResolvedValue([{name: 'createdAt_1', key: {createdAt: 1}, expireAfterSeconds: OLD_TTL_SECONDS}]);
		collection.dropIndex.mockRejectedValue({code: 27});

		await repository().onModuleInit();

		expect(collection.createIndex).toHaveBeenCalledWith({createdAt: 1}, {expireAfterSeconds: ATTACHMENT_UPLOAD_TTL_SECONDS});
	});

	it('collection이 없으면 새 만료 시간으로 인덱스를 만든다', async () => {
		collection.indexes.mockRejectedValue({code: 26});

		await repository().onModuleInit();

		expect(collection.dropIndex).not.toHaveBeenCalled();
		expect(collection.createIndex).toHaveBeenCalledWith({createdAt: 1}, {expireAfterSeconds: ATTACHMENT_UPLOAD_TTL_SECONDS});
	});
});
