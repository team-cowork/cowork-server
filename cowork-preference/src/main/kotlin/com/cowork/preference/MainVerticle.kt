package com.cowork.preference

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise

class MainVerticle : AbstractVerticle() {
    override fun start(startPromise: Promise<Void>) {
        vertx.createHttpServer()
            .requestHandler { req -> req.response().end("cowork-preference") }
            .listen(8085) { result ->
                if (result.succeeded()) startPromise.complete()
                else startPromise.fail(result.cause())
            }
    }
}
