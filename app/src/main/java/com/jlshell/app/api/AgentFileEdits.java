package com.jlshell.app.api;

import com.jlshell.program.api.ProgramFileService.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

final class AgentFileEdits {
    private AgentFileEdits() {}
    static WriteRequest patch(Content source, PatchRequest request) {
        if (request.expectedSha256() != null && !request.expectedSha256().equalsIgnoreCase(source.sha256()))
            throw new IllegalStateException("File revision conflict");
        if (request.edits().isEmpty() || request.edits().size() > 1000) throw new IllegalArgumentException("Supply 1..1000 text edits");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source.content())).toString(); }
        catch (CharacterCodingException error) { throw new IllegalArgumentException("Patch requires valid UTF-8", error); }
        for (TextEdit edit : request.edits()) {
            if (edit.oldText() == null || edit.oldText().isEmpty() || edit.newText() == null) throw new IllegalArgumentException("Each edit needs nonempty oldText and newText");
            int at = text.indexOf(edit.oldText());
            if (at < 0 || text.indexOf(edit.oldText(), at + 1) >= 0) throw new IllegalArgumentException("oldText must match exactly once");
            text = text.substring(0, at) + edit.newText() + text.substring(at + edit.oldText().length());
            if (text.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("Patched text exceeds limit");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4 * 1024 * 1024) throw new IllegalArgumentException("Patched bytes exceed limit");
        return new WriteRequest(request.path(), bytes, source.sha256(), false, request.backup());
    }
}
