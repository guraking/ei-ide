package local.bootservers;

import com.intellij.execution.filters.Filter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;

/**
 * 로그 콘솔 줄 색칠: 줄 앞 날짜 · 시각은 회색, 레벨 낱말만 레벨 색(ERROR · WARN 은 굵게),
 * ERROR 줄과 레벨 없는 예외 머리 줄은 본문도 빨강, DEBUG 줄은 본문도 회색. 레벨 판정은 {@link LogFilter} 를 따른다.
 */
final class LogLevelFilter implements Filter {

    private static final Color INFO_COLOR = JBColor.namedColor("EILog.infoForeground", new JBColor(0x0B7DB8, 0x64D2FF));
    private static final char NEW_LINE = '\n';

    /** 레벨 글 색. 테마의 오류 · 경고 · 흐린 글 색을 따르고, INFO 는 EILog.infoForeground, 레벨이 없는 줄은 보통 글 색이다. */
    static Color colorOf(LogFilter.Level level) {
        if (level == null) {
            return UIUtil.getLabelForeground();
        }
        return switch (level) {
            case ERROR -> JBUI.CurrentTheme.Label.errorForeground();
            case WARN -> JBUI.CurrentTheme.Label.warningForeground();
            case INFO -> INFO_COLOR;
            case DEBUG -> NamedColorUtil.getInactiveTextColor();
        };
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        int base = entireLength - line.length();
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == NEW_LINE) {
            end--;
        }
        String text = line.substring(0, end);
        List<ResultItem> items = new ArrayList<>();
        Matcher stamp = LogFilter.LEADING_TIMESTAMP.matcher(text);
        int from = stamp.lookingAt() ? stamp.end() : 0;
        add(items, base, 0, from, NamedColorUtil.getInactiveTextColor(), Font.PLAIN);

        MatchResult word = LogFilter.levelWord(text);
        LogFilter.Level level = LogFilter.levelOf(text);
        if (word != null && word.start() >= from) {
            Color body = bodyColor(level);
            add(items, base, from, word.start(), body, Font.PLAIN);
            boolean strong = level == LogFilter.Level.ERROR || level == LogFilter.Level.WARN;
            add(items, base, word.start(), word.end(), colorOf(level), strong ? Font.BOLD : Font.PLAIN);
            add(items, base, word.end(), end, body, Font.PLAIN);
        }
        else if (word == null && LogFilter.isExceptionHead(text)) {
            add(items, base, from, end, colorOf(LogFilter.Level.ERROR), Font.PLAIN);
        }
        return items.isEmpty() ? null : new Result(items);
    }

    /** 본문 색: ERROR 는 빨강, DEBUG 는 회색, 그 밖은 칠하지 않는다(null). */
    private static @Nullable Color bodyColor(LogFilter.Level level) {
        return level == LogFilter.Level.ERROR || level == LogFilter.Level.DEBUG ? colorOf(level) : null;
    }

    private static void add(List<ResultItem> items, int base, int start, int end, @Nullable Color color, int fontType) {
        if (start < end && color != null) {
            items.add(new ResultItem(base + start, base + end, null, new TextAttributes(color, null, null, null, fontType)));
        }
    }
}
