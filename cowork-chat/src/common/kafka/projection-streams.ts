import {type ProjectionStreamName} from './projection-dataset.repository.js';

export type ProjectionStream = {
	name: string;
	topic: string;
	groupId: string;
	expectedSource: string;
};

export type PartitionOffset = {partition: number; offset: string};

export type StartupPartitionOffset = {
	low: string;
	high: string;
} & PartitionOffset;

export const PROJECTION_STREAMS = {
	accountPolicy: {
		name: 'accountPolicy',
		topic: 'preference.account-role.state.v1',
		groupId: 'cowork-chat-account-role-v1-projection',
		expectedSource: 'cowork-preference',
	},
	meetingNoteAttachment: {
		name: 'meetingNoteAttachment',
		topic: 'channel.meeting-note-attachments.state.v1',
		groupId: 'cowork-chat-meeting-note-attachments-v1-projection',
		expectedSource: 'cowork-channel',
	},
	thread: {
		name: 'thread',
		topic: 'channel.thread.state.v1',
		groupId: 'cowork-chat-thread-state-v1-projection',
		expectedSource: 'cowork-channel',
	},
	channel: {
		name: 'channel',
		topic: 'channel.event.v2',
		groupId: 'cowork-chat-channel-event-v2-projection',
		expectedSource: 'cowork-channel',
	},
	project: {
		name: 'project',
		topic: 'project.event.v2',
		groupId: 'cowork-chat-project-event-v2-projection',
		expectedSource: 'cowork-project',
	},
	dmParticipant: {
		name: 'dmParticipant',
		topic: 'channel.dm-participant.state.v1',
		groupId: 'cowork-chat-dm-participant-v1-projection',
		expectedSource: 'cowork-channel',
	},
	projectPermission: {
		name: 'projectPermission',
		topic: 'preference.project-access.state.v1',
		groupId: 'cowork-chat-project-permission-v1-projection',
		expectedSource: 'cowork-preference',
	},
	projectGithubRepo: {
		name: 'projectGithubRepo',
		topic: 'project.github-repo.event',
		groupId: 'cowork-chat-project-github-repo-event-v1-projection',
		expectedSource: 'cowork-project',
	},
	teamMember: {
		name: 'teamMember',
		topic: 'team.member.event',
		groupId: 'cowork-chat-team-member-event-v1-projection',
		expectedSource: 'cowork-team',
	},
	teamAccess: {
		name: 'teamAccess',
		topic: 'preference.team-access.state.v2',
		groupId: 'cowork-chat-team-access-state-v2-projection',
		expectedSource: 'cowork-preference',
	},
	channelAccess: {
		name: 'channelAccess',
		topic: 'preference.channel-access.state.v1',
		groupId: 'cowork-chat-channel-access-state-v1-projection',
		expectedSource: 'cowork-preference',
	},
	textChannelSetting: {
		name: 'textChannelSetting',
		topic: 'preference.text-channel-setting.state.v1',
		groupId: 'cowork-chat-text-channel-setting-state-v1-projection',
		expectedSource: 'cowork-preference',
	},
	teamSetting: {
		name: 'teamSetting',
		topic: 'preference.team-setting.state.v1',
		groupId: 'cowork-chat-team-setting-state-v1-projection',
		expectedSource: 'cowork-preference',
	},
	webhook: {
		name: 'webhook',
		topic: 'preference.webhook.state.v2',
		groupId: 'cowork-chat-webhook-state-v2-projection',
		expectedSource: 'cowork-preference',
	},
	userProfile: {
		name: 'userProfile',
		topic: 'user.profile.event.v2',
		groupId: 'cowork-chat-user-profile-event-v2-projection',
		expectedSource: 'cowork-user',
	},
} as const satisfies Record<ProjectionStreamName, ProjectionStream>;

export type ProjectionName = keyof typeof PROJECTION_STREAMS;

/**
채널 권한 판정(HTTP 라우트 gate와 소켓 broadcast)이 읽는 projection만 모은다.
*/
export const CHANNEL_PERMISSION_STREAMS: ProjectionName[] = ['channel', 'teamMember', 'channelAccess', 'dmParticipant'];
