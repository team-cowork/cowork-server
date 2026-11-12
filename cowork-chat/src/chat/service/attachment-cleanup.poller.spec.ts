import {Types} from 'mongoose';
import {type AttachmentUpload, UPLOAD_RECLAIM_AGE_MS} from '../../storage/attachment-upload.schema.js';
import {type AttachmentDeletionRecord} from '../repository/attachment-deletion.repository.js';
import {AttachmentCleanupPoller, ABANDONED_DELETION_MS} from './attachment-cleanup.poller.js';

const DAY_MS = 24 * 60 * 60 * 1000;
const T0 = new Date('2026-11-01T00:00:00.000Z');
const at = (ms: number) => new Date(T0.getTime() + ms);

class FakeStorage {
	readonly objects = new Set<string>();
	readonly failing = new Set<string>();

	async removeObject(objectKey: string): Promise<void> {
		if (this.failing.has(objectKey)) {
			throw new Error(`storage unavailable for ${objectKey}`);
		}

		this.objects.delete(objectKey);
	}
}

type FakeMessage = {_id: string; clientMessageId?: string; searchIndexStatus: string; attachments: Array<{objectKey: string}>};

class FakeMessages {
	readonly rows = new Map<string, FakeMessage>();

	async findById(messageId: string): Promise<FakeMessage | undefined> {
		return this.rows.get(messageId);
	}

	async referencesAttachment(clientMessageId: string, objectKey: string): Promise<boolean> {
		return this.rows.values().some(row => row.clientMessageId === clientMessageId && row.attachments.some(a => a.objectKey === objectKey));
	}

	async referencesObject(objectKey: string): Promise<boolean> {
		return this.rows.values().some(row => row.attachments.some(a => a.objectKey === objectKey));
	}
}

class FakeDeletions {
	readonly rows = new Map<string, AttachmentDeletionRecord>();
	failRemove = false;

	record(input: {messageId: string; channelId: number; objectKeys: string[]}, now: Date): void {
		const existing = this.rows.values().find(row => row.messageId === input.messageId);
		if (existing) {
			Object.assign(existing, {requestedAt: now, nextAttemptAt: now});
			return;
		}

		const _id = new Types.ObjectId();
		this.rows.set(_id.toString(), {
			_id, ...input, requestedAt: now, nextAttemptAt: now, attempts: 0, lastError: null, claimId: null, createdAt: now, updatedAt: now,
		});
	}

	async claimDue(now: Date, leaseMs: number, batchSize: number): Promise<AttachmentDeletionRecord[]> {
		const due = this.rows.values().filter(row => row.nextAttemptAt <= now).take(batchSize).toArray();
		for (const row of due) {
			row.nextAttemptAt = new Date(now.getTime() + leaseMs);
		}

		return due.map(row => ({...row}));
	}

	async markFailed(id: Types.ObjectId, error: string): Promise<void> {
		const row = this.rows.get(id.toString())!;
		row.attempts++;
		row.lastError = error;
	}

	async remove(id: Types.ObjectId, requestedAt: Date): Promise<void> {
		if (this.failRemove) {
			throw new Error('mongo unavailable');
		}

		if (this.rows.get(id.toString())?.requestedAt.getTime() === requestedAt.getTime()) {
			this.rows.delete(id.toString());
		}
	}
}

class FakeUploads {
	readonly rows = new Map<string, AttachmentUpload>();
	failRemove = false;

	async claimReclaimable(createdBefore: Date, now: Date, leaseMs: number, batchSize: number): Promise<AttachmentUpload[]> {
		const due = this.rows.values()
			.filter(row => row.createdAt! < createdBefore && !(row.reclaimAttemptAt && row.reclaimAttemptAt > now))
			.take(batchSize)
			.toArray();
		for (const row of due) {
			row.reclaimAttemptAt = new Date(now.getTime() + leaseMs);
		}

		return due.map(row => ({...row}));
	}

	async remove(uploadKey: string): Promise<void> {
		if (this.failRemove) {
			throw new Error('mongo unavailable');
		}

		this.rows.delete(uploadKey);
	}
}

describe('AttachmentCleanupPoller', () => {
	let storage: FakeStorage;
	let messages: FakeMessages;
	let deletions: FakeDeletions;
	let uploads: FakeUploads;
	let poller: AttachmentCleanupPoller;
	const meetingNotes = {references: jest.fn()};
	const readiness = {areReady: jest.fn(), isCaughtUp: jest.fn()};
	const retentions = {claimDue: jest.fn(), remove: jest.fn()};

	const messageId = new Types.ObjectId().toString();
	const keyA = 'chat-files/1/42/a.png';
	const keyB = 'chat-files/1/42/b.png';

	beforeEach(() => {
		retentions.claimDue.mockReset().mockResolvedValue([]);
		retentions.remove.mockReset().mockResolvedValue(undefined);
		meetingNotes.references.mockReset().mockResolvedValue(false);
		readiness.areReady.mockReset().mockReturnValue(true);
		readiness.isCaughtUp.mockReset().mockReturnValue(true);
		storage = new FakeStorage();
		messages = new FakeMessages();
		deletions = new FakeDeletions();
		uploads = new FakeUploads();
		poller = new AttachmentCleanupPoller(deletions as never, uploads as never, messages as never, storage as never);
		Object.assign(poller, {meetingNotes, readiness, retentions});
		storage.objects.add(keyA);
		storage.objects.add(keyB);
	});

	const addUpload = (overrides: Partial<AttachmentUpload> = {}) => {
		const upload: AttachmentUpload = {
			uploadKey: 'chat-uploads/1/42/a.png', channelId: 1, userId: 42, name: 'a.png', mimeType: 'image/png', declaredSize: 1,
			objectKey: keyA, size: 1, createdAt: T0, ...overrides,
		};
		uploads.rows.set(upload.uploadKey, upload);
	};

	it('업로드 기록이 만료된 뒤에도 회의록에서 해제된 파일을 정리한다', async () => {
		retentions.claimDue.mockResolvedValue([{objectKey: keyA, claimId: 'claim'}]);
		await poller.runCycle(at(30 * DAY_MS));
		expect(storage.objects.has(keyA)).toBe(false);
		expect(retentions.remove).toHaveBeenCalledWith(keyA, 'claim');
	});

	it('다른 회의록이 참조하는 동안 보관하고 마지막 참조가 없어지면 삭제한다', async () => {
		retentions.claimDue.mockResolvedValue([{objectKey: keyA, claimId: 'claim'}]);
		meetingNotes.references.mockResolvedValue(true);
		await poller.runCycle(at(30 * DAY_MS));
		expect(storage.objects.has(keyA)).toBe(true);
		expect(retentions.remove).not.toHaveBeenCalled();
		meetingNotes.references.mockResolvedValue(false);
		await poller.runCycle(at(31 * DAY_MS));
		expect(storage.objects.has(keyA)).toBe(false);
	});

	it('회의록에서 해제해도 메시지가 참조하는 파일은 보관한다', async () => {
		retentions.claimDue.mockResolvedValue([{objectKey: keyA, claimId: 'claim'}]);
		messages.rows.set(messageId, {_id: messageId, searchIndexStatus: 'INDEXED', attachments: [{objectKey: keyA}]});
		await poller.runCycle(at(30 * DAY_MS));
		expect(storage.objects.has(keyA)).toBe(true);
		expect(retentions.remove).not.toHaveBeenCalled();
	});

	it('회의록에 등록된 파일은 업로드 회수 대상이어도 보관한다', async () => {
		addUpload();
		meetingNotes.references.mockResolvedValue(true);
		await poller.runCycle(at(UPLOAD_RECLAIM_AGE_MS + 1));
		expect(storage.objects.has(keyA)).toBe(true);
	});

	it('회의록에서 사용하는 파일은 채팅 메시지가 삭제되어도 보관한다', async () => {
		deletions.record({messageId, channelId: 1, objectKeys: [keyA]}, T0);
		meetingNotes.references.mockResolvedValue(true);
		await poller.runCycle(at(DAY_MS));
		expect(storage.objects.has(keyA)).toBe(true);
	});

	it('회의록 참조가 동기화되지 않았으면 정리를 보류한다', async () => {
		addUpload();
		readiness.areReady.mockReturnValue(false);
		readiness.isCaughtUp.mockReturnValue(false);
		await poller.runCycle(at(UPLOAD_RECLAIM_AGE_MS + 1));
		expect(storage.objects.has(keyA)).toBe(true);
		expect(uploads.rows.size).toBe(1);
	});

	it('API 읽기 유예 중이어도 알려진 동기화 지연이 있으면 파일을 삭제하지 않는다', async () => {
		addUpload();
		readiness.isCaughtUp.mockReturnValue(false);
		await poller.runCycle(at(UPLOAD_RECLAIM_AGE_MS + 1));
		expect(storage.objects.has(keyA)).toBe(true);
		expect(uploads.rows.size).toBe(1);
	});

	describe('메시지 삭제 의도', () => {
		it('메시지가 남아 있으면 객체를 지우지 않는다', async () => {
			messages.rows.set(messageId, {_id: messageId, searchIndexStatus: 'DELETING', attachments: [{objectKey: keyA}]});
			deletions.record({messageId, channelId: 1, objectKeys: [keyA]}, T0);

			await poller.runCycle(at(ABANDONED_DELETION_MS * 2));

			expect(storage.objects.has(keyA)).toBe(true);
			expect(deletions.rows.size).toBe(1);
		});

		it('메시지가 사라진 뒤 객체를 지우고 의도를 제거한다', async () => {
			deletions.record({messageId, channelId: 1, objectKeys: [keyA, keyB]}, T0);

			await poller.runCycle(T0);

			expect(storage.objects.size).toBe(0);
			expect(deletions.rows.size).toBe(0);
		});

		it('객체 삭제가 일부 실패하면 의도를 남기고 다음 점유에서 마저 지운다', async () => {
			deletions.record({messageId, channelId: 1, objectKeys: [keyA, keyB]}, T0);
			storage.failing.add(keyB);

			await poller.runCycle(T0);

			expect(storage.objects.has(keyA)).toBe(false);
			expect(storage.objects.has(keyB)).toBe(true);
			expect(deletions.rows.values().next().value).toMatchObject({attempts: 1, objectKeys: [keyA, keyB]});

			storage.failing.clear();
			await poller.runCycle(at(60_000));
			expect(storage.objects.has(keyB)).toBe(true);

			await poller.runCycle(at(3 * 60_000));
			expect(storage.objects.size).toBe(0);
			expect(deletions.rows.size).toBe(0);
		});

		it('객체 삭제 후 의도 제거가 실패해도 반복 실행이 안전하다', async () => {
			deletions.record({messageId, channelId: 1, objectKeys: [keyA]}, T0);
			deletions.failRemove = true;

			await poller.runCycle(T0);
			expect(storage.objects.has(keyA)).toBe(false);
			expect(deletions.rows.size).toBe(1);

			deletions.failRemove = false;
			await poller.runCycle(at(3 * 60_000));
			expect(deletions.rows.size).toBe(0);
		});

		it('같은 삭제를 반복 요청하고 실행해도 한 번 지운 결과와 같다', async () => {
			deletions.record({messageId, channelId: 1, objectKeys: [keyA]}, T0);
			deletions.record({messageId, channelId: 1, objectKeys: [keyA]}, T0);

			await poller.runCycle(T0);
			await poller.runCycle(at(3 * 60_000));

			expect(deletions.rows.size).toBe(0);
			expect(storage.objects.has(keyA)).toBe(false);
			expect(storage.objects.has(keyB)).toBe(true);
		});

		it('메시지 삭제가 중단돼 메시지가 남으면 객체를 지우지 않고 의도를 버린다', async () => {
			messages.rows.set(messageId, {_id: messageId, searchIndexStatus: 'SYNCED', attachments: [{objectKey: keyA}]});
			deletions.record({messageId, channelId: 1, objectKeys: [keyA]}, T0);

			await poller.runCycle(T0);
			expect(deletions.rows.size).toBe(1);

			await poller.runCycle(at(ABANDONED_DELETION_MS + 1));
			expect(deletions.rows.size).toBe(0);
			expect(storage.objects.has(keyA)).toBe(true);
		});
	});

	describe('미전송 업로드 회수', () => {
		it('재시도 창과 유예가 지나기 전에는 회수하지 않는다', async () => {
			addUpload();

			await poller.runCycle(at((7 * DAY_MS) + (DAY_MS / 2)));

			expect(storage.objects.has(keyA)).toBe(true);
			expect(uploads.rows.size).toBe(1);
		});

		it('7일과 유예 1일이 지난 미전송 업로드는 객체를 지운 뒤 기록을 지운다', async () => {
			addUpload();

			await poller.runCycle(at(UPLOAD_RECLAIM_AGE_MS + 1));

			expect(UPLOAD_RECLAIM_AGE_MS).toBe(8 * DAY_MS);
			expect(storage.objects.has(keyA)).toBe(false);
			expect(uploads.rows.size).toBe(0);
		});

		it('메시지가 참조하는 첨부는 지우지 않는다', async () => {
			addUpload({clientMessageId: 'client-1'});
			messages.rows.set(messageId, {
				_id: messageId, clientMessageId: 'client-1', searchIndexStatus: 'SYNCED', attachments: [{objectKey: keyA}],
			});

			await poller.runCycle(at(UPLOAD_RECLAIM_AGE_MS + 1));

			expect(storage.objects.has(keyA)).toBe(true);
			expect(uploads.rows.size).toBe(0);
		});

		it('메시지에 연결했지만 메시지가 저장되지 않은 업로드는 회수한다', async () => {
			addUpload({clientMessageId: 'client-1'});

			await poller.runCycle(at(UPLOAD_RECLAIM_AGE_MS + 1));

			expect(storage.objects.has(keyA)).toBe(false);
			expect(uploads.rows.size).toBe(0);
		});

		it('객체 삭제가 실패하면 기록을 남기고 점유가 끝난 뒤 다시 회수한다', async () => {
			addUpload();
			storage.failing.add(keyA);
			const now = at(UPLOAD_RECLAIM_AGE_MS + 1);

			await poller.runCycle(now);
			expect(uploads.rows.size).toBe(1);

			storage.failing.clear();
			await poller.runCycle(new Date(now.getTime() + (2 * 60 * 60 * 1000)));
			expect(storage.objects.has(keyA)).toBe(false);
			expect(uploads.rows.size).toBe(0);
		});

		it('객체 삭제 후 기록 삭제가 실패해도 다시 회수하면 끝난다', async () => {
			addUpload();
			uploads.failRemove = true;
			const now = at(UPLOAD_RECLAIM_AGE_MS + 1);

			await poller.runCycle(now);
			expect(storage.objects.has(keyA)).toBe(false);
			expect(uploads.rows.size).toBe(1);

			uploads.failRemove = false;
			await poller.runCycle(new Date(now.getTime() + (2 * 60 * 60 * 1000)));
			expect(uploads.rows.size).toBe(0);
		});

		it('확정 전 기록은 객체 삭제 없이 기록만 지운다', async () => {
			addUpload({objectKey: undefined, size: undefined});

			await poller.runCycle(at(UPLOAD_RECLAIM_AGE_MS + 1));

			expect(storage.objects.size).toBe(2);
			expect(uploads.rows.size).toBe(0);
		});
	});
});
