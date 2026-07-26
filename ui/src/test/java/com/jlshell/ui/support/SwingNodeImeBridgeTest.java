package com.jlshell.ui.support;

import javafx.geometry.BoundingBox;
import javafx.geometry.Point2D;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwingNodeImeBridgeTest {

    @Test
    void preservesLogicalCoordinatesWhenComponentAndNodeHaveSameSize() {
        Point2D mapped = SwingNodeImeBridge.mapComponentToNode(
                new Point(320, 180),
                new Dimension(1280, 720),
                new BoundingBox(0, 0, 1280, 720)
        );

        assertEquals(new Point2D(320, 180), mapped);
    }

    @Test
    void mapsCoordinatesWhenJavaFxNodeIsScaled() {
        Point2D mapped = SwingNodeImeBridge.mapComponentToNode(
                new Point(320, 180),
                new Dimension(1280, 720),
                new BoundingBox(5, 7, 1920, 1080)
        );

        assertEquals(new Point2D(485, 277), mapped);
    }

    @Test
    void fallsBackToLogicalPixelsBeforeComponentIsLaidOut() {
        Point2D mapped = SwingNodeImeBridge.mapComponentToNode(
                new Point(50, 60),
                new Dimension(0, 0),
                new BoundingBox(3, 4, 800, 600)
        );

        assertEquals(new Point2D(53, 64), mapped);
    }
}
