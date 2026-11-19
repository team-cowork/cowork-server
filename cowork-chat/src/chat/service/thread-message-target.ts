import {type Nullable} from '../../common/type/nullable.js';
import {type ChannelActionPermission} from './channel-message-read-access.service.js';

export type ThreadTargetError = 'THREAD_NOT_FOUND' | 'THREAD_ARCHIVED' | 'PARENT_THREAD_MISMATCH';

type ThreadView = {channelId: number; status: string; archived: boolean; deleted: boolean};
type ParentView = {threadId?: Nullable<number>};

/**
 메시지를 보낼 때 검사할 권한. 스레드 메시지는 `thread_reply`, 채널 메시지(답장 포함)는 `message_send`다.
 */
export function sendPermission(threadId: number | undefined): ChannelActionPermission {
	return threadId === undefined ? 'message_send' : 'thread_reply';
}

/**
 메시지를 보낼 스레드와 답장 원본이 맞는지 판정한다.

 - 스레드는 같은 채널의 확정된(`ACTIVE`) 스레드여야 하고 보관되지 않아야 한다.
 - 답장 원본은 보내는 메시지와 같은 곳에 있어야 한다. 채널 메시지는 채널 메시지에, 스레드 메시지는 같은 스레드의 메시지에만 답장한다.

 `threadId`는 채널 메시지이면 `undefined`다. `thread`는 `threadId`로 찾은 스레드 projection이며, 없으면 `null`이다.
 `parent`는 답장 원본 메시지이며, 답장이 아니면 `undefined`다.

 @returns 문제가 없으면 `null`
 */
export function checkThreadTarget(
	channelId: number,
	threadId: number | undefined,
	thread: Nullable<ThreadView> | undefined,
	parent: ParentView | undefined,
): Nullable<ThreadTargetError> {
	if (threadId !== undefined) {
		if (!thread || thread.deleted || thread.status !== 'ACTIVE' || thread.channelId !== channelId) {
			return 'THREAD_NOT_FOUND';
		}

		if (thread.archived) {
			return 'THREAD_ARCHIVED';
		}
	}

	return parent !== undefined && (parent.threadId ?? undefined) !== threadId ? 'PARENT_THREAD_MISMATCH' : null;
}
