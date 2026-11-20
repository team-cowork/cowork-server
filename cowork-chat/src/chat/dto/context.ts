/**
인증된 사용자를 식별하는 최소 컨텍스트. 사용자 수준 권한만 필요한 핸들러에 사용
*/
export type UserContext = {userId: number};

/**
특정 채널 내 사용자를 식별하는 컨텍스트. 채널 멤버십 검증이 필요한 핸들러에 사용
*/
export type ChannelUserContext = {channelId: number; userId: number};

/**
 특정 메시지에 대한 작업 시 필요한 컨텍스트.
 메시지 수정·삭제·리액션 등 메시지 단위 권한 검증에 사용.
 messageId는 MongoDB ObjectId 문자열
 */
export type MessageUserContext = {channelId: number; messageId: string; userId: number};
