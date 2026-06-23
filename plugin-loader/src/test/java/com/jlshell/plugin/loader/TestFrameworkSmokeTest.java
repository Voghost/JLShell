package com.jlshell.plugin.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TestFrameworkSmokeTest {
    @Test
    void frameworkWorks() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
