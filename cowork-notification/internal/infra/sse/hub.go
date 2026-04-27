package sse

import "sync"

const clientChannelBuffer = 8

// Hub는 사용자별 SSE 클라이언트 채널을 관리합니다.
type Hub struct {
	mu      sync.RWMutex
	clients map[int64][]chan []byte
}

func NewHub() *Hub {
	return &Hub{clients: make(map[int64][]chan []byte)}
}

// Subscribe는 해당 사용자의 SSE 채널을 등록합니다.
// 클라이언트 연결 종료 시 반드시 반환된 unsubscribe를 호출해야 합니다.
func (h *Hub) Subscribe(userID int64) (ch chan []byte, unsubscribe func()) {
	ch = make(chan []byte, clientChannelBuffer)

	h.mu.Lock()
	h.clients[userID] = append(h.clients[userID], ch)
	h.mu.Unlock()

	unsubscribe = func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		list := h.clients[userID]
		for i, c := range list {
			if c == ch {
				h.clients[userID] = append(list[:i], list[i+1:]...)
				break
			}
		}
		if len(h.clients[userID]) == 0 {
			delete(h.clients, userID)
		}
		close(ch)
	}
	return ch, unsubscribe
}

// Broadcast는 지정된 사용자들의 모든 SSE 클라이언트에 payload를 전송합니다.
// 클라이언트 버퍼가 가득 찬 경우 해당 메시지는 드롭됩니다.
func (h *Hub) Broadcast(userIDs []int64, payload []byte) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, uid := range userIDs {
		for _, ch := range h.clients[uid] {
			select {
			case ch <- payload:
			default:
			}
		}
	}
}
