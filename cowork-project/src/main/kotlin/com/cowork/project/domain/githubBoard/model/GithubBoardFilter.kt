package com.cowork.project.domain.githubBoard.model

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import com.cowork.project.domain.githubBoard.event.GithubProjectItemStateEvent
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * 해석한 filter 조건 하나. 같은 조건 안의 `values`는 OR, 조건끼리는 AND이며 `negated`이면 결과를 뒤집는다.
 * `qualifier`는 `assignee`, `label`, `repo`, `milestone`, `is`, `has`, `title`, `text` 중 하나이거나
 * `field`이다. `field`이면 `fieldId`에 대상 field가 있다.
 */
data class GithubBoardFilterCondition(
    val raw: String,
    val qualifier: String,
    val values: List<String>,
    val negated: Boolean = false,
    val fieldId: String? = null,
)

data class ParsedGithubBoardFilter(
    val conditions: List<GithubBoardFilterCondition>,
    val unsupportedTokens: List<String>,
)

/** 클라이언트가 구조화 파라미터로 더하는 조건. 모두 뷰 filter와 AND로 합친다. */
data class GithubBoardItemQuery(
    val assignees: List<String> = emptyList(),
    val fields: Map<String, List<String>> = emptyMap(),
    val repo: String? = null,
    val type: String? = null,
    val q: String? = null,
    val ignoreViewFilter: Boolean = false,
)

/**
 * GitHub Projects 뷰 filter 문자열을 해석한다. 문법은 https://docs.github.com/en/issues/planning-and-tracking-with-projects/customizing-views-in-your-project/filtering-projects 를 따른다.
 *
 * 지원: `assignee`, `label`, `repo`, `milestone`, `title`, 일반 텍스트, `is:open|closed|merged|issue|pr|draft`,
 * `has:`, `no:`, single-select, text, number, date, iteration field 조건, `-` 부정, 쉼표 OR, 와일드카드 `*`,
 * number와 date의 비교 연산자와 `..` 범위, `@me`, `@today`, `@current`, `@next`, `@previous`.
 *
 * 미지원: `reviewers`, `reason`, `updated`, `parent-issue`, 이슈 타입 `type`, iteration 비교와 범위, 그 밖의 field 종류.
 * 해석하지 못한 토큰은 버리지 않고 [ParsedGithubBoardFilter.unsupportedTokens]로 돌려준다.
 */
object GithubBoardFilterParser {
    private val KNOWN_QUALIFIERS = setOf("assignee", "label", "repo", "milestone", "title")
    private val IS_VALUES = setOf("open", "closed", "merged", "issue", "pr", "draft")
    private val PRESENCE_TARGETS = setOf("assignee", "label", "milestone")
    private val SUPPORTED_FIELD_TYPES = setOf("SINGLE_SELECT", "ITERATION", "TEXT", "NUMBER", "DATE")

    fun parse(filter: String?, board: GithubBoard): ParsedGithubBoardFilter {
        val conditions = mutableListOf<GithubBoardFilterCondition>()
        val unsupported = mutableListOf<String>()
        tokenize(filter.orEmpty()).forEach { token ->
            val condition = toCondition(token, board)
            if (condition == null) unsupported += token else conditions += condition
        }
        return ParsedGithubBoardFilter(conditions, unsupported)
    }

    private fun toCondition(token: String, board: GithubBoard): GithubBoardFilterCondition? {
        val negated = token.startsWith("-")
        val body = token.removePrefix("-")
        val separator = body.indexOf(':')
        if (separator <= 0) {
            val text = unquote(body)
            return GithubBoardFilterCondition(token, "text", listOf(text), negated).takeIf { text.isNotEmpty() }
        }
        val qualifier = unquote(body.substring(0, separator)).lowercase(Locale.ROOT)
        val values = splitValues(body.substring(separator + 1))
        if (values.isEmpty()) return null
        return when {
            qualifier in KNOWN_QUALIFIERS -> GithubBoardFilterCondition(token, qualifier, values, negated)
            qualifier == "is" -> isCondition(token, values, negated)
            qualifier == "has" || qualifier == "no" -> presenceCondition(token, qualifier, values, negated, board)
            else -> fieldCondition(token, qualifier, values, negated, board)
        }
    }

    private fun isCondition(token: String, values: List<String>, negated: Boolean): GithubBoardFilterCondition? {
        val normalized = values.map { it.lowercase(Locale.ROOT) }
        if (!IS_VALUES.containsAll(normalized)) return null
        return GithubBoardFilterCondition(token, "is", normalized, negated)
    }

    // `no:X`는 `-has:X`와 같다. `-no:X`는 다시 뒤집혀 `has:X`가 된다.
    private fun presenceCondition(
        token: String,
        qualifier: String,
        values: List<String>,
        negated: Boolean,
        board: GithubBoard,
    ): GithubBoardFilterCondition? {
        val targets = values.map { target ->
            val key = target.lowercase(Locale.ROOT)
            when {
                key in PRESENCE_TARGETS -> key
                else -> board.fieldByName(target)?.takeIf { it.dataType in SUPPORTED_FIELD_TYPES }?.id ?: return null
            }
        }
        return GithubBoardFilterCondition(token, "has", targets, negated xor (qualifier == "no"))
    }

    private fun fieldCondition(
        token: String,
        qualifier: String,
        values: List<String>,
        negated: Boolean,
        board: GithubBoard,
    ): GithubBoardFilterCondition? {
        val field = board.fieldByName(qualifier) ?: return null
        if (field.dataType !in SUPPORTED_FIELD_TYPES) return null
        val valid = values.all { value ->
            when (field.dataType) {
                "NUMBER" -> GithubBoardFieldMatcher.numberPredicate(value) != null
                "DATE" -> GithubBoardFieldMatcher.datePredicate(value, LocalDate.EPOCH) != null
                "ITERATION" -> !value.startsWith(">") && !value.startsWith("<") && ".." !in value
                else -> true
            }
        }
        if (!valid) return null
        return GithubBoardFilterCondition(token, "field", values, negated, field.id)
    }

    /** 공백으로 토큰을 나누되 큰따옴표 안의 공백은 유지한다. */
    internal fun tokenize(filter: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        filter.forEach { char ->
            when {
                char == '"' -> {
                    quoted = !quoted
                    current.append(char)
                }

                char.isWhitespace() && !quoted -> {
                    if (current.isNotEmpty()) tokens += current.toString()
                    current.clear()
                }

                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** 쉼표로 값을 나누되 큰따옴표 안의 쉼표는 유지하고, 따옴표는 지운다. */
    internal fun splitValues(raw: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        raw.forEach { char ->
            when {
                char == '"' -> quoted = !quoted

                char == ',' && !quoted -> {
                    values += current.toString()
                    current.clear()
                }

                else -> current.append(char)
            }
        }
        values += current.toString()
        return values.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun unquote(value: String): String = value.trim().removeSurrounding("\"")
}

/** field 값과 filter 값을 비교한다. 날짜와 iteration 키워드는 `today` 기준으로 해석한다. */
object GithubBoardFieldMatcher {
    private val RELATIVE_DATE = Regex("^@today(?:([+-])(\\d+)([dw]))?$")

    fun glob(pattern: String, value: String): Boolean {
        if ('*' !in pattern) return pattern.equals(value, ignoreCase = true)
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex$", RegexOption.IGNORE_CASE).matches(value)
    }

    fun numberPredicate(raw: String): ((Double) -> Boolean)? {
        val range = raw.split("..")
        if (range.size == 2) {
            val low = range[0].takeUnless { it == "*" }?.let { it.toDoubleOrNull() ?: return null }
            val high = range[1].takeUnless { it == "*" }?.let { it.toDoubleOrNull() ?: return null }
            return { value -> (low == null || value >= low) && (high == null || value <= high) }
        }
        val (operator, operand) = splitOperator(raw)
        val number = operand.toDoubleOrNull() ?: return null
        return comparison(operator, number)
    }

    fun datePredicate(raw: String, today: LocalDate): ((LocalDate) -> Boolean)? {
        val range = raw.split("..")
        if (range.size == 2) {
            val low = range[0].takeUnless { it == "*" }?.let { parseDate(it, today) ?: return null }
            val high = range[1].takeUnless { it == "*" }?.let { parseDate(it, today) ?: return null }
            return { value -> (low == null || !value.isBefore(low)) && (high == null || !value.isAfter(high)) }
        }
        val (operator, operand) = splitOperator(raw)
        val date = parseDate(operand, today) ?: return null
        return { value ->
            val order = value.compareTo(date)
            when (operator) {
                ">" -> order > 0
                ">=" -> order >= 0
                "<" -> order < 0
                "<=" -> order <= 0
                else -> order == 0
            }
        }
    }

    private fun splitOperator(raw: String): Pair<String, String> {
        val operator = listOf(">=", "<=", ">", "<").firstOrNull { raw.startsWith(it) } ?: ""
        return operator to raw.removePrefix(operator)
    }

    private fun comparison(operator: String, operand: Double): (Double) -> Boolean = when (operator) {
        ">" -> { value -> value > operand }
        ">=" -> { value -> value >= operand }
        "<" -> { value -> value < operand }
        "<=" -> { value -> value <= operand }
        else -> { value -> value == operand }
    }

    private fun parseDate(raw: String, today: LocalDate): LocalDate? {
        RELATIVE_DATE.matchEntire(raw)?.let { match ->
            val (sign, amount, unit) = match.destructured
            if (sign.isEmpty()) return today
            val days = amount.toLong() * if (unit == "w") 7 else 1
            return if (sign == "+") today.plusDays(days) else today.minusDays(days)
        }
        return try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** `@current`, `@next`, `@previous`가 가리키는 iteration ID. 해당 iteration이 없으면 null이다. */
    fun iterationKeyword(field: GithubProjectField, keyword: String, today: LocalDate): String? {
        val iterations = field.iterations.sortedBy { it.startDate }
        val currentIndex = iterations.indexOfFirst { iteration ->
            val start = runCatching { LocalDate.parse(iteration.startDate) }.getOrNull() ?: return@indexOfFirst false
            !today.isBefore(start) && today.isBefore(start.plusDays(iteration.duration.toLong()))
        }
        val index = when (keyword.lowercase(Locale.ROOT)) {
            "@current" -> currentIndex

            "@next" -> if (currentIndex >=
                0
            ) {
                currentIndex + 1
            } else {
                iterations.indexOfFirst { it.startDate > today.toString() }
            }

            "@previous" -> if (currentIndex >=
                0
            ) {
                currentIndex - 1
            } else {
                iterations.indexOfLast { it.startDate < today.toString() }
            }

            else -> return null
        }
        return iterations.getOrNull(index)?.id
    }
}

/** 해석한 조건과 구조화 파라미터를 item에 적용한다. */
class GithubBoardFilterEvaluator(
    private val board: GithubBoard,
    private val requesterLogin: String?,
    private val today: LocalDate,
) {
    fun matches(item: GithubProjectItemStateEvent, condition: GithubBoardFilterCondition): Boolean =
        matchesUnsigned(item, condition) != condition.negated

    fun matches(item: GithubProjectItemStateEvent, query: GithubBoardItemQuery): Boolean {
        if (query.assignees.isNotEmpty() && query.assignees.none { matchesAssignee(item, it) }) return false
        if (query.type != null && !item.type.equals(query.type, ignoreCase = true)) return false
        if (query.repo != null && !matchesRepo(item, query.repo)) return false
        if (query.q != null && !item.title().contains(query.q, ignoreCase = true)) return false
        return query.fields.all { (name, values) ->
            val field = board.fieldByName(name) ?: return false
            matchesField(item, field, values)
        }
    }

    private fun matchesUnsigned(item: GithubProjectItemStateEvent, condition: GithubBoardFilterCondition): Boolean {
        val content = item.content
        val values = condition.values
        return when (condition.qualifier) {
            "assignee" -> values.any { matchesAssignee(item, it) }

            "label" -> values.any { pattern ->
                content?.labels.orEmpty().any { GithubBoardFieldMatcher.glob(pattern, it.name) }
            }

            "repo" -> values.any { matchesRepo(item, it) }

            "milestone" -> values.any { pattern ->
                content?.milestone?.let { GithubBoardFieldMatcher.glob(pattern, it) } ==
                    true
            }

            "title" -> values.any { GithubBoardFieldMatcher.glob(it, item.title()) }

            "text" -> values.any { item.title().contains(it.replace("*", ""), ignoreCase = true) }

            "is" -> values.any { matchesIs(item, it) }

            "has" -> values.any { hasValue(item, it) }

            "field" -> board.field(condition.fieldId.orEmpty())?.let { matchesField(item, it, values) } == true

            else -> false
        }
    }

    private fun matchesAssignee(item: GithubProjectItemStateEvent, value: String): Boolean {
        val login = if (value.equals("@me", ignoreCase = true) || value.equals("me", ignoreCase = true)) {
            requesterLogin?.takeIf { it.isNotBlank() } ?: return false
        } else {
            value
        }
        return item.content?.assignees.orEmpty().any { GithubBoardFieldMatcher.glob(login, it) }
    }

    private fun matchesRepo(item: GithubProjectItemStateEvent, value: String): Boolean {
        val repository = item.content?.repository ?: return false
        return GithubBoardFieldMatcher.glob(value, repository) ||
            ('/' !in value && GithubBoardFieldMatcher.glob(value, repository.substringAfter('/')))
    }

    private fun matchesIs(item: GithubProjectItemStateEvent, value: String): Boolean {
        val state = item.content?.state?.uppercase(Locale.ROOT)
        return when (value) {
            "open" -> state == "OPEN"
            "closed" -> state == "CLOSED" || state == "MERGED"
            "merged" -> state == "MERGED"
            "issue" -> item.type == "ISSUE"
            "pr" -> item.type == "PULL_REQUEST"
            "draft" -> item.type == "DRAFT_ISSUE" || (item.type == "PULL_REQUEST" && item.content?.draft == true)
            else -> false
        }
    }

    private fun hasValue(item: GithubProjectItemStateEvent, target: String): Boolean = when (target) {
        "assignee" -> item.content?.assignees.orEmpty().isNotEmpty()
        "label" -> item.content?.labels.orEmpty().isNotEmpty()
        "milestone" -> item.content?.milestone != null
        else -> item.value(target)?.let { it.text ?: it.number ?: it.date ?: it.optionId ?: it.iterationId } != null
    }

    private fun matchesField(
        item: GithubProjectItemStateEvent,
        field: GithubProjectField,
        values: List<String>,
    ): Boolean {
        val value = item.value(field.id)
        return when (field.dataType) {
            "SINGLE_SELECT" -> {
                val optionName = field.options.firstOrNull { it.id == value?.optionId }?.name ?: return false
                values.any { GithubBoardFieldMatcher.glob(it, optionName) }
            }

            "ITERATION" -> {
                val iterationId = value?.iterationId ?: return false
                values.any { pattern ->
                    if (pattern.startsWith("@")) {
                        GithubBoardFieldMatcher.iterationKeyword(field, pattern, today) == iterationId
                    } else {
                        field.iterations.firstOrNull {
                            it.id == iterationId
                        }?.let { GithubBoardFieldMatcher.glob(pattern, it.title) } ==
                            true
                    }
                }
            }

            "TEXT" -> {
                val text = value?.text ?: return false
                values.any { GithubBoardFieldMatcher.glob(it, text) }
            }

            "NUMBER" -> {
                val number = value?.number ?: return false
                values.any { GithubBoardFieldMatcher.numberPredicate(it)?.invoke(number) == true }
            }

            "DATE" -> {
                val date = value?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
                values.any { GithubBoardFieldMatcher.datePredicate(it, today)?.invoke(date) == true }
            }

            else -> false
        }
    }
}

fun GithubProjectItemStateEvent.title(): String = content?.title.orEmpty()
