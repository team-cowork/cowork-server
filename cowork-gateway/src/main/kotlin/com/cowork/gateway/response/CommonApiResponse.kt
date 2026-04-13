package com.cowork.gateway.response

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.HttpStatus

data class CommonApiResponse<T>(
    val status: String,
    val code: Int,
    val message: String,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val data: T? = null,
) {
    companion object {
        fun <T> success(data: T): CommonApiResponse<T> = CommonApiResponse(
            status = HttpStatus.OK.name,
            code = HttpStatus.OK.value(),
            message = "OK",
            data = data,
        )

        fun noContent(): CommonApiResponse<Nothing> = CommonApiResponse(
            status = HttpStatus.NO_CONTENT.name,
            code = HttpStatus.NO_CONTENT.value(),
            message = "No Content",
        )

        fun error(httpStatus: HttpStatus, message: String): CommonApiResponse<Nothing> = CommonApiResponse(
            status = httpStatus.name,
            code = httpStatus.value(),
            message = message,
        )
    }
}
