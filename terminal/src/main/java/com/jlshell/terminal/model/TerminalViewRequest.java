package com.jlshell.terminal.model;

import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.ShellRequest;

/**
 * 创建终端视图时的参数。
 */
public record TerminalViewRequest(
        String title,
        ShellRequest shellRequest,
        FontProfile fontProfile,
        TerminalColorScheme colorScheme
) {

    public TerminalViewRequest {
        title = title == null || title.isBlank() ? "SSH Terminal" : title;
        shellRequest = shellRequest == null ? new ShellRequest(null, null, null) : shellRequest;
        colorScheme = colorScheme == null ? TerminalColorScheme.dark() : colorScheme;
    }

    public TerminalViewRequest withResolvedFontProfile(FontProfile resolvedFontProfile) {
        return new TerminalViewRequest(title, shellRequest, resolvedFontProfile, colorScheme);
    }
}
