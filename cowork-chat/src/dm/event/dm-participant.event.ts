import {type Nullable} from '../../common/type/nullable.js';

export type DmParticipantEvent = {
	eventType: 'JOIN' | 'LEAVE';
	channelId: number;
	/**
    DM 채널은 팀에 속하지 않으므로 null
    */
	teamId: Nullable<never>;
	userId: number;
	role: string;
	/**
    DM 참여 상태는 DM 채널만 포함한다.
    */
	channelType: 'DM';
	occurredAt: string;
	/**
    Startup/주기 projection snapshot 여부
    */
	snapshot?: boolean;
};
