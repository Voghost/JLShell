package com.jlshell.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PluginCompatibilityTest {

    @Test
    void undeclaredRangeWarns() {
        PluginCompatibility.Result result = PluginCompatibility.evaluate("1.2.3", "", "");

        assertThat(result.status()).isEqualTo(PluginCompatibilityStatus.UNDECLARED);
        assertThat(result.warning()).contains("not declared");
    }

    @Test
    void currentVersionInsideRangeIsCompatible() {
        PluginCompatibility.Result result = PluginCompatibility.evaluate("1.2.3.RELEASE", "1.0.0", "1.3.0");

        assertThat(result.status()).isEqualTo(PluginCompatibilityStatus.COMPATIBLE);
    }

    @Test
    void currentVersionOutsideRangeIsIncompatible() {
        assertThat(PluginCompatibility.evaluate("1.2.3", "1.3.0", "").status())
                .isEqualTo(PluginCompatibilityStatus.INCOMPATIBLE);
        assertThat(PluginCompatibility.evaluate("1.2.3", "", "1.1.9").status())
                .isEqualTo(PluginCompatibilityStatus.INCOMPATIBLE);
    }
}
