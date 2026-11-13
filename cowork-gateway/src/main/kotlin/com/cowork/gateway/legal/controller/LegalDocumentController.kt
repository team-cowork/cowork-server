package com.cowork.gateway.legal.controller

import com.cowork.gateway.legal.document.LegalDocumentCatalog
import com.cowork.gateway.legal.document.LegalDocumentFormat
import com.cowork.gateway.legal.document.LegalDocumentType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.NotAcceptableStatusException
import org.springframework.web.server.ServerWebExchange
import team.themoment.sdk.response.CommonApiResponse
import java.time.Duration

@Tag(name = "약관 및 라이선스", description = "서비스 약관과 라이선스 문서를 조회합니다.")
@ApiResponses(
    ApiResponse(
        responseCode = "200",
        description = "조회 성공",
        content = [
            Content(mediaType = "text/markdown", schema = Schema(type = "string")),
            Content(mediaType = "application/xml", schema = Schema(type = "string")),
            Content(mediaType = "text/plain", schema = Schema(type = "string")),
        ],
    ),
    ApiResponse(
        responseCode = "406",
        description = "지원하지 않는 응답 형식",
        content = [
            Content(
                mediaType = "application/json",
                schema = Schema(implementation = CommonApiResponse::class),
                examples = [
                    ExampleObject(
                        value = """{"status":"406 NOT_ACCEPTABLE","code":406,"message":"지원하지 않는 응답 형식입니다."}""",
                    ),
                ],
            ),
        ],
    ),
)
@RestController
@RequestMapping("/api/legal")
class LegalDocumentController(private val catalog: LegalDocumentCatalog) {

    @Operation(summary = "서비스 라이선스 조회")
    @GetMapping("/license")
    fun license(exchange: ServerWebExchange) = respond(LegalDocumentType.LICENSE, exchange)

    @Operation(summary = "오픈소스 사용 목록 조회")
    @GetMapping("/open-source")
    fun openSource(exchange: ServerWebExchange) = respond(LegalDocumentType.OPEN_SOURCE, exchange)

    @Operation(summary = "개인정보처리방침 조회")
    @GetMapping("/privacy-policy")
    fun privacyPolicy(exchange: ServerWebExchange) = respond(LegalDocumentType.PRIVACY_POLICY, exchange)

    @Operation(summary = "서비스 이용약관 조회")
    @GetMapping("/terms-of-service")
    fun termsOfService(exchange: ServerWebExchange) = respond(LegalDocumentType.TERMS_OF_SERVICE, exchange)

    private fun respond(type: LegalDocumentType, exchange: ServerWebExchange): ResponseEntity<String> {
        val accept = try {
            exchange.request.headers.accept
        } catch (_: InvalidMediaTypeException) {
            throw NotAcceptableStatusException(LegalDocumentFormat.supportedTypes)
        }
        val format = LegalDocumentFormat.negotiate(accept)
            ?: throw NotAcceptableStatusException(LegalDocumentFormat.supportedTypes)
        return ResponseEntity.ok()
            .contentType(format.contentType)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .header(HttpHeaders.VARY, HttpHeaders.ACCEPT)
            .body(catalog.get(type, format))
    }
}
