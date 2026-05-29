/** 인증된 사용자를 식별하는 최소 컨텍스트. 사용자 수준 권한만 필요한 핸들러에 사용 */
export type UserContext = { userId: number };

/** 특정 채널 내 사용자를 식별하는 컨텍스트. 채널 멤버십 검증이 필요한 핸들러에 사용 */
export type ChannelUserContext = { channelId: number; userId: number };

/**
 * 채널 내 사용자의 역할 정보를 포함하는 컨텍스트.
 * 역할 기반 접근 제어(RBAC)가 필요한 핸들러(예: 채널 관리 작업)에 사용
 */
export type ChannelUserRoleContext = { channelId: number; userId: number; userRole: string };

/**
 * 특정 메시지에 대한 작업 시 필요한 컨텍스트.
 * 메시지 수정·삭제·리액션 등 메시지 단위 권한 검증에 사용.
 * messageId는 MongoDB ObjectId 문자열
 */
export type MessageUserRoleContext = { channelId: number; messageId: string; userId: number; userRole: string };
