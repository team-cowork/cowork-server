package com.cowork.roadmap.domain.node.service.support;

import java.util.List;

import org.springframework.http.HttpStatus;

import team.themoment.sdk.exception.ExpectedException;

/**
 * 노드 본문(Markdown)의 링크 목적지가 실행 가능한 스킴으로 시작하는지 검사한다. Markdown을 파싱하지 않고 목적지가 시작될 수
 * 있는 자리({@code ](}, {@code ]:}, {@code <})만 보므로, 코드 블록 안의 같은 표기도 거부한다.
 */
public final class NodeContentLinkPolicy {

    private static final List<String> BLOCKED_SCHEMES = List.of("javascript:", "data:", "vbscript:");

    private NodeContentLinkPolicy() {
    }

    public static void validate(String content) {
        if (content != null && hasBlockedLink(content)) {
            throw new ExpectedException("노드 본문에는 javascript, data, vbscript 링크를 넣을 수 없습니다.", HttpStatus.BAD_REQUEST);
        }
    }

    private static boolean hasBlockedLink(String content) {
        for (int i = 0; i < content.length(); i++) {
            if (isDestinationStart(content, i) && startsWithBlockedScheme(content, i + 1)) {
                return true;
            }
        }
        return false;
    }

    /** 인라인 링크 {@code ](}, 참조 정의 {@code ]:}, 자동 링크와 꺾쇠 목적지 {@code <}. */
    private static boolean isDestinationStart(String content, int index) {
        char c = content.charAt(index);
        if (c == '<') {
            return true;
        }
        return (c == '(' || c == ':') && index > 0 && content.charAt(index - 1) == ']';
    }

    /**
     * 렌더러와 브라우저가 스킴을 읽는 방식에 맞춰 비교한다. 문자 참조와 백슬래시 이스케이프를 풀고, 공백과 제어 문자를 건너뛰고, 대소문자를
     * 구분하지 않는다. 스킴의 접두사가 아니게 되면 바로 멈추므로 본문 전체에 대해 선형 시간이다.
     */
    private static boolean startsWithBlockedScheme(String content, int start) {
        StringBuilder scheme = new StringBuilder();
        int index = start;
        while (index < content.length()) {
            Decoded decoded = decode(content, index);
            index = decoded.next();
            int codePoint = decoded.codePoint();
            if (codePoint <= ' ' || codePoint == 0x7F) {
                continue;
            }
            scheme.appendCodePoint(codePoint >= 'A' && codePoint <= 'Z' ? codePoint + ('a' - 'A') : codePoint);
            String prefix = scheme.toString();
            if (BLOCKED_SCHEMES.contains(prefix)) {
                return true;
            }
            if (BLOCKED_SCHEMES.stream().noneMatch(blocked -> blocked.startsWith(prefix))) {
                return false;
            }
        }
        return false;
    }

    private static Decoded decode(String content, int index) {
        char c = content.charAt(index);
        if (c == '\\' && index + 1 < content.length() && isAsciiPunctuation(content.charAt(index + 1))) {
            return new Decoded(content.charAt(index + 1), index + 2);
        }
        if (c == '&') {
            Decoded reference = decodeCharacterReference(content, index);
            if (reference != null) {
                return reference;
            }
        }
        return new Decoded(c, index + 1);
    }

    private static Decoded decodeCharacterReference(String content, int index) {
        if (content.startsWith("&colon;", index)) {
            return new Decoded(':', index + "&colon;".length());
        }
        if (content.startsWith("&Tab;", index)) {
            return new Decoded('\t', index + "&Tab;".length());
        }
        if (content.startsWith("&NewLine;", index)) {
            return new Decoded('\n', index + "&NewLine;".length());
        }
        if (!content.startsWith("&#", index)) {
            return null;
        }
        int cursor = index + 2;
        int radix = 10;
        if (cursor < content.length() && (content.charAt(cursor) == 'x' || content.charAt(cursor) == 'X')) {
            radix = 16;
            cursor++;
        }
        int digitsStart = cursor;
        long value = 0;
        while (cursor < content.length() && Character.digit(content.charAt(cursor), radix) >= 0) {
            value = Math.min(value * radix + Character.digit(content.charAt(cursor), radix),
                    Character.MAX_CODE_POINT + 1L);
            cursor++;
        }
        if (cursor == digitsStart) {
            return null;
        }
        if (cursor < content.length() && content.charAt(cursor) == ';') {
            cursor++;
        }
        return new Decoded(value > Character.MAX_CODE_POINT ? 0xFFFD : (int) value, cursor);
    }

    private static boolean isAsciiPunctuation(char c) {
        return c >= '!' && c <= '~' && !Character.isLetterOrDigit(c);
    }

    private record Decoded(int codePoint, int next) {
    }
}
