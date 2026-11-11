package com.jlshell.app.api;

import com.jlshell.program.api.ProgramFileService.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentFileEditsTest {
    @TempDir Path directory;
    @Test void patchesUtf8WithRevisionChecksAndAtomicBackup() throws Exception {
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var files=new LocalAgentFiles(executor);
            Path path=directory.resolve("code.txt");
            var created=files.writeAtomic(new WriteRequest(path.toString(),"你好\nold\n".getBytes(StandardCharsets.UTF_8),null,true,true)).get();
            var original=files.readVersioned(path.toString()).get();
            var request=new PatchRequest(path.toString(),original.sha256(),List.of(new TextEdit("old","new")),true);
            var updated=files.writeAtomic(AgentFileEdits.patch(original,request)).get();
            assertEquals("你好\nnew\n",Files.readString(path));
            assertEquals("你好\nold\n",Files.readString(Path.of(updated.backupPath())));
            assertNotEquals(created.sha256(),updated.sha256());
            assertThrows(ExecutionException.class,() -> files.writeAtomic(new WriteRequest(path.toString(),new byte[0],created.sha256(),false,true)).get());
            assertEquals("你好\nnew\n",Files.readString(path));
            try (var entries=Files.list(directory)) { assertTrue(entries.noneMatch(value -> value.getFileName().toString().startsWith(".jlshell-tmp-"))); }
        }
    }
    @Test void rejectsAmbiguousOrInvalidUtf8PatchBeforeWriting() {
        var content=new Content("file","aa aa".getBytes(),"revision");
        assertThrows(IllegalArgumentException.class,() -> AgentFileEdits.patch(content,new PatchRequest("file",null,List.of(new TextEdit("aa","bb")),false)));
        var invalid=new Content("file",new byte[]{(byte)0xff},"revision");
        assertThrows(IllegalArgumentException.class,() -> AgentFileEdits.patch(invalid,new PatchRequest("file",null,List.of(new TextEdit("aa","bb")),false)));
    }
    @Test void concurrentExpectedRevisionWritersCannotBothCommit() throws Exception {
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var files=new LocalAgentFiles(executor);
            Path path=Files.writeString(directory.resolve("concurrent.txt"),"original");
            String revision=files.readVersioned(path.toString()).get().sha256();
            var first=files.writeAtomic(new WriteRequest(path.toString(),"one".getBytes(),revision,false,false));
            var second=files.writeAtomic(new WriteRequest(path.toString(),"two".getBytes(),revision,false,false));
            CompletableFuture.allOf(first,second).handle((ignored,error) -> null).get();
            assertNotEquals(first.isCompletedExceptionally(),second.isCompletedExceptionally());
        }
    }
}
