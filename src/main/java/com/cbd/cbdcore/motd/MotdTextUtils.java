package com.cbd.cbdcore.motd;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 레거시 '&' 색상 코드와 MiniMessage 태그(그라데이션 등)를 함께 사용할 수 있도록
 * 변환하고, 기본 마인크래프트 폰트 기준으로 텍스트를 가운데 정렬하는 유틸리티.
 */
public final class MotdTextUtils {

    // MOTD에는 색상/서식/그라데이션 정도만 필요하므로, 클릭/호버/NBT/newline 등
    // 잠재적으로 의도치 않은 동작을 유발할 수 있는 태그는 허용하지 않는다.
    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolver(StandardTags.color())
                    .resolver(StandardTags.decorations())
                    .resolver(StandardTags.gradient())
                    .resolver(StandardTags.rainbow())
                    .resolver(StandardTags.transition())
                    .resolver(StandardTags.reset())
                    .build())
            .build();

    private static final Map<Character, String> COLOR_TAGS = new HashMap<>();
    private static final Map<Character, String> FORMAT_TAGS = new HashMap<>();

    static {
        COLOR_TAGS.put('0', "black");
        COLOR_TAGS.put('1', "dark_blue");
        COLOR_TAGS.put('2', "dark_green");
        COLOR_TAGS.put('3', "dark_aqua");
        COLOR_TAGS.put('4', "dark_red");
        COLOR_TAGS.put('5', "dark_purple");
        COLOR_TAGS.put('6', "gold");
        COLOR_TAGS.put('7', "gray");
        COLOR_TAGS.put('8', "dark_gray");
        COLOR_TAGS.put('9', "blue");
        COLOR_TAGS.put('a', "green");
        COLOR_TAGS.put('b', "aqua");
        COLOR_TAGS.put('c', "red");
        COLOR_TAGS.put('d', "light_purple");
        COLOR_TAGS.put('e', "yellow");
        COLOR_TAGS.put('f', "white");

        FORMAT_TAGS.put('k', "obfuscated");
        FORMAT_TAGS.put('l', "bold");
        FORMAT_TAGS.put('m', "strikethrough");
        FORMAT_TAGS.put('n', "underlined");
        FORMAT_TAGS.put('o', "italic");
    }

    // 기본 마인크래프트 폰트(default.json)의 대략적인 문자 픽셀 너비.
    // 정확한 렌더링 폭은 클라이언트/폰트/유니코드 여부에 따라 달라질 수 있어 근사치입니다.
    private static final Map<Character, Integer> CHAR_WIDTH = new HashMap<>();
    private static final int DEFAULT_WIDTH = 6;
    private static final int SPACE_WIDTH = 4;
    private static final int WIDE_CHAR_WIDTH = 9; // 한글/한자/가나 등 전각 글리프 근사치
    private static final int BOLD_EXTRA_WIDTH = 1; // 마인크래프트는 굵게 처리 시 글자당 1px 더 그림

    static {
        String narrow2 = " !',.:;i|";
        String width3 = "l`'\"";
        String width4 = "I[]t";
        String width5 = "()*<>fk{}";
        String width7 = "@~";

        for (char c : narrow2.toCharArray()) {
            CHAR_WIDTH.put(c, 2);
        }
        for (char c : width3.toCharArray()) {
            CHAR_WIDTH.put(c, 3);
        }
        for (char c : width4.toCharArray()) {
            CHAR_WIDTH.put(c, 4);
        }
        for (char c : width5.toCharArray()) {
            CHAR_WIDTH.put(c, 5);
        }
        for (char c : width7.toCharArray()) {
            CHAR_WIDTH.put(c, 7);
        }
        CHAR_WIDTH.put(' ', SPACE_WIDTH);
    }

    private MotdTextUtils() {
    }

    /**
     * 레거시 '&' 코드를 MiniMessage 태그로 변환한 뒤, 색상/서식/그라데이션 태그만
     * 허용하는 제한된 MiniMessage 인스턴스로 역직렬화합니다.
     * MiniMessage 태그(예: {@code <gradient:#ff8ec7:#a0d8ff>...</gradient>})는
     * 그대로 통과하므로 기존 '&' 설정과 그라데이션을 함께 사용할 수 있습니다.
     */
    public static Component deserialize(String raw) {
        return MINI_MESSAGE.deserialize(convertLegacyToMiniMessage(raw));
    }

    private static String convertLegacyToMiniMessage(String raw) {
        StringBuilder out = new StringBuilder();
        Deque<String> openFormats = new ArrayDeque<>();
        String openColor = null;

        int i = 0;
        int len = raw.length();
        while (i < len) {
            char c = raw.charAt(i);

            // 원본에 이미 존재하는 MiniMessage 태그(<...>)는 통째로 하나의 경계로 취급한다.
            // 이 경계를 지나기 전에 현재 열려 있는 레거시 파생 태그를 모두 닫고,
            // 경계를 지난 직후 동일한 순서로 다시 열어서 두 태그 체계가 서로 교차(invalid nesting)하지 않도록 한다.
            if (c == '<') {
                int closeIdx = raw.indexOf('>', i);
                if (closeIdx == -1) {
                    out.append(c);
                    i++;
                    continue;
                }

                String passthroughTag = raw.substring(i, closeIdx + 1);

                List<String> formatSnapshot = new ArrayList<>(openFormats);
                closeAll(out, openFormats);

                boolean hadColor = openColor != null;
                if (hadColor) {
                    out.append("</").append(openColor).append(">");
                }

                out.append(passthroughTag);

                if (hadColor) {
                    out.append("<").append(openColor).append(">");
                }
                for (int j = formatSnapshot.size() - 1; j >= 0; j--) {
                    String tag = formatSnapshot.get(j);
                    out.append("<").append(tag).append(">");
                    openFormats.push(tag);
                }

                i = closeIdx + 1;
                continue;
            }

            if (c == '&' && i + 1 < len) {
                char next = Character.toLowerCase(raw.charAt(i + 1));

                if (next == '#' && i + 8 <= len && isHex(raw, i + 2, 6)) {
                    String hex = raw.substring(i + 2, i + 8);
                    closeAll(out, openFormats);
                    if (openColor != null) {
                        out.append("</").append(openColor).append(">");
                    }
                    openColor = "#" + hex;
                    out.append("<").append(openColor).append(">");
                    i += 8;
                    continue;
                }

                if (COLOR_TAGS.containsKey(next)) {
                    closeAll(out, openFormats);
                    if (openColor != null) {
                        out.append("</").append(openColor).append(">");
                    }
                    openColor = COLOR_TAGS.get(next);
                    out.append("<").append(openColor).append(">");
                    i += 2;
                    continue;
                }

                if (next == 'r') {
                    closeAll(out, openFormats);
                    if (openColor != null) {
                        out.append("</").append(openColor).append(">");
                        openColor = null;
                    }
                    i += 2;
                    continue;
                }

                if (FORMAT_TAGS.containsKey(next)) {
                    String tag = FORMAT_TAGS.get(next);
                    out.append("<").append(tag).append(">");
                    openFormats.push(tag);
                    i += 2;
                    continue;
                }
            }

            out.append(c);
            i++;
        }

        closeAll(out, openFormats);
        if (openColor != null) {
            out.append("</").append(openColor).append(">");
        }

        return out.toString();
    }

    private static boolean isHex(String s, int start, int length) {
        if (start + length > s.length()) {
            return false;
        }
        for (int i = start; i < start + length; i++) {
            if (Character.digit(s.charAt(i), 16) == -1) {
                return false;
            }
        }
        return true;
    }

    private static void closeAll(StringBuilder out, Deque<String> openFormats) {
        while (!openFormats.isEmpty()) {
            out.append("</").append(openFormats.pop()).append(">");
        }
    }

    /**
     * 렌더링된 텍스트(색상 제외, 굵게 서식 및 한글/한자 등 전각 문자 폭은 반영)의
     * 대략적인 픽셀 너비를 계산합니다.
     */
    public static int measureWidth(Component component) {
        return measureRecursive(component, false);
    }

    private static int measureRecursive(Component component, boolean parentBold) {
        boolean bold = resolveBold(component, parentBold);
        int width = measureOwnContent(component, bold);

        for (Component child : component.children()) {
            width += measureRecursive(child, bold);
        }

        return width;
    }

    private static boolean resolveBold(Component component, boolean parentBold) {
        TextDecoration.State state = component.decoration(TextDecoration.BOLD);
        return switch (state) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> parentBold;
        };
    }

    private static int measureOwnContent(Component component, boolean bold) {
        return measureText(contentOf(component), bold);
    }

    private static String contentOf(Component component) {
        if (component instanceof TextComponent textComponent) {
            return textComponent.content();
        }
        return "";
    }

    private static int measureText(String text, boolean bold) {
        int width = 0;
        int i = 0;
        int len = text.length();
        while (i < len) {
            int codePoint = text.codePointAt(i);
            width += glyphWidth(codePoint);
            if (bold && codePoint != ' ') {
                width += BOLD_EXTRA_WIDTH;
            }
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static int glyphWidth(int codePoint) {
        if (codePoint <= Character.MAX_VALUE) {
            Integer known = CHAR_WIDTH.get((char) codePoint);
            if (known != null) {
                return known;
            }
        }

        if (isWideCharacter(codePoint)) {
            return WIDE_CHAR_WIDTH;
        }

        return DEFAULT_WIDTH;
    }

    /**
     * 한글, 한자, 가나 등 마인크래프트 유니코드 폰트가 전각으로 그리는 문자 범위인지 근사 판별한다.
     */
    private static boolean isWideCharacter(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x11FF)   // Hangul Jamo
                || (codePoint >= 0x3000 && codePoint <= 0x303F) // CJK Symbols and Punctuation
                || (codePoint >= 0x3040 && codePoint <= 0x30FF) // Hiragana, Katakana
                || (codePoint >= 0x3130 && codePoint <= 0x318F) // Hangul Compatibility Jamo
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF) // CJK Unified Ideographs Extension A
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF) // CJK Unified Ideographs
                || (codePoint >= 0xA960 && codePoint <= 0xA97F) // Hangul Jamo Extended-A
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3) // Hangul Syllables
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF) // CJK Compatibility Ideographs
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF); // Halfwidth and Fullwidth Forms
    }

    /**
     * 대상 너비(targetWidth, px)를 기준으로 텍스트 앞에 공백을 추가하여 가운데 정렬합니다.
     * 이미 대상 너비 이상이면 원본을 그대로 반환합니다.
     */
    public static Component center(Component component, int targetWidth) {
        int width = measureWidth(component);
        if (width >= targetWidth) {
            return component;
        }

        int paddingWidth = (targetWidth - width) / 2;
        int spaceCount = paddingWidth / SPACE_WIDTH;
        if (spaceCount <= 0) {
            return component;
        }

        return Component.text(" ".repeat(spaceCount)).append(component);
    }
}
