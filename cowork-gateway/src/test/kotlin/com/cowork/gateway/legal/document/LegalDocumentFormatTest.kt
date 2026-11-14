package com.cowork.gateway.legal.document

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.MediaType

class LegalDocumentFormatTest :
    DescribeSpec({
        fun negotiate(accept: String) = LegalDocumentFormat.negotiate(MediaType.parseMediaTypes(accept))

        describe("LegalDocumentFormat.negotiate는") {
            context("Accept 헤더가 없거나 와일드카드뿐이면") {
                it("마크다운을 선택한다") {
                    LegalDocumentFormat.negotiate(emptyList()) shouldBe LegalDocumentFormat.MARKDOWN
                    negotiate("*/*") shouldBe LegalDocumentFormat.MARKDOWN
                    negotiate("text/*") shouldBe LegalDocumentFormat.MARKDOWN
                }
            }
            context("지원하는 형식을 지정하면") {
                it("해당 형식을 선택한다") {
                    negotiate("text/markdown") shouldBe LegalDocumentFormat.MARKDOWN
                    negotiate("text/plain") shouldBe LegalDocumentFormat.TEXT
                    negotiate("application/xml") shouldBe LegalDocumentFormat.XML
                    negotiate("text/xml") shouldBe LegalDocumentFormat.XML
                }
            }
            context("여러 형식을 지정하면") {
                it("품질 값, 구체성, 나열 순서 순으로 고른다") {
                    negotiate("text/markdown;q=0.5, application/xml") shouldBe LegalDocumentFormat.XML
                    negotiate("text/*, text/plain") shouldBe LegalDocumentFormat.TEXT
                    negotiate("text/plain, text/markdown") shouldBe LegalDocumentFormat.TEXT
                    negotiate("application/json, text/plain;q=0.1") shouldBe LegalDocumentFormat.TEXT
                }
            }
            context("q=0으로 제외하면") {
                it("제외한 형식을 고르지 않는다") {
                    negotiate("text/markdown;q=0, */*") shouldBe LegalDocumentFormat.TEXT
                    negotiate("*/*;q=0").shouldBeNull()
                }
            }
            context("지원하지 않는 형식만 지정하면") {
                it("null을 반환한다") {
                    negotiate("application/json").shouldBeNull()
                    negotiate("text/html, image/*").shouldBeNull()
                }
            }
        }
    })
