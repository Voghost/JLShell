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
        // platform/icons/src/expui/nodes/ssh.svg：IDEA 的 SSH 连接图标；沿用主题化线条色。
        put(WorkbenchIcon.Kind.SSH, new Glyph(new RoundRectangle2D.Double(1.5, 2.5, 13, 11, 3, 3), false));
        {
            Path2D.Double ssh = new Path2D.Double();
            ssh.moveTo(4.5, 10.5); ssh.lineTo(7, 8); ssh.lineTo(4.4799, 5.5201);
            put(WorkbenchIcon.Kind.SSH, new Glyph(ssh, false));
        }
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
        {
            Path2D.Double p0 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p0.moveTo(10.9995, 4);
            p0.curveTo(10.9995, 5.65685, 9.65637, 7, 7.99951, 7);
            p0.curveTo(6.34266, 7, 4.99951, 5.65685, 4.99951, 4);
            p0.curveTo(4.99951, 2.34315, 6.34266, 1, 7.99951, 1);
            p0.curveTo(9.65637, 1, 10.9995, 2.34315, 10.9995, 4);
            p0.closePath();
            p0.moveTo(9.99951, 4);
            p0.curveTo(9.99951, 5.10457, 9.10408, 6, 7.99951, 6);
            p0.curveTo(6.89494, 6, 5.99951, 5.10457, 5.99951, 4);
            p0.curveTo(5.99951, 2.89543, 6.89494, 2, 7.99951, 2);
            p0.curveTo(9.10408, 2, 9.99951, 2.89543, 9.99951, 4);
            p0.closePath();
            put(WorkbenchIcon.Kind.USER, new Glyph(p0, true));
        }
        {
            Path2D.Double p1 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p1.moveTo(7.99969, 8);
            p1.curveTo(3.54766, 8, 2.39906, 11.3034, 2.10272, 13.008);
            p1.curveTo(2.00813, 13.5521, 2.4474, 14, 2.99969, 14);
            p1.lineTo(12.9997, 14);
            p1.curveTo(13.552, 14, 13.9912, 13.5521, 13.8967, 13.008);
            p1.curveTo(13.6003, 11.3034, 12.4517, 8, 7.99969, 8);
            p1.closePath();
            p1.moveTo(11.7438, 10.5114);
            p1.curveTo(12.4081, 11.2951, 12.7281, 12.2552, 12.8778, 13);
            p1.lineTo(3.12154, 13);
            p1.curveTo(3.27126, 12.2552, 3.59131, 11.2951, 4.25563, 10.5114);
            p1.curveTo(4.94028, 9.70375, 6.05804, 9, 7.99969, 9);
            p1.curveTo(9.94134, 9, 11.0591, 9.70375, 11.7438, 10.5114);
            p1.closePath();
            put(WorkbenchIcon.Kind.USER, new Glyph(p1, true));
        }
        put(WorkbenchIcon.Kind.HELP, new Glyph(new Ellipse2D.Double(6.94995, 13.3, 1.7, 1.7), true));
        {
            Path2D.Double p1 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p1.moveTo(7.97962, 8.25128);
            p1.curveTo(8.20415, 8.01392, 8.49793, 7.73622, 8.8602, 7.41844);
            p1.curveTo(9.23243, 7.09036, 9.52729, 6.80791, 9.74572, 6.57075);
            p1.lineTo(9.74668, 6.56973);
            p1.curveTo(9.96622, 6.33799, 10.1503, 6.0594, 10.2982, 5.73275);
            p1.lineTo(10.2988, 5.73133);
            p1.curveTo(10.45, 5.4108, 10.5268, 5.0457, 10.5268, 4.634);
            p1.curveTo(10.5268, 4.13537, 10.4222, 3.69607, 10.2158, 3.31349);
            p1.curveTo(10.0093, 2.93099, 9.71876, 2.63442, 9.34261, 2.42208);
            p1.lineTo(9.34155, 2.42149);
            p1.curveTo(8.96585, 2.20334, 8.53389, 2.093, 8.04277, 2.093);
            p1.curveTo(7.53263, 2.093, 7.08144, 2.20651, 6.68653, 2.43124);
            p1.curveTo(6.29797, 2.65595, 5.9944, 2.97134, 5.77499, 3.3796);
            p1.curveTo(5.67273, 3.57247, 5.59473, 3.77918, 5.54126, 4);
            p1.curveTo(5.51644, 4.10252, 5.49691, 4.20808, 5.48269, 4.31671);
            p1.curveTo(5.44318, 4.61868, 5.20739, 4.867, 4.90285, 4.867);
            p1.lineTo(4.77896, 4.867);
            p1.curveTo(4.47828, 4.867, 4.23781, 4.61717, 4.24929, 4.31671);
            p1.curveTo(4.25956, 4.2093, 4.27359, 4.10373, 4.2914, 4);
            p1.curveTo(4.36501, 3.57119, 4.50313, 3.17391, 4.7064, 2.80885);
            p1.curveTo(5.03226, 2.23534, 5.4864, 1.7906, 6.06642, 1.47769);
            p1.curveTo(6.64663, 1.15834, 7.30949, 1, 8.05227, 1);
            p1.curveTo(8.77568, 1, 9.4194, 1.15185, 9.98062, 1.45842);
            p1.curveTo(10.5475, 1.75827, 10.9888, 2.17975, 11.3023, 2.72194);
            p1.curveTo(11.6162, 3.26467, 11.7718, 3.88719, 11.7718, 4.5865);
            p1.curveTo(11.7718, 5.13576, 11.6813, 5.62465, 11.4976, 6.05106);
            p1.curveTo(11.3166, 6.47121, 11.0924, 6.82638, 10.8239, 7.11469);
            p1.curveTo(10.567, 7.39722, 10.2283, 7.71973, 9.80882, 8.08202);
            p1.curveTo(9.47419, 8.37245, 9.2138, 8.61414, 9.02667, 8.8075);
            p1.curveTo(8.85057, 8.98968, 8.70116, 9.20284, 8.57871, 9.44772);
            p1.lineTo(8.57826, 9.44864);
            p1.curveTo(8.4595, 9.6802, 8.39927, 9.93983, 8.39927, 10.2295);
            p1.curveTo(8.39927, 10.4946, 8.18437, 10.7095, 7.91927, 10.7095);
            p1.lineTo(7.71977, 10.7095);
            p1.curveTo(7.42844, 10.7095, 7.19227, 10.4733, 7.19227, 10.182);
            p1.curveTo(7.19227, 9.77678, 7.26749, 9.41044, 7.42025, 9.08501);
            p1.curveTo(7.57001, 8.76595, 7.75635, 8.48769, 7.97962, 8.25128);
            p1.closePath();
            put(WorkbenchIcon.Kind.HELP, new Glyph(p1, true));
        }
        {
            Path2D.Double p0 = new Path2D.Double(Path2D.WIND_EVEN_ODD);
            p0.moveTo(6.99988, 3);
            p0.lineTo(3.99994, 3);
            p0.curveTo(3.44765, 3, 2.99994, 3.44772, 2.99994, 4);
            p0.lineTo(2.99994, 12);
            p0.curveTo(2.99994, 12.5523, 3.44765, 13, 3.99994, 13);
            p0.lineTo(6.99988, 13);
            p0.lineTo(6.99988, 8);
            p0.lineTo(6.99988, 7);
            p0.lineTo(6.99988, 3);
            p0.closePath();
            p0.moveTo(7.99988, 3);
            p0.lineTo(7.99988, 7);
            p0.lineTo(12.9999, 7);
            p0.lineTo(12.9999, 4);
            p0.curveTo(12.9999, 3.44772, 12.5522, 3, 11.9999, 3);
            p0.lineTo(7.99988, 3);
            p0.closePath();
            p0.moveTo(7.99988, 8);
            p0.lineTo(7.99988, 13);
            p0.lineTo(11.9999, 13);
            p0.curveTo(12.5522, 13, 12.9999, 12.5523, 12.9999, 12);
            p0.lineTo(12.9999, 8);
            p0.lineTo(7.99988, 8);
            p0.closePath();
            p0.moveTo(3.99994, 2);
            p0.curveTo(2.89537, 2, 1.99994, 2.89543, 1.99994, 4);
            p0.lineTo(1.99994, 12);
            p0.curveTo(1.99994, 13.1046, 2.89537, 14, 3.99994, 14);
            p0.lineTo(11.9999, 14);
            p0.curveTo(13.1045, 14, 13.9999, 13.1046, 13.9999, 12);
            p0.lineTo(13.9999, 4);
            p0.curveTo(13.9999, 2.89543, 13.1045, 2, 11.9999, 2);
            p0.lineTo(3.99994, 2);
            p0.closePath();
            put(WorkbenchIcon.Kind.LAYOUT, new Glyph(p0, true));
        }
        GLYPHS.put(WorkbenchIcon.Kind.PROJECT, GLYPHS.get(WorkbenchIcon.Kind.FOLDER));
        // IntelliJ IDEA platform/icons/src/expui/general/{autoscrollToSource,expandAll,collapseAll}.svg
        // Apache-2.0, JetBrains s.r.o.; Java2D coordinates preserve the source 16px geometry.
        Path2D.Double locate = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        locate.moveTo(8.5, 5); locate.lineTo(8.5, 2.02054); locate.curveTo(11.4149, 2.26101, 13.739, 4.5851, 13.9795, 7.5);
        locate.lineTo(11, 7.5); locate.curveTo(10.7239, 7.5, 10.5, 7.72386, 10.5, 8); locate.curveTo(10.5, 8.27614, 10.7239, 8.5, 11, 8.5);
        locate.lineTo(13.9795, 8.5); locate.curveTo(13.739, 11.4149, 11.4149, 13.739, 8.5, 13.9795); locate.lineTo(8.5, 11);
        locate.curveTo(8.5, 10.7239, 8.27614, 10.5, 8, 10.5); locate.curveTo(7.72386, 10.5, 7.5, 10.7239, 7.5, 11);
        locate.lineTo(7.5, 13.9795); locate.curveTo(4.5851, 13.739, 2.26101, 11.4149, 2.02054, 8.5); locate.lineTo(5, 8.5);
        locate.curveTo(5.27614, 8.5, 5.5, 8.27614, 5.5, 8); locate.curveTo(5.5, 7.72386, 5.27614, 7.5, 5, 7.5);
        locate.lineTo(2.02054, 7.5); locate.curveTo(2.26101, 4.5851, 4.5851, 2.26101, 7.5, 2.02054); locate.lineTo(7.5, 5);
        locate.curveTo(7.5, 5.27614, 7.72386, 5.5, 8, 5.5); locate.curveTo(8.27614, 5.5, 8.5, 5.27614, 8.5, 5); locate.closePath();
        locate.moveTo(1, 8); locate.curveTo(1, 4.13401, 4.13401, 1, 8, 1); locate.curveTo(11.866, 1, 15, 4.13401, 15, 8);
        locate.curveTo(15, 11.866, 11.866, 15, 8, 15); locate.curveTo(4.13401, 15, 1, 11.866, 1, 8); locate.closePath();
        put(WorkbenchIcon.Kind.FOCUS, new Glyph(locate, true));
        Path2D.Double expand = new Path2D.Double();
        expand.moveTo(4.5, 5.5); expand.lineTo(8, 2); expand.lineTo(11.5, 5.5);
        expand.moveTo(4.5, 10.5); expand.lineTo(8, 14); expand.lineTo(11.5, 10.5);
        put(WorkbenchIcon.Kind.EXPAND_ALL, new Glyph(expand, false));
        Path2D.Double collapse = new Path2D.Double();
        collapse.moveTo(4.5, 2.5); collapse.lineTo(8, 6); collapse.lineTo(11.5, 2.5);
        collapse.moveTo(4.5, 13.5); collapse.lineTo(8, 10); collapse.lineTo(11.5, 13.5);
        put(WorkbenchIcon.Kind.COLLAPSE_ALL, new Glyph(collapse, false));
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
