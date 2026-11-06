// @title           cowork Voice API
// @version         20261106.0
// @description     음성 통화와 라이브 방송에 사용할 LiveKit 토큰을 발급합니다. 클라이언트는 응답의 data.livekit_url로 LiveKit에 직접 연결합니다. room_name은 통화방 이름이고 session_id는 세션 조회에 사용하는 ID입니다.
// @BasePath        /api/voice
// @securityDefinitions.apikey BearerAuth
// @in              header
// @name            Authorization
// @description     "Bearer {access_token}" 형식으로 입력합니다.
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
	httpSwagger "github.com/swaggo/http-swagger/v2"
	"go.mongodb.org/mongo-driver/v2/mongo"
	mongoopts "go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/cowork/cowork-voice/internal/config"
	livedomain "github.com/cowork/cowork-voice/internal/domain/live_room"
	permissionsync "github.com/cowork/cowork-voice/internal/domain/permission_sync"
	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
	webhookdomain "github.com/cowork/cowork-voice/internal/domain/webhook"
	"github.com/cowork/cowork-voice/internal/health"
	"github.com/cowork/cowork-voice/internal/infra/channel"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
	lkinfra "github.com/cowork/cowork-voice/internal/infra/livekit"
	mongoinfra "github.com/cowork/cowork-voice/internal/infra/mongo"
	"github.com/cowork/cowork-voice/internal/middleware"
	"github.com/cowork/cowork-voice/internal/monitoring"
	"github.com/cowork/cowork-voice/internal/relay"
	"github.com/cowork/cowork-voice/pkg/logger"
	_ "github.com/cowork/cowork-voice/swagger"
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
	if err := channel.CreateIndexes(indexCtx, db); err != nil {
		indexCancel()
		slog.Error("channel permission projection index creation failed", "err", err)
		os.Exit(1)
	}
	if err := channel.CreateCheckpointIndexes(indexCtx, db); err != nil {
		indexCancel()
		slog.Error("channel permission projection checkpoint index creation failed", "err", err)
		os.Exit(1)
	}
	permissionCheckpoints, err := channel.NewMongoCheckpointStore(indexCtx, db)
	if err != nil {
		indexCancel()
		slog.Error("permission checkpoint initialization failed", "err", err)
		os.Exit(1)
	}
	indexCancel()

	kafkaProducer := kafkadomain.NewProducer(
		cfg.KafkaSecurity,
		cfg.KafkaBrokers,
		cfg.KafkaTopicVoiceEvent,
		cfg.KafkaMessageTimeoutMs,
	)

	livekitClient := lksdk.NewRoomServiceClient(
		cfg.LiveKitURL,
		cfg.LiveKitAPIKey,
		cfg.LiveKitAPISecret,
	)

	channelPermissions := channel.NewProjection(db)
	projectionReadiness := health.NewReadiness()
	channelPermissionConsumer, err := channel.NewConsumer(
		cfg.KafkaSecurity,
		cfg.KafkaBrokers,
		cfg.KafkaGroupIDChannelAccess,
		channel.NewEventHandler(channelPermissions),
		permissionCheckpoints,
		projectionReadiness,
	)
	if err != nil {
		slog.Error("channel permission consumer init failed", "err", err)
		os.Exit(1)
	}
	channelPermissions.SetCurrentHighChecker(channelPermissionConsumer)
	runtimeCtx, runtimeCancel := context.WithCancel(context.Background())
	channelPermissionConsumer.Start(runtimeCtx)
	sessionRepo := mongoinfra.NewMongoSessionRepository(db)
	outboxRepo := mongoinfra.NewOutboxRepository(db)
	livekitRoom := lkinfra.NewLiveKitRoom(
		livekitClient,
		cfg.LiveKitAPIKey,
		cfg.LiveKitAPISecret,
		cfg.LiveKitTokenTTLSecs,
	)
	roomSvc := roomdomain.NewRoomService(sessionRepo, channelPermissions, livekitRoom, cfg.LiveKitWsURL)
	roomHandler := roomdomain.NewHandler(roomSvc)
	webhookSvc := webhookdomain.NewWebhookService(sessionRepo)

	// live: 방송형(1:N) 라이브. 세션과 이벤트는 MongoDB와 동일 outbox를 사용한다.
	liveMongoRepo := mongoinfra.NewMongoLiveSessionRepository(db)
	liveLKRoom := lkinfra.NewLiveKitLiveRoom(
		livekitClient,
		cfg.LiveKitAPIKey,
		cfg.LiveKitAPISecret,
		cfg.LiveKitTokenTTLSecs,
	)
	liveSvc := livedomain.NewLiveService(liveMongoRepo, channelPermissions, liveLKRoom, cfg.LiveKitWsURL)
	liveHandler := livedomain.NewHandler(liveSvc)
	liveWebhookSvc := webhookdomain.NewLiveWebhookService(liveMongoRepo, liveLKRoom)
	permissionSync := permissionsync.New(livekitClient, channelPermissions, liveMongoRepo)
	permissionSyncDone := make(chan struct{})
	go func() {
		defer close(permissionSyncDone)
		permissionSync.Run(runtimeCtx)
	}()
	// 세션 저장과 방 생성 사이에서 중단된 라이브 시작이 남긴 활성 세션을 정리한다.
	startRecovery := livedomain.NewStartRecovery(liveMongoRepo, livekitClient)
	startRecoveryDone := make(chan struct{})
	go func() {
		defer close(startRecoveryDone)
		startRecovery.Run(runtimeCtx)
	}()

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
	r.Get("/health/ready", health.ReadyHandler(projectionReadiness))
	r.Handle("/metrics", promhttp.Handler())
	r.Get("/swagger/*", httpSwagger.Handler(
		httpSwagger.URL("/swagger/doc.json"),
	))
	r.Post("/voice/webhook", webhookHandler.Handle)

	r.Group(func(r chi.Router) {
		r.Use(health.RequireReady(projectionReadiness))
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

	runtimeCancel()
	<-permissionSyncDone
	<-startRecoveryDone

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("server shutdown error", "err", err)
	}
	outboxRelay.Stop()
	if err := channelPermissionConsumer.Stop(); err != nil {
		slog.Error("channel permission consumer close error", "err", err)
	}
	if err := kafkaProducer.Close(); err != nil {
		slog.Error("kafka producer close error", "err", err)
	}
	if err := mongoClient.Disconnect(shutdownCtx); err != nil {
		slog.Error("mongodb disconnect error", "err", err)
	}

	slog.Info("shutdown complete")
	if exitCode != 0 {
		os.Exit(exitCode)
	}
}
