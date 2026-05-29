import { IsNumber, IsOptional, IsString } from 'class-validator';

/**
 * 채팅 메시지 전송 WebSocket 이벤트 페이로드 DTO.
 *
 * `projectId`는 프로젝트 채널일 때만 설정한다. 팀 채널은 null 또는 생략한다.
 * `authorId`는 서버가 JWT에서 추출한 값을 클라이언트로부터 재확인하는 용도이며,
 * 실제 권한 판단은 서버 측 컨텍스트를 우선한다.
 */
export class MessagePayloadDto {
    @IsNumber() teamId!: number;
    /** 프로젝트 채널일 때만 설정; 팀 채널은 null 또는 undefined */
    @IsNumber() @IsOptional() projectId?: number | null;
    @IsNumber() channelId!: number;
    @IsNumber() authorId!: number;
    @IsString() content!: string;
}
