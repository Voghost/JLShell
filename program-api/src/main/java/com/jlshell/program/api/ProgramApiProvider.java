package com.jlshell.program.api;

/**
 * Program API SPI。实现类通过 {@code META-INF/services} 由宿主发现并激活。
 */
public interface ProgramApiProvider {

    void activate(ProgramApiContext context);

    default void deactivate() {
    }
}
