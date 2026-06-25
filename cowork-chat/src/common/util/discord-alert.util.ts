import { hostname } from 'os';
import { DiscordField } from 'dicoshot-nest';

/** Discord embed field value의 실제 한도(1024자)보다 여유를 둔 안전 길이 */
const MAX_FIELD_LENGTH = 1000;

/**
 * 에러를 디버깅에 필요한 Discord embed 필드 목록으로 변환한다.
 *
 * Error 메시지, 호스트명(같은 서비스의 어느 인스턴스/pod인지 식별), Stack Trace(있는 경우)를 포함한다.
 */
export function buildErrorFields(error: unknown): DiscordField[] {
    const message = error instanceof Error ? error.message : String(error);
    const stack = error instanceof Error ? error.stack : undefined;

    const fields: DiscordField[] = [
        { name: 'Error', value: message || '(no message)' },
        { name: 'Host', value: hostname(), inline: true },
    ];

    if (stack) {
        fields.push({ name: 'Stack Trace', value: `\`\`\`\n${truncate(stack, MAX_FIELD_LENGTH)}\n\`\`\`` });
    }

    return fields;
}

function truncate(value: string, max: number): string {
    return value.length > max ? `${value.slice(0, max)}…` : value;
}
