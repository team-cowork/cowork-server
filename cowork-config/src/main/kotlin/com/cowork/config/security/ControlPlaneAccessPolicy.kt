package com.cowork.config.security

/** The Config environment endpoint and Eureka operations used by our clients are allowlisted. */
class ControlPlaneAccessPolicy {
    fun allows(application: String, profile: String, method: String, path: String): Boolean {
        if (method == "GET" && path == "/actuator/health") return true
        if (application == "monitoring") {
            return method == "GET" && (path == "/actuator/prometheus" || isRegistryRead(path))
        }
        if (method == "GET" && isRegistryRead(path)) return true
        if (method == "GET" && (path == "/$application/default" || path == "/$application/$profile")) {
            return true
        }
        val parts = path.split('/')
        if (parts.size !in 4..6 || parts[1] != "eureka" || parts[2] != "apps") return false
        if (!parts[3].equals(application, ignoreCase = true)) return false
        if (parts.size == 4) return method == "POST"
        if (!INSTANCE_ID.matches(parts[4])) return false
        if (parts.size == 5) return method == "PUT" || method == "DELETE"
        return parts[5] == "status" && (method == "PUT" || method == "DELETE")
    }

    fun allowsRegistration(application: String, registeredApp: String?, vip: String?, secureVip: String?): Boolean =
        application != "monitoring" &&
            application.equals(registeredApp, ignoreCase = true) &&
            application.equals(vip, ignoreCase = true) &&
            (secureVip.isNullOrEmpty() || application.equals(secureVip, ignoreCase = true))

    private fun isRegistryRead(path: String): Boolean =
        path == "/eureka/apps" || path == "/eureka/apps/" || path == "/eureka/apps/delta" ||
            REGISTRY_LOOKUP.matches(path)

    companion object {
        private val INSTANCE_ID = Regex("[a-zA-Z0-9._:@-]{1,255}")
        private val REGISTRY_LOOKUP = Regex("/eureka/(apps|vips|svips)/[a-zA-Z0-9-]+")
    }
}
