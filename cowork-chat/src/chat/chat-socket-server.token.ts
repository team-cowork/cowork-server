import {type Server} from 'socket.io';

/**
`ChatService`는 브로드캐스트에 Socket.IO 서버만 필요하므로, `ChatGateway` 클래스 대신 이 토큰으로
주입받아 `chat.gateway.ts`를 import하지 않는다. `chat.gateway.ts`는 소비자들을 통해 다시
`chat.service.ts`를 import하므로, 클래스를 직접 참조하면 순환 참조가 생긴다.
*/
export const CHAT_SOCKET_SERVER = Symbol('CHAT_SOCKET_SERVER');

export type ChatSocketServerProvider = {
    readonly server: Server;
};
