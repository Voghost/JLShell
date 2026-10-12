// Copyright 2000-2025 JetBrains s.r.o. and contributors. Apache-2.0.
// Adapted from IntelliJ SVGs; originals and attribution: resources/icons/idea, third-party/idea-NOTICE.txt.
package com.jlshell.ui.swing;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.*;
import java.util.EnumMap;
import java.util.List;

/** IDEA 原始图标的 Java2D 路径。初始化一次；绘制不解析 SVG，也不跨越 FX/EDT。 */
final class IdeaIconShapes {
    private record Glyph(Shape shape, boolean fill) {}
    private static final EnumMap<WorkbenchIcon.Kind, List<Glyph>> GLYPHS = new EnumMap<>(WorkbenchIcon.Kind.class);
    static {
        {
            Path2D.Double p0 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p0.moveTo(8.15132, 4.35836);
            p0.lineTo(8.29689, 4.5);
            p0.lineTo(8.5, 4.5);
            p0.lineTo(13, 4.5);
            p0.curveTo(13.8284, 4.5, 14.5, 5.17157, 14.5, 6);
            p0.lineTo(14.5, 12.1333);
            p0.curveTo(14.5, 12.919, 13.9104, 13.5, 13.25, 13.5);
            p0.lineTo(2.75, 13.5);
            p0.curveTo(2.08955, 13.5, 1.5, 12.919, 1.5, 12.1333);
            p0.lineTo(1.5, 3.86667);
            p0.curveTo(1.5, 3.08099, 2.08955, 2.5, 2.75, 2.5);
            p0.lineTo(6.03823, 2.5);
            p0.curveTo(6.16847, 2.5, 6.29357, 2.55082, 6.38691, 2.64164);
            p0.lineTo(8.15132, 4.35836);
            p0.closePath();
            put(WorkbenchIcon.Kind.FOLDER, new Glyph(p0, false));
        }
        put(WorkbenchIcon.Kind.TERMINAL, new Glyph(new RoundRectangle2D.Double(1.5, 2.5, 13, 11, 3.0, 3.0), false));
        {
            Path2D.Double p1 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p1.moveTo(7.5, 10.5);
            p1.lineTo(10.5, 10.5);
            put(WorkbenchIcon.Kind.TERMINAL, new Glyph(p1, false));
        }
        {
            Path2D.Double p2 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p2.moveTo(4.5, 9.5);
            p2.lineTo(6.5, 7.5);
            p2.lineTo(4.5, 5.5);
            put(WorkbenchIcon.Kind.TERMINAL, new Glyph(p2, false));
        }
        put(WorkbenchIcon.Kind.SEARCH, new Glyph(new Ellipse2D.Double(2.5, 2.5, 9.0, 9.0), false));
        {
            Path2D.Double p1 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p1.moveTo(10.1992, 10.2002);
            p1.lineTo(13.4992, 13.4961);
            put(WorkbenchIcon.Kind.SEARCH, new Glyph(p1, false));
        }
        put(WorkbenchIcon.Kind.MORE, new Glyph(new Ellipse2D.Double(7.0, 2.0, 2.0, 2.0), true));
        put(WorkbenchIcon.Kind.MORE, new Glyph(new Ellipse2D.Double(7.0, 7.0, 2.0, 2.0), true));
        put(WorkbenchIcon.Kind.MORE, new Glyph(new Ellipse2D.Double(7.0, 12.0, 2.0, 2.0), true));
        {
            Path2D.Double p0 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p0.moveTo(3.22655, 4.36961);
            p0.curveTo(2.92233, 4.76924, 2.66735, 5.20781, 2.47037, 5.67626);
            p0.lineTo(3.30992, 7.0943);
            p0.curveTo(3.6406, 7.65283, 3.6406, 8.34718, 3.30992, 8.9057);
            p0.lineTo(2.47037, 10.3237);
            p0.curveTo(2.66736, 10.7922, 2.92233, 11.2308, 3.22656, 11.6304);
            p0.lineTo(4.87261, 11.6124);
            p0.curveTo(5.52165, 11.6053, 6.12297, 11.9524, 6.44133, 12.5181);
            p0.lineTo(7.24899, 13.953);
            p0.curveTo(7.49593, 13.984, 7.74792, 14, 8.00408, 14);
            p0.curveTo(8.2602, 14, 8.51214, 13.984, 8.75904, 13.9531);
            p0.lineTo(9.56671, 12.5181);
            p0.curveTo(9.88507, 11.9524, 10.4864, 11.6053, 11.1354, 11.6124);
            p0.lineTo(12.7816, 11.6304);
            p0.curveTo(13.0858, 11.2308, 13.3408, 10.7923, 13.5377, 10.3239);
            p0.lineTo(12.6981, 8.9057);
            p0.curveTo(12.3674, 8.34718, 12.3674, 7.65283, 12.6981, 7.0943);
            p0.lineTo(13.5377, 5.67613);
            p0.curveTo(13.3408, 5.20773, 13.0858, 4.76921, 12.7816, 4.36961);
            p0.lineTo(11.1354, 4.38764);
            p0.curveTo(10.4864, 4.39475, 9.88507, 4.04758, 9.56671, 3.48194);
            p0.lineTo(8.75904, 2.04693);
            p0.curveTo(8.51214, 2.01599, 8.2602, 2, 8.00408, 2);
            p0.curveTo(7.74792, 2, 7.49594, 2.016, 7.24899, 2.04695);
            p0.lineTo(6.44133, 3.48194);
            p0.curveTo(6.12297, 4.04758, 5.52165, 4.39475, 4.87261, 4.38764);
            p0.lineTo(3.22655, 4.36961);
            p0.closePath();
            p0.moveTo(10.655, 8.00001);
            p0.curveTo(10.655, 9.46412, 9.46811, 10.651, 8.004, 10.651);
            p0.curveTo(6.5399, 10.651, 5.353, 9.46412, 5.353, 8.00001);
            p0.curveTo(5.353, 6.53591, 6.5399, 5.34902, 8.004, 5.34902);
            p0.curveTo(9.46811, 5.34902, 10.655, 6.53591, 10.655, 8.00001);
            p0.closePath();
            p0.moveTo(4.88356, 3.3877);
            p0.curveTo(5.16752, 3.39081, 5.4306, 3.23892, 5.56988, 2.99146);
            p0.lineTo(6.43817, 1.44875);
            p0.curveTo(6.54914, 1.25159, 6.7401, 1.1101, 6.96387, 1.07676);
            p0.curveTo(7.30327, 1.0262, 7.65062, 1, 8.00408, 1);
            p0.curveTo(8.3575, 1, 8.7048, 1.02619, 9.04414, 1.07674);
            p0.curveTo(9.26792, 1.11007, 9.45889, 1.25157, 9.56986, 1.44873);
            p0.lineTo(10.4382, 2.99146);
            p0.curveTo(10.5774, 3.23892, 10.8405, 3.39081, 11.1245, 3.3877);
            p0.lineTo(12.8938, 3.36832);
            p0.curveTo(13.1196, 3.36585, 13.3372, 3.46012, 13.4781, 3.63661);
            p0.curveTo(13.9099, 4.17766, 14.2632, 4.78416, 14.5207, 5.43884);
            p0.curveTo(14.6034, 5.6491, 14.5763, 5.88489, 14.4612, 6.07931);
            p0.lineTo(13.5586, 7.60376);
            p0.curveTo(13.4139, 7.84811, 13.4139, 8.15189, 13.5586, 8.39625);
            p0.lineTo(14.4612, 9.92069);
            p0.curveTo(14.5763, 10.1151, 14.6034, 10.3509, 14.5207, 10.5612);
            p0.curveTo(14.2632, 11.2158, 13.9099, 11.8223, 13.4781, 12.3634);
            p0.curveTo(13.3372, 12.5399, 13.1196, 12.6342, 12.8938, 12.6317);
            p0.lineTo(11.1245, 12.6123);
            p0.curveTo(10.8405, 12.6092, 10.5774, 12.7611, 10.4382, 13.0085);
            p0.lineTo(9.56986, 14.5513);
            p0.curveTo(9.45889, 14.7484, 9.26792, 14.8899, 9.04414, 14.9233);
            p0.curveTo(8.7048, 14.9738, 8.3575, 15, 8.00408, 15);
            p0.curveTo(7.65062, 15, 7.30327, 14.9738, 6.96387, 14.9232);
            p0.curveTo(6.7401, 14.8899, 6.54914, 14.7484, 6.43817, 14.5512);
            p0.lineTo(5.56988, 13.0085);
            p0.curveTo(5.4306, 12.7611, 5.16752, 12.6092, 4.88356, 12.6123);
            p0.lineTo(3.1144, 12.6317);
            p0.curveTo(2.8886, 12.6342, 2.67096, 12.5399, 2.5301, 12.3634);
            p0.curveTo(2.09822, 11.8223, 1.74489, 11.2158, 1.48738, 10.561);
            p0.curveTo(1.40469, 10.3508, 1.43184, 10.115, 1.54695, 9.92057);
            p0.lineTo(2.44942, 8.39625);
            p0.curveTo(2.5941, 8.15189, 2.5941, 7.84811, 2.44942, 7.60376);
            p0.lineTo(1.54695, 6.07944);
            p0.curveTo(1.43184, 5.88502, 1.40469, 5.64924, 1.48738, 5.43898);
            p0.curveTo(1.74489, 4.78425, 2.09822, 4.1777, 2.53009, 3.63661);
            p0.curveTo(2.67096, 3.46012, 2.8886, 3.36585, 3.1144, 3.36832);
            p0.lineTo(4.88356, 3.3877);
            p0.closePath();
            p0.moveTo(9.655, 8.00001);
            p0.curveTo(9.655, 8.91183, 8.91582, 9.65101, 8.004, 9.65101);
            p0.curveTo(7.09218, 9.65101, 6.353, 8.91183, 6.353, 8.00001);
            p0.curveTo(6.353, 7.08819, 7.09218, 6.34902, 8.004, 6.34902);
            p0.curveTo(8.91582, 6.34902, 9.655, 7.08819, 9.655, 8.00001);
            p0.closePath();
            put(WorkbenchIcon.Kind.SETTINGS, new Glyph(p0, true));
        }
        {
            Path2D.Double p0 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p0.moveTo(7.5, 1);
            p0.curveTo(7.77614, 1, 8, 1.22386, 8, 1.5);
            p0.lineTo(8, 7);
            p0.lineTo(13.5, 7);
            p0.curveTo(13.7761, 7, 14, 7.22386, 14, 7.5);
            p0.curveTo(14, 7.77614, 13.7761, 8, 13.5, 8);
            p0.lineTo(8, 8);
            p0.lineTo(8, 13.5);
            p0.curveTo(8, 13.7761, 7.77614, 14, 7.5, 14);
            p0.curveTo(7.22386, 14, 7, 13.7761, 7, 13.5);
            p0.lineTo(7, 8);
            p0.lineTo(1.5, 8);
            p0.curveTo(1.22386, 8, 1, 7.77614, 1, 7.5);
            p0.curveTo(1, 7.22386, 1.22386, 7, 1.5, 7);
            p0.lineTo(7, 7);
            p0.lineTo(7, 1.5);
            p0.curveTo(7, 1.22386, 7.22386, 1, 7.5, 1);
            p0.closePath();
            put(WorkbenchIcon.Kind.ADD, new Glyph(p0, true));
        }
        {
            Path2D.Double p0 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p0.moveTo(3.5, 12.5);
            p0.lineTo(12.5, 3.5);
            p0.moveTo(12.5, 12.5);
            p0.lineTo(3.5, 3.5);
            put(WorkbenchIcon.Kind.CLOSE, new Glyph(p0, false));
        }
        GLYPHS.put(WorkbenchIcon.Kind.PROJECT, GLYPHS.get(WorkbenchIcon.Kind.FOLDER));
    }

    private static void put(WorkbenchIcon.Kind kind, Glyph glyph) {
        var shapes = new java.util.ArrayList<>(GLYPHS.getOrDefault(kind, List.of()));
        shapes.add(glyph);
        GLYPHS.put(kind, List.copyOf(shapes));
    }

    static boolean paint(WorkbenchIcon.Kind kind, Graphics2D g) {
        List<Glyph> shapes = GLYPHS.get(kind);
        if (shapes == null) return false;
        for (Glyph shape : shapes) {
            if (shape.fill()) g.fill(shape.shape()); else g.draw(shape.shape());
        }
        return true;
    }
}
