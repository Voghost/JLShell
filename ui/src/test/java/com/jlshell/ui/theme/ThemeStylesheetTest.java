package com.jlshell.ui.theme;

import javafx.css.CssParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeStylesheetTest {

    @Test
    void bothPalettesAndSharedComponentsParseWithoutErrors() throws Exception {
        CssParser.errorsProperty().clear();
        for (String file : new String[]{"/css/dark-theme.css", "/css/light-theme.css", "/css/components.css"}) {
            var resource = getClass().getResource(file);
            assertNotNull(resource, file);
            new CssParser().parse(resource);
        }
        assertTrue(CssParser.errorsProperty().isEmpty(), () -> CssParser.errorsProperty().toString());
    }
}
