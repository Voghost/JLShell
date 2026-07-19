package com.jlshell.terminal.support;

/**
 * 从终端输出流中移除一次由程序注入命令产生的输入回显。
 *
 * <p>PTY 的回显可能被拆分到多次 {@code read()} 中，因此不能对单个缓冲区做简单替换。
 * 本类会暂存与目标前缀匹配的字符，直到确认完整匹配后丢弃，或在匹配失败时原样输出。
 */
final class TerminalEchoSuppressor {

    private String expected;
    private final StringBuilder candidate = new StringBuilder();

    synchronized void suppressNext(String text) {
        String normalized = stripTrailingLineEndings(text);
        if (normalized.isEmpty()) {
            return;
        }
        expected = normalized;
        candidate.setLength(0);
    }

    synchronized String filter(char[] input, int offset, int length) {
        if (length <= 0) {
            return "";
        }

        StringBuilder output = new StringBuilder(length + candidate.length());
        for (int i = offset; i < offset + length; i++) {
            appendFiltered(input[i], output);
        }
        return output.toString();
    }

    private void appendFiltered(char value, StringBuilder output) {
        if (expected == null) {
            output.append(value);
            return;
        }

        int matched = candidate.length();
        if (value == expected.charAt(matched)) {
            candidate.append(value);
            if (candidate.length() == expected.length()) {
                // 完整命中：丢弃本次命令回显。随后的 CR/LF 保留，让新提示符仍从下一行开始。
                candidate.setLength(0);
                expected = null;
            }
            return;
        }

        if (!candidate.isEmpty()) {
            output.append(candidate);
            candidate.setLength(0);
        }

        // 当前字符也可能是目标串的新起点。
        if (value == expected.charAt(0)) {
            candidate.append(value);
        } else {
            output.append(value);
        }
    }

    private static String stripTrailingLineEndings(String text) {
        if (text == null) {
            return "";
        }
        int end = text.length();
        while (end > 0) {
            char value = text.charAt(end - 1);
            if (value != '\n' && value != '\r') {
                break;
            }
            end--;
        }
        return text.substring(0, end);
    }
}
