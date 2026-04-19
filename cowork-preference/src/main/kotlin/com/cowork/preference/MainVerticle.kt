package com.cowork.preference

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise

class MainVerticle : AbstractVerticle() {
    override fun start(startPromise: Promise<Void>) {
        // TODO: vertx-config를 통해 설정 파일에서 포트를 동적으로 읽도록 개선 필요
        vertx.createHttpServer()
            .requestHandler { req -> req.response().end("cowork-preference") }
            .listen(8085) { result ->
                if (result.succeeded()) startPromise.complete()
                else startPromise.fail(result.cause())
            }
    }
}
