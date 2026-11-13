package com.cowork.gateway.legal.document

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class LegalDocumentCatalogTest :
    DescribeSpec({
        val markdown = """
            # 이용약관

            소개 문단입니다.
            둘째 줄입니다.

            - **운영 주체:** team.cowork
            - **문의:** [gsm.cowork@gmail.com](mailto:gsm.cowork@gmail.com)

            ## 제1조. 목적

            자세한 내용은 [안내](https://example.com/a?b=1&c=2)를 참고하십시오.

            ### 세부 항목

            1. 첫째
            2. 둘째 <태그> & 기호

            ## 제2조. 시행

            **김태은**(CTO)이 담당합니다.
        """.trimIndent()

        describe("LegalDocumentCatalog.render는") {
            val rendered = LegalDocumentCatalog.render(LegalDocumentType.TERMS_OF_SERVICE, markdown)

            it("마크다운 원문을 그대로 반환한다") {
                rendered.getValue(LegalDocumentFormat.MARKDOWN) shouldBe markdown + "\n"
            }

            it("plain text에서 마크다운 기호를 제거하고 링크 주소를 괄호로 표시한다") {
                rendered.getValue(LegalDocumentFormat.TEXT) shouldBe """
                    이용약관

                    소개 문단입니다.
                    둘째 줄입니다.

                    - 운영 주체: team.cowork
                    - 문의: gsm.cowork@gmail.com

                    제1조. 목적

                    자세한 내용은 안내 (https://example.com/a?b=1&c=2)를 참고하십시오.

                    세부 항목

                    1. 첫째
                    2. 둘째 <태그> & 기호

                    제2조. 시행

                    김태은(CTO)이 담당합니다.

                """.trimIndent()
            }

            it("XML을 제목과 중첩된 section 구조로 만든다") {
                val xml = rendered.getValue(LegalDocumentFormat.XML)
                xml shouldStartWith "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                xml shouldNotContain "<태그>"
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(ByteArrayInputStream(xml.toByteArray()))
                    .documentElement
                root.tagName shouldBe "document"
                root.getAttribute("type") shouldBe "terms-of-service"
                root.getElementsByTagName("title").item(0).textContent shouldBe "이용약관"
                root.getElementsByTagName("paragraph").item(0).textContent shouldBe "소개 문단입니다. 둘째 줄입니다."

                val sections = root.getElementsByTagName("section")
                sections.length shouldBe 3
                val first = sections.item(0)
                first.parentNode shouldBe root
                sections.item(1).parentNode shouldBe first
                sections.item(2).parentNode shouldBe root

                val link = root.getElementsByTagName("link").item(1)
                link.attributes.getNamedItem("href").nodeValue shouldBe "https://example.com/a?b=1&c=2"
                link.textContent shouldBe "안내"

                val lists = root.getElementsByTagName("list")
                lists.item(0).attributes.getNamedItem("ordered").nodeValue shouldBe "false"
                lists.item(1).attributes.getNamedItem("ordered").nodeValue shouldBe "true"
                lists.item(1).lastChild.previousSibling.textContent shouldBe "둘째 <태그> & 기호"
                root.getElementsByTagName("strong").item(2).textContent shouldBe "김태은"
            }
        }

        describe("지원하지 않는 문서 구조는") {
            it("제목으로 시작하지 않으면 거부한다") {
                shouldThrow<IllegalStateException> {
                    LegalDocumentCatalog.render(LegalDocumentType.LICENSE, "본문만 있습니다.")
                }
            }
            it("XML로 표현하지 않는 블록을 거부한다") {
                shouldThrow<IllegalStateException> {
                    LegalDocumentCatalog.render(LegalDocumentType.LICENSE, "# 제목\n\n> 인용문")
                }
            }
        }

        describe("LegalDocumentCatalog는") {
            it("모든 문서를 모든 형식으로 불러온다") {
                val catalog = LegalDocumentCatalog()
                for (type in LegalDocumentType.entries) {
                    for (format in LegalDocumentFormat.entries) {
                        catalog.get(type, format).isNotBlank() shouldBe true
                    }
                }
            }
        }
    })
