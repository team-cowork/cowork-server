export type UserContext = { userId: number };
export type ChannelUserContext = { channelId: number; userId: number };
export type ChannelUserRoleContext = { channelId: number; userId: number; userRole: string };
export type MessageUserRoleContext = { channelId: number; messageId: string; userId: number; userRole: string };
