// @title           Cowork Voice API
// @version         20260820.0
// @description     음성 채널 서비스 — LiveKit 기반 음성 통화 세션 관리
// @description
// @description     ## 미디어 연결 (LiveKit)
// @description     이 서비스는 WebSocket을 직접 제공하지 않습니다. 아래 REST 엔드포인트가 LiveKit 접속
// @description     토큰을 발급하고, 클라이언트는 응답의 `livekit_url`(wss)로 **LiveKit 서버에 직접 연결**합니다.
// @description     이 연결은 Gateway를 경유하지 않습니다.
// @description
// @description     | 구분 | 입장 | 발급 토큰 권한 |
// @description     |------|------|----------------|
// @description     | `voice` — 상시 음성 채널 | `POST /voice/channels/{channel_id}/join` | 전원 publish + subscribe |
// @description     | `live` — 라이브 방송 | `POST /live/channels/{channel_id}/start` (호스트) | publish (마이크·화면공유) |
// @description     | | `POST /live/channels/{channel_id}/join` (시청자) | subscribe 전용 |
// @description
// @description     응답의 `room_name`이 LiveKit room 식별자이며, `session_id`로 서버 측 세션을 조회합니다.
// @description     참가자 입퇴장은 LiveKit이 `POST /voice/webhook`으로 통보하고 서버가 세션 상태를 갱신합니다.
// @BasePath        /api
// @securityDefinitions.apikey BearerAuth
// @in              header
// @name            Authorization
// @description     "Bearer {access_token}" 형식으로 입력하세요.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	chimiddleware "github.com/go-chi/chi/v5/middleware"
	"github.com/livekit/protocol/auth"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	httpSwagger "github.com/swaggo/http-swagger"
	"go.mongodb.org/mongo-driver/v2/mongo"
	mongoopts "go.mongodb.org/mongo-driver/v2/mongo/options"

	_ "github.com/cowork/cowork-voice/docs"
	"github.com/cowork/cowork-voice/internal/config"
	livedomain "github.com/cowork/cowork-voice/internal/domain/live_room"
	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
	webhookdomain "github.com/cowork/cowork-voice/internal/domain/webhook"
	"github.com/cowork/cowork-voice/internal/health"
	"github.com/cowork/cowork-voice/internal/infra/channel"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
	lkinfra "github.com/cowork/cowork-voice/internal/infra/livekit"
	mongoinfra "github.com/cowork/cowork-voice/internal/infra/mongo"
	redisinfra "github.com/cowork/cowork-voice/internal/infra/redis"
	"github.com/cowork/cowork-voice/internal/middleware"
	"github.com/cowork/cowork-voice/internal/monitoring"
	"github.com/cowork/cowork-voice/internal/relay"
	"github.com/cowork/cowork-voice/pkg/eureka"
	"github.com/cowork/cowork-voice/pkg/logger"
)

func main() {
	logger.Init("cowork-voice")

	cfg, err := config.Load()
	if err != nil {
		slog.Error("config load failed", "err", err)
		os.Exit(1)
	}

	mongoClient, err := mongo.Connect(mongoopts.Client().ApplyURI(cfg.MongoDBURI))
	if err != nil {
		slog.Error("mongodb connect failed", "err", err)
		os.Exit(1)
	}
	pingCtx, pingCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer pingCancel()
	if err := mongoClient.Ping(pingCtx, nil); err != nil {
		slog.Error("mongodb ping failed", "err", err)
		os.Exit(1)
	}
	db := mongoClient.Database(cfg.MongoDBDB)

	indexCtx, indexCancel := context.WithTimeout(context.Background(), 15*time.Second)
	if err := mongoinfra.CreateIndexes(indexCtx, db); err != nil {
		indexCancel()
		slog.Error("mongodb index creation failed", "err", err)
		os.Exit(1)
	}
	indexCancel()

	kafkaProducer := kafkadomain.NewProducer(
		cfg.KafkaBrokers,
		cfg.KafkaTopicVoiceEvent,
		cfg.KafkaMessageTimeoutMs,
	)

	livekitClient := lksdk.NewRoomServiceClient(
		cfg.LiveKitURL,
		cfg.LiveKitAPIKey,
		cfg.LiveKitAPISecret,
	)

	redisClient := redisinfra.NewClient(cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB)
	redisCtx, redisCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer redisCancel()
	if err := redisinfra.Ping(redisCtx, redisClient); err != nil {
		slog.Error("redis ping failed", "err", err)
		os.Exit(1)
	}

	channelClient := channel.NewClient(cfg.ChannelServiceURL)
	mongoRepo := mongoinfra.NewMongoSessionRepository(db)
	sessionRepo := redisinfra.NewCachedSessionRepository(mongoRepo, redisClient)
	outboxRepo := mongoinfra.NewOutboxRepository(db)
	livekitRoom := lkinfra.NewLiveKitRoom(
		livekitClient,
		cfg.LiveKitAPIKey,
		cfg.LiveKitAPISecret,
		cfg.LiveKitTokenTTLSecs,
	)
	roomSvc := roomdomain.NewRoomService(sessionRepo, channelClient, livekitRoom, outboxRepo, cfg.LiveKitWsURL)
	roomHandler := roomdomain.NewHandler(roomSvc)
	webhookSvc := webhookdomain.NewWebhookService(sessionRepo, outboxRepo)

	// live: 방송형(1:N) 라이브. 세션은 Mongo 직행(캐시 미적용), 이벤트는 동일 outbox 경유
	liveMongoRepo := mongoinfra.NewMongoLiveSessionRepository(db)
	liveLKRoom := lkinfra.NewLiveKitLiveRoom(
		livekitClient,
		cfg.LiveKitAPIKey,
		cfg.LiveKitAPISecret,
		cfg.LiveKitTokenTTLSecs,
	)
	liveSvc := livedomain.NewLiveService(liveMongoRepo, channelClient, liveLKRoom, outboxRepo, cfg.LiveKitWsURL)
	liveHandler := livedomain.NewHandler(liveSvc)
	liveWebhookSvc := webhookdomain.NewLiveWebhookService(liveMongoRepo, liveLKRoom, outboxRepo)

	// outbox relay: 도메인 서비스가 Mongo에 적재한 이벤트를 Kafka로 전송(재시도 포함)
	outboxRelay := relay.New(outboxRepo, kafkaProducer, 1*time.Second, 200)
	outboxRelay.Start()
	webhookHandler := webhookdomain.NewHandler(
		webhookSvc,
		liveWebhookSvc,
		auth.NewSimpleKeyProvider(cfg.LiveKitAPIKey, cfg.LiveKitAPISecret),
	)

	r := chi.NewRouter()
	r.Use(chimiddleware.RequestID)
	r.Use(chimiddleware.Recoverer)
	r.Use(monitoring.HTTPInFlightMiddleware)
	r.Use(monitoring.HTTPMetricsMiddleware)

	r.Get("/health", health.Handler)
	r.Handle("/metrics", promhttp.Handler())
	r.Get("/swagger/*", httpSwagger.Handler(
		httpSwagger.URL("/swagger/doc.json"),
	))
	r.Post("/voice/webhook", webhookHandler.Handle)

	r.Group(func(r chi.Router) {
		r.Use(middleware.ExtractAuthUser)
		r.Post("/voice/channels/{channel_id}/join", roomHandler.Join)
		r.Post("/voice/channels/{channel_id}/leave", roomHandler.Leave)
		r.Get("/voice/channels/{channel_id}/participants", roomHandler.Participants)
		r.Get("/voice/sessions/{session_id}", roomHandler.GetSession)
		r.Post("/live/channels/{channel_id}/start", liveHandler.Start)
		r.Post("/live/channels/{channel_id}/join", liveHandler.Join)
		r.Post("/live/channels/{channel_id}/leave", liveHandler.Leave)
		r.Get("/live/channels/{channel_id}", liveHandler.Status)
	})

	srv := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: r,
	}

	eurekaClient := eureka.New(cfg)
	if err := eurekaClient.Register(cfg); err != nil {
		slog.Error("critical: eureka registration failed", "err", err)
		os.Exit(1)
	}
	eurekaClient.StartHeartbeat(cfg)

	done := make(chan os.Signal, 1)
	serverErrCh := make(chan error, 1)
	signal.Notify(done, syscall.SIGINT, syscall.SIGTERM)
	exitCode := 0

	go func() {
		slog.Info("cowork-voice starting", "port", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			serverErrCh <- err
		}
	}()

	select {
	case sig := <-done:
		slog.Info("shutting down", "signal", sig.String())
	case err := <-serverErrCh:
		slog.Error("server error", "err", err)
		exitCode = 1
	}

	_ = eurekaClient.Deregister(cfg)

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("server shutdown error", "err", err)
	}
	outboxRelay.Stop()
	if err := kafkaProducer.Close(); err != nil {
		slog.Error("kafka producer close error", "err", err)
	}
	if err := mongoClient.Disconnect(shutdownCtx); err != nil {
		slog.Error("mongodb disconnect error", "err", err)
	}
	if err := redisClient.Close(); err != nil {
		slog.Error("redis close error", "err", err)
	}

	slog.Info("shutdown complete")
	if exitCode != 0 {
		os.Exit(exitCode)
	}
}
