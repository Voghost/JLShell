package com.jlshell.ui.swing;

import com.jlshell.program.api.ProgramWorkspaceService.*;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;

/** Native, asynchronous target selection shared by Program plugins and RPC callers. */
public final class WorkspaceSelectionDialog {
    private WorkspaceSelectionDialog() {}
    private record Choice(String id,String label) { @Override public String toString() { return label; } }
    private static Choice choice(Session session) {
        return new Choice(session.sessionId(),session.name()+"  ·  "+session.username()+"@"+session.host()
                + (session.port()==0?"":":"+session.port())+"  ·  "+session.state());
    }
    private static List<Session> candidates(List<Session> sessions,List<String> ids) {
        if(ids.isEmpty()) return sessions;
        for(String id:ids) if(sessions.stream().noneMatch(session -> session.sessionId().equals(id)))
            throw new IllegalArgumentException("Session selection contains a closed or unknown target: "+id);
        return sessions.stream().filter(session -> ids.contains(session.sessionId())).toList();
    }
    public static CompletableFuture<Selection> choose(SelectionRequest request,List<Session> sessions,I18nService i18n,ThemeService themes) {
        var choices=candidates(sessions,request.candidateIds());
        return SwingFormDialog.showAsync(request.title(),"workspace.chooseSessions",i18n,themes,() -> {
            JPanel panel=new JPanel(new BorderLayout(8,12)); panel.setBorder(BorderFactory.createEmptyBorder(16,20,16,20));
            JLabel description=new JLabel(request.description()); description.putClientProperty("html.disable",true); panel.add(description,BorderLayout.NORTH);
            JList<Choice> list=new JList<>(choices.stream().map(WorkspaceSelectionDialog::choice).toArray(Choice[]::new));
            list.setName("workspace.sessionChoices"); list.setSelectionMode(request.multiple()?ListSelectionModel.MULTIPLE_INTERVAL_SELECTION:ListSelectionModel.SINGLE_SELECTION);
            list.setFixedCellHeight(32); list.setVisibleRowCount(9);
            DefaultListCellRenderer renderer=new DefaultListCellRenderer();
            list.setCellRenderer((view,value,index,selected,focus) -> {
                JLabel label=(JLabel)renderer.getListCellRendererComponent(view,value,index,selected,focus);
                label.putClientProperty("html.disable",true); label.setToolTipText(value.id()); return label;
            });
            panel.add(new JScrollPane(list));
            JLabel validation=new JLabel(" "); panel.add(validation,BorderLayout.SOUTH);
            return new SwingFormDialog.Form<>(panel,() -> {
                if(list.isSelectionEmpty()) { validation.setText(i18n.get("workspace.choose.required")); return null; }
                return new Selection(false,list.getSelectedValuesList().stream().map(Choice::id).toList());
            });
        },760,430).thenApply(result -> result==null?new Selection(true,List.of()):result);
    }
    public static CompletableFuture<RoleSelection> roles(RoleRequest request,List<Session> sessions,I18nService i18n,ThemeService themes) {
        if(request.roles().isEmpty() || request.roles().size()>16 || request.roles().stream().anyMatch(role -> role.key()==null || role.key().isBlank() || role.label()==null || role.label().isBlank()) || request.roles().stream().map(Role::key).distinct().count()!=request.roles().size())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Supply 1..16 distinct role keys"));
        var candidates=candidates(sessions,request.candidateIds());
        return SwingFormDialog.showAsync(request.title(),"workspace.chooseRoles",i18n,themes,() -> {
            JPanel panel=new JPanel(new BorderLayout(8,12)); panel.setBorder(BorderFactory.createEmptyBorder(16,20,16,20));
            JLabel description=new JLabel(request.description()); description.putClientProperty("html.disable",true); panel.add(description,BorderLayout.NORTH);
            JPanel fields=new JPanel(new GridLayout(0,2,12,12));
            Map<Role,JComboBox<Choice>> selectors=new LinkedHashMap<>();
            for(Role role:request.roles()) {
                JLabel label=new JLabel(role.label()+(role.required()?" *":"")); label.putClientProperty("html.disable",true); fields.add(label);
                JComboBox<Choice> select=new JComboBox<>(); select.addItem(new Choice("",i18n.get("workspace.choose.placeholder")));
                candidates.stream().map(WorkspaceSelectionDialog::choice).forEach(select::addItem); selectors.put(role,select); fields.add(select);
            }
            panel.add(fields); JLabel validation=new JLabel(" "); panel.add(validation,BorderLayout.SOUTH);
            return new SwingFormDialog.Form<>(panel,() -> {
                Map<String,String> selected=new LinkedHashMap<>();
                for(var entry:selectors.entrySet()) {
                    Choice choice=(Choice)entry.getValue().getSelectedItem();
                    if(choice==null || choice.id().isBlank()) {
                        if(entry.getKey().required()) { validation.setText(i18n.get("workspace.choose.required")); return null; }
                    } else selected.put(entry.getKey().key(),choice.id());
                }
                return new RoleSelection(false,selected);
            });
        },800,Math.min(720,190+request.roles().size()*48)).thenApply(result -> result==null?new RoleSelection(true,Map.of()):result);
    }
}
