package com.jlshell.api.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenStoreTest {
    @Test
    void loadOrCreateIsIdempotent(@TempDir Path tmp) throws Exception {
        System.setProperty("jlshell.home", tmp.toString());
        String t1 = ApiTokenStore.loadOrCreate();
        String t2 = ApiTokenStore.loadOrCreate();
        assertThat(t1).isNotBlank();
        assertThat(t1).isEqualTo(t2); // 第二次读回同一个
    }
}