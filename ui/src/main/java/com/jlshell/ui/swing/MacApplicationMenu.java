package com.jlshell.ui.swing;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 启动时本地化 AWT 创建的 macOS 应用菜单。只修改 NSMenuItem.title，保留 JDK 安装的
 * target/action、Services 子菜单及系统快捷键。AppKit 操作异步交给主线程，不从 EDT 等待。
 */
public final class MacApplicationMenu {
    private MacApplicationMenu() {}

    public static CompletableFuture<Void> localize(Locale locale) {
        if (!com.formdev.flatlaf.util.SystemInfo.isMacOS) return CompletableFuture.completedFuture(null);
        try {
            ResourceBundle messages = ResourceBundle.getBundle("i18n/messages", locale,
                    ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            return Cocoa.submit(() -> {
                Pointer app = Cocoa.send(Cocoa.type("NSApplication"), "sharedApplication");
                Pointer menu = Cocoa.applicationMenu(app);
                Pointer services = Cocoa.send(app, "servicesMenu");
                long count = Cocoa.number(menu, "numberOfItems");
                for (long index = 0; index < count; index++) {
                    Pointer item = Cocoa.send(menu, "itemAtIndex:", index);
                    Pointer action = Cocoa.send(item, "action");
                    String selector = action == null ? "" : Cocoa.OBJC.sel_getName(action);
                    String key = switch (selector) {
                        case "orderFrontStandardAboutPanel:", "_aboutMenuHandler" -> "about";
                        case "_preferencesMenuHandler" -> "preferences";
                        case "hide:" -> "hide";
                        case "hideOtherApplications:" -> "hideOthers";
                        case "unhideAllApplications:" -> "showAll";
                        case "terminate:" -> "quit";
                        default -> null;
                    };
                    Pointer submenu = Cocoa.send(item, "submenu");
                    if (services != null && services.equals(submenu)) key = "services";
                    if (key != null) Cocoa.send(item, "setTitle:", Cocoa.string(messages.getString("mac.application." + key)));
                }
                return null;
            });
        } catch (RuntimeException | LinkageError error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    /** Native inspection for the macOS smoke test; executed on the same AppKit queue as updates. */
    static CompletableFuture<List<Item>> snapshot() {
        return Cocoa.submit(() -> {
            Pointer app = Cocoa.send(Cocoa.type("NSApplication"), "sharedApplication");
            Pointer menu = Cocoa.applicationMenu(app);
            List<Item> items = new ArrayList<>();
            for (long index = 0, count = Cocoa.number(menu, "numberOfItems"); index < count; index++) {
                Pointer item = Cocoa.send(menu, "itemAtIndex:", index);
                Pointer action = Cocoa.send(item, "action");
                items.add(new Item(Cocoa.text(Cocoa.send(item, "title")),
                        action == null ? "" : Cocoa.OBJC.sel_getName(action),
                        Cocoa.text(Cocoa.send(item, "keyEquivalent")), Cocoa.number(item, "keyEquivalentModifierMask"),
                        Pointer.nativeValue(Cocoa.send(item, "target")), Pointer.nativeValue(Cocoa.send(item, "submenu"))));
            }
            return List.copyOf(items);
        });
    }

    record Item(String title, String action, String key, long modifiers, long target, long submenu) {}

    private interface ObjectiveC extends Library {
        Pointer objc_getClass(String name);
        Pointer sel_registerName(String name);
        String sel_getName(Pointer selector);
        Pointer objc_allocateClassPair(Pointer parent, String name, long extraBytes);
        byte class_addMethod(Pointer type, Pointer selector, MainThreadCallback callback, String signature);
        void objc_registerClassPair(Pointer type);
    }

    private interface MainThreadCallback extends Callback {
        void invoke(Pointer self, Pointer selector, Pointer argument);
    }

    /** Lazy holder: Windows/Linux never load JNA or the Objective-C runtime. */
    private static final class Cocoa {
        private static final Map<String, ?> OPTIONS = Map.of(Library.OPTION_STRING_ENCODING, "UTF-8");
        private static final ObjectiveC OBJC = Native.load("objc", ObjectiveC.class, OPTIONS);
        private static final Function SEND = NativeLibrary.getInstance("objc", OPTIONS).getFunction("objc_msgSend");
        private static final ConcurrentLinkedQueue<Runnable> PENDING = new ConcurrentLinkedQueue<>();
        // Keep the callback and receiver strongly reachable for the full lifetime of NSApplication.
        private static final MainThreadCallback CALLBACK = (self, selector, argument) -> {
            Pointer pool = send(type("NSAutoreleasePool"), "new");
            try {
                Runnable task;
                while ((task = PENDING.poll()) != null) task.run();
            } finally { send(pool, "drain"); }
        };
        private static final Pointer RECEIVER = receiver();

        private static Pointer receiver() {
            Pointer type = OBJC.objc_allocateClassPair(type("NSObject"), "JLShellApplicationMenuLocalizer", 0);
            if (type == null || OBJC.class_addMethod(type, OBJC.sel_registerName("localize:"), CALLBACK, "v@:@") == 0)
                throw new IllegalStateException("Unable to create macOS menu localization dispatcher");
            OBJC.objc_registerClassPair(type);
            return send(type, "new");
        }

        private static <T> CompletableFuture<T> submit(Callable<T> action) {
            CompletableFuture<T> result = new CompletableFuture<>();
            Runnable task = () -> {
                try { result.complete(action.call()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            };
            PENDING.add(task);
            try {
                send(RECEIVER, "performSelectorOnMainThread:withObject:waitUntilDone:",
                        OBJC.sel_registerName("localize:"), null, (byte) 0);
            } catch (RuntimeException | LinkageError error) {
                PENDING.remove(task);
                result.completeExceptionally(error);
            }
            return result;
        }

        private static Pointer type(String name) { return OBJC.objc_getClass(name); }
        private static Object[] arguments(Pointer receiver, String selector, Object... arguments) {
            Object[] values = new Object[arguments.length + 2];
            values[0] = receiver;
            values[1] = OBJC.sel_registerName(selector);
            System.arraycopy(arguments, 0, values, 2, arguments.length);
            return values;
        }
        private static Pointer send(Pointer receiver, String selector, Object... arguments) {
            return SEND.invokePointer(arguments(receiver, selector, arguments));
        }
        private static long number(Pointer receiver, String selector) { return SEND.invokeLong(arguments(receiver, selector)); }
        private static Pointer string(String text) { return send(type("NSString"), "stringWithUTF8String:", text); }
        private static String text(Pointer string) {
            Pointer utf8 = send(string, "UTF8String");
            return utf8 == null ? "" : utf8.getString(0, "UTF-8");
        }
        private static Pointer applicationMenu(Pointer app) {
            Pointer menu = send(send(send(app, "mainMenu"), "itemAtIndex:", 0L), "submenu");
            if (menu == null) throw new IllegalStateException("AWT application menu has not been initialized");
            return menu;
        }
    }
}
