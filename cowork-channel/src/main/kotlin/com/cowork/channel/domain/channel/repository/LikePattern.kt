package com.cowork.channel.domain.channel.repository

/** LIKE 검색어의 메타문자를 [ESCAPE] 문자로 이스케이프한다. 쿼리는 `ESCAPE '!'`를 함께 지정해야 한다. */
object LikePattern {
    const val ESCAPE = '!'

    fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            if (char == ESCAPE || char == '%' || char == '_') append(ESCAPE)
            append(char)
        }
    }
}
