package com.cowork.gateway.deployment

import java.net.URI

internal class DeploymentRouteMatcher(destinations: Map<String, String>) {
    private val servicesByDestination = destinations.entries.associate { (service, url) ->
        val uri = URI.create(url)
        require(service.matches(Regex("cowork-[a-z-]+")))
        require(uri.host != null && uri.port > 0) { "Deployment destination must have a host and port" }
        Destination(uri.host.lowercase(), uri.port) to service
    }.also { require(it.size == destinations.size) { "Deployment destinations must be unique" } }

    fun serviceFor(uri: URI): String? = uri.takeIf {
        it.scheme == "http" || it.scheme == "https" ||
            it.scheme == "ws" || it.scheme == "wss"
    }?.let {
        servicesByDestination[Destination(uri.host?.lowercase() ?: return@let null, uri.port)]
    }

    private data class Destination(val host: String, val port: Int)
}
