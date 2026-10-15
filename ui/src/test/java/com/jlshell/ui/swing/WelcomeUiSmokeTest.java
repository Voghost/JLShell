package com.jlshell.ui.swing;

import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.AppTheme;
import com.jlshell.ui.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="jlshell.ui.smoke",matches="true")
class WelcomeUiSmokeTest {
    @Test void welcomeRemainsUsableInBothThemesAndNarrowWindows() throws Exception {
        SwingExecutors.runOnEdtAsync(() -> {
            var i18n = new I18nService(Locale.SIMPLIFIED_CHINESE);
            var texts = new HashMap<String,String>(); SwingWorkbench.TEXT_KEYS.forEach(key -> texts.put(key,i18n.get(key)));
            var created = new AtomicInteger(); var connected = new AtomicInteger(); Runnable noop=()->{};
            var actions = new SwingWorkbench.Actions(created::incrementAndGet,noop,noop,noop,noop,noop,noop,noop,noop,
                    connection->connected.incrementAndGet(),items->{},project->{},folders->{},tab->{},
                    (tab,pos)->{},(items,folder)->{},(item,reply)->reply.accept(List.of()),(tab,reply)->reply.accept(List.of()));
            SwingTheme.install(AppTheme.DARK,"#3574f0","SansSerif",13);
            var view = new SwingWorkbench(actions,texts);
            try {
                var profiles = new ArrayList<ConnectionProfile>(); var recent = new ArrayList<RecentSessionProfile>();
                String[] names={"Leif NAS","开发环境 · Arch Linux","生产服务器与后台服务——一个很长很长的连接名称","测试服务器"};
                for(int index=0;index<4;index++) {
                    String id="server-"+index;
                    profiles.add(new ConnectionProfile(id,names[index],"192.0.2."+(10+index),22,"developer",null,null,"","",false,null,com.jlshell.core.model.ConnectionType.SSH,null,null));
                    recent.add(new RecentSessionProfile(id,names[index],"192.0.2."+(10+index),22,"developer","SSH",Instant.now(),null,"CLOSED"));
                }
                view.updateConnections(List.of(),profiles,Set.of());
                view.updateWelcome(recent,List.of()); view.show(); view.frame().setSize(1440,960); view.frame().validate();
                capture(view,"welcome-dark");
                ((JButton)named(view.frame(),"welcome.create")).doClick(); assertEquals(1,created.get());
                ((JButton)named(view.frame(),"welcome.connection.server-0")).doClick(); assertEquals(1,connected.get());
                SwingTheme.install(AppTheme.LIGHT,"#3574f0","SansSerif",13); SwingTheme.update(view.frame()); view.frame().validate();
                capture(view,"welcome-light");
                view.frame().setSize(800,740); view.frame().validate(); capture(view,"welcome-narrow");
                JComponent content=(JComponent)named(view.frame(),"welcome.content");
                assertTrue(content.getWidth() < 580,"Narrow layout should stack its two collections");
                assertTrue(content.getHeight()>700,"Stacked cards must retain their usable height and scroll with the page");
                var scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,content);
                scroll.doLayout();
                assertTrue(scroll.getVerticalScrollBar().isVisible(),"Stacked page must expose a scrollbar");
                assertNotNull(named(view.frame(),"welcome.folder"));
                view.updateWelcome(List.of(),List.of()); view.frame().setSize(1440,960);view.frame().validate();capture(view,"welcome-empty");
            } finally { view.close(); }
        }).get(20,TimeUnit.SECONDS);
    }
    private static Component named(Component root,String name) {
        if(name.equals(root.getName())) return root;
        if(root instanceof Container container) for(Component child:container.getComponents()) { Component found=named(child,name);if(found!=null)return found; }
        return null;
    }
    private static void capture(SwingWorkbench view,String name) {
        try {
            var root=view.frame().getRootPane(); var image=new java.awt.image.BufferedImage(root.getWidth(),root.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=image.createGraphics();root.printAll(g);g.dispose();
            Path file=Path.of("target/review/"+name+".png");Files.createDirectories(file.getParent());javax.imageio.ImageIO.write(image,"png",file.toFile());
        } catch(java.io.IOException error) {throw new java.io.UncheckedIOException(error);}
    }
}
