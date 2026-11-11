package com.jlshell.ui.model;

import com.jlshell.core.support.MutableValue;
import com.jlshell.plugin.api.ui.ObservableValue;
import java.util.ArrayList;
import java.util.List;

/** 与控件树独立的标签顺序和选择状态。由 EDT 修改，观察者接收不可变快照。 */
public final class WorkspaceTabs {
    private final MutableValue<List<WorkspacePage>> pages = new MutableValue<>(List.of());
    private final MutableValue<WorkspacePage> selected = new MutableValue<>(null);

    public List<WorkspacePage> pages() {
        return pages.get();
    }

    public WorkspacePage selected() {
        return selected.get();
    }

    public int selectedIndex() {
        return pages().indexOf(selected());
    }

    public ObservableValue<List<WorkspacePage>> pagesProperty() {
        return pages;
    }

    public ObservableValue<WorkspacePage> selectionProperty() {
        return selected;
    }

    public void select(WorkspacePage page) {
        if (page == null || pages().contains(page)) selected.set(page);
    }

    public void add(WorkspacePage page) {
        page.getProperties().put("workspace.owner", this);
        var next = new ArrayList<>(pages());
        next.add(page);
        pages.set(List.copyOf(next));
        if (selected() == null) select(page);
    }

    public void remove(WorkspacePage page) {
        int index = pages().indexOf(page);
        if (index < 0) return;
        var next = new ArrayList<>(pages());
        next.remove(index);
        page.getProperties().remove("workspace.owner", this);
        pages.set(List.copyOf(next));
        if (selected() == page) select(next.isEmpty() ? null : next.get(Math.min(index, next.size() - 1)));
    }

    public void clear() {
        pages().forEach(page -> page.getProperties().remove("workspace.owner", this));
        pages.set(List.of());
        select(null);
    }

    public void move(WorkspacePage page, int index) {
        if (!pages().contains(page)) return;
        var next = new ArrayList<>(pages());
        next.remove(page);
        next.add(Math.max(0, Math.min(index, next.size())), page);
        pages.set(List.copyOf(next));
    }
}
