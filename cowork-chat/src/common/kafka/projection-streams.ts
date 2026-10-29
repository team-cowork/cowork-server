import process from 'node:process';
import {type PartitionOffset} from 'kafkajs';
import {type ProjectionStreamName} from './projection-dataset.repository.js';

export type ProjectionStream = {
	name: string;
	topic: string;
	groupId: string;
	expectedSource: string;
	sourceGeneration: string;
};

export type StartupPartitionOffset = {
	low: string;
	high: string;
} & PartitionOffset;

const DEFAULT_SOURCE_GENERATION = process.env.CHAT_PROJECTION_SOURCE_GENERATION ?? '1';
const sourceGeneration = (name: string): string => (
	process.env[`CHAT_PROJECTION_${name}_SOURCE_GENERATION`] ?? DEFAULT_SOURCE_GENERATION
);

export const PROJECTION_STREAMS = {
	channel: {
		name: 'channel',
		topic: 'channel.event.v2',
		groupId: 'cowork-chat-channel-event-v2-projection',
		expectedSource: 'cowork-channel',
		sourceGeneration: sourceGeneration('CHANNEL'),
	},
	project: {
		name: 'project',
		topic: 'project.event.v2',
		groupId: 'cowork-chat-project-event-v2-projection',
		expectedSource: 'cowork-project',
		sourceGeneration: sourceGeneration('PROJECT'),
	},
	dmParticipant: {
		name: 'dmParticipant',
		topic: 'channel.dm-participant.state.v1',
		groupId: 'cowork-chat-dm-participant-v1-projection',
		expectedSource: 'cowork-channel',
		sourceGeneration: sourceGeneration('DM_PARTICIPANT'),
	},
	projectPermission: {
		name: 'projectPermission',
		topic: 'preference.project-access.state.v1',
		groupId: 'cowork-chat-project-permission-v1-projection',
		expectedSource: 'cowork-preference',
		sourceGeneration: sourceGeneration('PROJECT_PERMISSION'),
	},
	projectGithubRepo: {
		name: 'projectGithubRepo',
		topic: 'project.github-repo.event',
		groupId: 'cowork-chat-project-github-repo-event-v1-projection',
		expectedSource: 'cowork-project',
		sourceGeneration: sourceGeneration('PROJECT_GITHUB_REPO'),
	},
	teamMember: {
		name: 'teamMember',
		topic: 'team.member.event',
		groupId: 'cowork-chat-team-member-event-v1-projection',
		expectedSource: 'cowork-team',
		sourceGeneration: sourceGeneration('TEAM_MEMBER'),
	},
	teamRole: {
		name: 'teamRole',
		topic: 'preference.team-role.state.v2',
		groupId: 'cowork-chat-team-role-state-v2-projection',
		expectedSource: 'cowork-preference',
		sourceGeneration: sourceGeneration('TEAM_ROLE'),
	},
	channelRolePolicy: {
		name: 'channelRolePolicy',
		topic: 'preference.channel-role-policy.state.v2',
		groupId: 'cowork-chat-channel-role-policy-state-v2-projection',
		expectedSource: 'cowork-preference',
		sourceGeneration: sourceGeneration('CHANNEL_ROLE_POLICY'),
	},
	textChannelSetting: {
		name: 'textChannelSetting',
		topic: 'preference.text-channel-setting.state.v1',
		groupId: 'cowork-chat-text-channel-setting-state-v1-projection',
		expectedSource: 'cowork-preference',
		sourceGeneration: sourceGeneration('TEXT_CHANNEL_SETTING'),
	},
	webhook: {
		name: 'webhook',
		topic: 'preference.webhook.state.v2',
		groupId: 'cowork-chat-webhook-state-v2-projection',
		expectedSource: 'cowork-preference',
		sourceGeneration: sourceGeneration('WEBHOOK'),
	},
	userProfile: {
		name: 'userProfile',
		topic: 'user.profile.event.v2',
		groupId: 'cowork-chat-user-profile-event-v2-projection',
		expectedSource: 'cowork-user',
		sourceGeneration: sourceGeneration('USER_PROFILE'),
	},
} as const satisfies Record<ProjectionStreamName, ProjectionStream>;

export type ProjectionName = keyof typeof PROJECTION_STREAMS;
