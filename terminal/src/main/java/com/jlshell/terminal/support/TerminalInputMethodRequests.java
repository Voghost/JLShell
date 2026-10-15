package com.jlshell.terminal.support;

import java.awt.Rectangle;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;

/** 兼容 macOS CInputMethod 对 committed-text 查询必须返回迭代器的要求。 */
final class TerminalInputMethodRequests implements InputMethodRequests {
    private final InputMethodRequests delegate;

    TerminalInputMethodRequests(InputMethodRequests delegate) {
        this.delegate = delegate;
    }

    @Override
    public AttributedCharacterIterator getCommittedText(int beginIndex, int endIndex,
                                                       AttributedCharacterIterator.Attribute[] attributes) {
        AttributedCharacterIterator text = delegate.getCommittedText(beginIndex, endIndex, attributes);
        // JediTerm 3.61 不维护可重转换的已提交文本（length=0），却返回 null。
        // macOS CInputMethod 会直接调用 getEndIndex，必须给它一个独立的空迭代器。
        // 不读取终端输出/历史作为输入法上下文，避免改变提交语义或泄露终端内容。
        return text != null ? text : new AttributedString("").getIterator();
    }

    @Override public Rectangle getTextLocation(TextHitInfo offset) { return delegate.getTextLocation(offset); }
    @Override public TextHitInfo getLocationOffset(int x, int y) { return delegate.getLocationOffset(x, y); }
    @Override public int getInsertPositionOffset() { return delegate.getInsertPositionOffset(); }
    @Override public int getCommittedTextLength() { return delegate.getCommittedTextLength(); }
    @Override public AttributedCharacterIterator cancelLatestCommittedText(AttributedCharacterIterator.Attribute[] attributes) {
        return delegate.cancelLatestCommittedText(attributes);
    }
    @Override public AttributedCharacterIterator getSelectedText(AttributedCharacterIterator.Attribute[] attributes) {
        return delegate.getSelectedText(attributes);
    }
}
