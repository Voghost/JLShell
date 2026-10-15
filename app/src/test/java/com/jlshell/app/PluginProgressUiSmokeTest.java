package com.jlshell.app;

import com.jlshell.core.service.AppSettingsService;
import com.jlshell.terminal.service.ColorSchemeRegistry;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.support.FxThread;
import com.jlshell.ui.swing.*;
import com.jlshell.ui.theme.*;
import com.jlshell.plugin.api.PluginContext;
import javafx.application.Platform;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="jlshell.ui.smoke", matches="true")
class PluginProgressUiSmokeTest {
    @BeforeAll static void toolkit() throws Exception {
        var ready = new CompletableFuture<Void>();
        try { Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); }); }
        catch (IllegalStateException running) { Platform.runLater(() -> ready.complete(null)); }
        ready.get(10, TimeUnit.SECONDS);
    }
    private static <T> T edt(java.util.function.Supplier<T> action) throws Exception {
        return SwingExecutors.supplyOnEdtAsync(action).get(10, TimeUnit.SECONDS);
    }
    private static JDialog window(String name) throws Exception {
        for (int i=0;i<100;i++) {
            JDialog dialog=edt(() -> Arrays.stream(Window.getWindows()).filter(w -> w instanceof JDialog && w.isShowing() && name.equals(w.getName())).map(w -> (JDialog)w).findFirst().orElse(null));
            if(dialog!=null)return dialog; Thread.sleep(30);
        }
        throw new AssertionError(name);
    }
    private static void capture(Window dialog,String name) {
        try {
            Path path=Path.of("..","ui","target","review",name+".png");Files.createDirectories(path.getParent());
            JRootPane root=((RootPaneContainer)dialog).getRootPane();
            var image=new java.awt.image.BufferedImage(root.getWidth()*2,root.getHeight()*2,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=image.createGraphics();try{g.scale(2,2);root.printAll(g);}finally{g.dispose();}
            javax.imageio.ImageIO.write(image,"png",path.toFile());
        }catch(Exception error){throw new RuntimeException(error);}
    }
    @Test void progressUsesSwingAndSurvivesEarlyCloseAndBackgroundUpdates() throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        AppSettingsService settings=mock(AppSettingsService.class);
        when(settings.get(anyString(),anyString())).thenAnswer(call->call.getArgument(1));
        when(settings.get(anyString())).thenReturn(Optional.empty());
        ColorSchemeRegistry schemes=mock(ColorSchemeRegistry.class);when(schemes.findByName(anyString())).thenReturn(Optional.empty());
        ThemeService themes=FxThread.supplyAsync(()->new ThemeService(settings,schemes,worker)).get(10,TimeUnit.SECONDS);
        I18nService i18n=new I18nService(Locale.SIMPLIFIED_CHINESE);
        try {
            for(AppTheme theme:AppTheme.values()) {
                FxThread.supplyAsync(()->{themes.setTheme(theme);return null;}).get(10,TimeUnit.SECONDS);
                var progress=FxThread.supplyAsync(()->new SwingProgressDialog(themes,i18n,"fixture.progress",i18n.get("updates.downloading"))).get(10,TimeUnit.SECONDS);
                JDialog dialog=window("fixture.progress");
                edt(()->{NativeSettingsProbe.assertNative(dialog);assertTrue(NativeSettingsProbe.all(dialog,JProgressBar.class).getFirst().isIndeterminate());capture(dialog,"updates-checking-"+theme);return null;});
                progress.update(.42);
                edt(()->{var bar=NativeSettingsProbe.all(dialog,JProgressBar.class).getFirst();assertFalse(bar.isIndeterminate());assertEquals(420,bar.getValue());capture(dialog,"updates-download-"+theme);NativeSettingsProbe.button(dialog,i18n.get("updates.background")).doClick();return null;});
                progress.update(1);progress.close();edt(()->{assertFalse(dialog.isDisplayable());return null;});
            }
            FxThread.supplyAsync(()->{var fast=new SwingProgressDialog(themes,i18n,"fixture.fast", "Checking");fast.close();return null;}).get(10,TimeUnit.SECONDS);
            edt(()->{assertTrue(Arrays.stream(Window.getWindows()).noneMatch(w->"fixture.fast".equals(w.getName())&&w.isDisplayable()));return null;});
        }finally{worker.shutdownNow();}
    }

    @Test void narrowPluginCardsKeepActionsAndTextInsideTheirBounds() throws Exception {
        for(AppTheme theme:AppTheme.values()) edt(()->{
            SwingTheme.install(theme,"#3574f0",null,13);
            var list=new SwingPluginList(); var opens=new java.util.concurrent.atomic.AtomicInteger();var pins=new java.util.concurrent.atomic.AtomicInteger();
            list.update(List.of(
                new SwingPluginList.Entry("sysmon","系统监控","实时 CPU、内存、网络和磁盘监控，支持趋势图与资源详情。文字应该适配窄侧栏，不产生横向滚动。","0.1.0",false,opens::incrementAndGet,value->pins.incrementAndGet()),
                new SwingPluginList.Entry("tools","Session Tools","Run commands and inspect session state without leaving your terminal.","0.1.0",true,opens::incrementAndGet,value->pins.incrementAndGet())),
                "搜索插件","打开","固定到侧栏","取消固定","暂无插件");
            JFrame frame=new JFrame("Plugins");frame.add(list);frame.setSize(300,560);frame.addNotify();frame.validate();
            try {
                NativeSettingsProbe.assertNative(list);
                assertTrue(NativeSettingsProbe.all(list,JScrollPane.class).stream().noneMatch(scroll->scroll.getHorizontalScrollBar().isVisible()));
                for(var button:NativeSettingsProbe.all(list,javax.swing.AbstractButton.class)) {
                    Rectangle bounds=SwingUtilities.convertRectangle(button.getParent(),button.getBounds(),list);
                    assertTrue(bounds.x>=0&&bounds.x+bounds.width<=list.getWidth(),button.getName());
                }
                NativeSettingsProbe.button(list,"打开").doClick();assertEquals(1,opens.get());
                NativeSettingsProbe.all(list,JToggleButton.class).getFirst().doClick();assertEquals(1,pins.get());
                capture(frame,"plugins-list-"+theme);
                NativeSettingsProbe.all(list,JTextField.class).getFirst().setText("不存在的插件");
                assertTrue(NativeSettingsProbe.all(list,JButton.class).stream().noneMatch(button -> button.getName()!=null&&button.getName().startsWith("plugin-open-")));
            }finally{frame.dispose();}
            return null;
        });
    }

    @Test void bundledSystemMonitorCanProvideANativeDisposablePage() throws Exception {
        Path jar=Path.of("..","plugins","plugin-sysmon","target","plugin-sysmon-0.1.0.RELEASE-fat.jar");
        Assumptions.assumeTrue(Files.exists(jar),"Build -Pwith-plugins first");
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},getClass().getClassLoader())) {
            Object plugin=loader.loadClass("com.jlshell.sysmon.SystemMonitorPlugin").getConstructor().newInstance();
            PluginContext context=mock(PluginContext.class);when(context.supportsSwingViews()).thenReturn(true);
            when(context.locale()).thenReturn(Locale.SIMPLIFIED_CHINESE);when(context.sshSession()).thenReturn(Optional.empty());
            java.util.concurrent.atomic.AtomicReference<java.util.function.Supplier<JComponent>> factory=new java.util.concurrent.atomic.AtomicReference<>();
            doAnswer(call->{factory.set(call.getArgument(1));return null;}).when(context).openSwingTab(anyString(),any());
            plugin.getClass().getMethod("activate",PluginContext.class).invoke(plugin,context);assertNotNull(factory.get());
            JComponent page=edt(()->{SwingTheme.install(AppTheme.DARK,"#3574f0",null,13);return factory.get().get();});
            SwingExecutors.runOnEdtAsync(()->{
                NativeSettingsProbe.assertNative(page);assertEquals("sysmon.swing.dashboard",page.getName());
                JFrame frame=new JFrame("Native monitor fixture");frame.add(page);frame.setSize(340,780);
                // 手工布置测试数据，不启动本机或真实 SSH 采样。
                frame.addNotify(); frame.validate();
                try{
                    Class<?> model=loader.loadClass("com.jlshell.sysmon.SystemMetrics");
                    Class<?> disk=loader.loadClass("com.jlshell.sysmon.SystemMetrics$DiskInfo");
                    Object diskInfo=disk.getConstructor(String.class,long.class,long.class).newInstance("/",256L<<30,96L<<30);
                    var constructor=model.getConstructors()[0];
                    var render=page.getClass().getDeclaredMethod("render",model,boolean.class);render.setAccessible(true);
                    for(int sample=0;sample<60;sample++) {
                        Object data=constructor.newInstance(25+Math.sin(sample*.35)*14,8,1.24,16L<<30,6L<<30,0L,500000L+sample*1000,120000L,List.of(diskInfo),sample*2000L+10000);
                        render.invoke(page,data,false);
                    }
                    capture(frame,"plugin-sysmon-native-dark");((AutoCloseable)page).close();}catch(Exception e){throw new RuntimeException(e);}finally{frame.dispose();}
            }).get(10,TimeUnit.SECONDS);
            var closed=page.getClass().getDeclaredField("closed");closed.setAccessible(true);assertTrue(closed.getBoolean(page));
            plugin.getClass().getMethod("deactivate").invoke(plugin);
        }
    }
}
