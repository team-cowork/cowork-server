package com.cowork.preference.domain.setting.model

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.vertx.core.json.JsonObject

class SettingDefinitionTest :
    DescribeSpec({
        describe("설정 항목 정의") {
            it("클라이언트에는 제목 설명 JSON 타입 기본값과 null 허용 여부만 반환한다") {
                val definitions = listOf(
                    SettingDefinition(Boolean::class, "사용 여부", "사용 여부를 설정합니다.", defaultValue = false) to "boolean",
                    SettingDefinition(Int::class, "크기", "크기를 설정합니다.", defaultValue = 0) to "integer",
                    SettingDefinition(String::class, "문구", "문구를 설정합니다.", nullable = true) to "string",
                    SettingDefinition(JsonObject::class, "예약", "예약을 설정합니다.") to "object",
                )

                definitions.forEach { (definition, expectedType) ->
                    val metadata = JsonObject(definition.metadata("setting").encode())

                    metadata.fieldNames() shouldBe
                        setOf("key", "title", "description", "type", "defaultValue", "nullable")
                    metadata.getString("key") shouldBe "setting"
                    metadata.getString("title") shouldBe definition.title
                    metadata.getString("description") shouldBe definition.description
                    metadata.getString("type") shouldBe expectedType
                    metadata.getValue("defaultValue") shouldBe definition.defaultValue
                    metadata.getBoolean("nullable") shouldBe definition.nullable
                }
            }

            it("타입을 확인한 뒤 커스텀 검증기에 값과 전체 설정을 전달한다") {
                var calls = 0
                val definition =
                    SettingDefinition(Int::class, "테스트 항목", "테스트 설명입니다.", defaultValue = 10) { value, settings ->
                        calls++
                        if (value > settings.getInteger("limit")) "exceeds limit" else null
                    }
                val settings = JsonObject().put("limit", 20)

                definition.validate("size", "10", settings).shouldNotBeNull()
                definition.validate("size", null, settings).shouldNotBeNull()
                calls shouldBe 0
                definition.validate("size", 10, settings).shouldBeNull()
                definition.validate("size", 21, settings) shouldBe "size exceeds limit"
                calls shouldBe 2
            }

            it("boolean integer text object 타입을 구분한다") {
                val settings = JsonObject()
                SettingDefinition(
                    Boolean::class,
                    "테스트 항목",
                    "테스트 설명입니다.",
                ).validate("value", true, settings).shouldBeNull()
                SettingDefinition(
                    Boolean::class,
                    "테스트 항목",
                    "테스트 설명입니다.",
                ).validate("value", "true", settings).shouldNotBeNull()
                SettingDefinition(Int::class, "테스트 항목", "테스트 설명입니다.").validate("value", 1, settings).shouldBeNull()
                SettingDefinition(Int::class, "테스트 항목", "테스트 설명입니다.").validate("value", 1.0, settings).shouldNotBeNull()
                SettingDefinition(String::class, "테스트 항목", "테스트 설명입니다.").validate("value", "1", settings).shouldBeNull()
                SettingDefinition(
                    String::class,
                    "테스트 항목",
                    "테스트 설명입니다.",
                ).validate("value", 1, settings).shouldNotBeNull()
                SettingDefinition(
                    JsonObject::class,
                    "테스트 항목",
                    "테스트 설명입니다.",
                ).validate("value", JsonObject(), settings).shouldBeNull()
                SettingDefinition(
                    JsonObject::class,
                    "테스트 항목",
                    "테스트 설명입니다.",
                ).validate("value", "{}", settings).shouldNotBeNull()
            }

            it("null 허용 여부를 기본값과 별도로 검사한다") {
                val definition =
                    SettingDefinition(String::class, "테스트 항목", "테스트 설명입니다.", nullable = true) { _, _ ->
                        error("unexpected validation")
                    }

                definition.validate("value", null, JsonObject()).shouldBeNull()
                SettingDefinition(Boolean::class, "테스트 항목", "테스트 설명입니다.", defaultValue = true)
                    .validate("value", null, JsonObject()).shouldNotBeNull()
            }

            it("기본값을 보충해도 false와 0과 빈 문자열은 유지한다") {
                SettingDefinition(Boolean::class, "테스트 항목", "테스트 설명입니다.", defaultValue = true).resolve(false) shouldBe
                    false
                SettingDefinition(Int::class, "테스트 항목", "테스트 설명입니다.", defaultValue = 10).resolve(0) shouldBe 0
                SettingDefinition(String::class, "테스트 항목", "테스트 설명입니다.", defaultValue = "default").resolve("") shouldBe
                    ""
                SettingDefinition(Int::class, "테스트 항목", "테스트 설명입니다.", defaultValue = 10).resolve(null) shouldBe 10
                SettingDefinition(String::class, "테스트 항목", "테스트 설명입니다.").resolve(null).shouldBeNull()
            }

            it("객체 기본값을 반환할 때 원본을 공유하지 않는다") {
                val definition =
                    SettingDefinition(
                        JsonObject::class,
                        "테스트 항목",
                        "테스트 설명입니다.",
                        defaultValue = JsonObject().put("enabled", false),
                    )
                val resolved = definition.resolve(null) as JsonObject

                resolved.put("enabled", true)

                definition.resolve(null) shouldBe JsonObject().put("enabled", false)
            }
        }
    })
