package com.jlshell.ui.service;

import com.jlshell.data.config.DatabaseFactory;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionFolderHierarchyTest {
    @TempDir Path temporary;
    HikariDataSource database; Jdbi jdbi; ConnectionProfileService service;
    @BeforeEach void setup() {
        database=DatabaseFactory.createDataSource("jdbc:sqlite:"+temporary.resolve("folders.db"));
        jdbi=DatabaseFactory.createJdbi(database); DatabaseFactory.initSchema(jdbi);
        service=new ConnectionProfileService(jdbi,null,null,null);
    }
    @AfterEach void close() { database.close(); }
    @Test void refusesCyclesForMoveAndSaveWithoutChangingStoredParents() {
        var root=service.saveFolder(null,"Root",null,null);
        var child=service.saveFolder(null,"Child",root.id(),null);
        var leaf=service.saveFolder(null,"Leaf",child.id(),null);
        assertThrows(IllegalArgumentException.class,()->service.moveFolderToParent(root.id(),root.id()));
        assertThrows(IllegalArgumentException.class,()->service.moveFolderToParent(root.id(),leaf.id()));
        assertThrows(IllegalArgumentException.class,()->service.saveFolder(root.id(),"Root",leaf.id(),null));
        assertNull(parent(root.id())); assertEquals(root.id(),parent(child.id()));
        assertThrows(IllegalArgumentException.class,()->service.moveFolderToParent(root.id(),"missing"));
        assertThrows(IllegalArgumentException.class,()->service.saveFolder(null,"Foreign",root.id(),"other-project"));
        assertEquals(3,service.listFolders(null).size());
        service.moveFolderToParent(leaf.id(),null); assertNull(parent(leaf.id()));
        service.moveFolderToParent(child.id(),leaf.id()); assertEquals(leaf.id(),parent(child.id()));
    }
    @Test void legacyCycleCanBeMovedOutButCannotBeUsedAsANewParent() {
        var a=service.saveFolder(null,"A",null,null); var b=service.saveFolder(null,"B",a.id(),null);
        jdbi.useHandle(h->h.execute("UPDATE connection_folders SET parent_id=? WHERE id=?",b.id(),a.id()));
        assertThrows(IllegalArgumentException.class,()->service.saveFolder(null,"New",b.id(),null));
        service.moveFolderToParent(a.id(),null);
        assertNull(parent(a.id())); assertEquals(a.id(),parent(b.id()));
    }
    @Test void deletingLegacyCycleKeepsConnectionsAndUnrelatedFolders() {
        var a=service.saveFolder(null,"A",null,null); var b=service.saveFolder(null,"B",a.id(),null);
        var other=service.saveFolder(null,"Other",null,null);
        jdbi.useHandle(h->{
            h.execute("UPDATE connection_folders SET parent_id=? WHERE id=?",b.id(),a.id());
            h.execute("INSERT INTO connections(id,display_name,folder_id,created_at,updated_at) VALUES ('conn','Preserved',?,0,0)",b.id());
        });
        assertEquals(List.of("Preserved"),service.collectConnectionNamesUnderFolder(a.id()));
        service.deleteFolder(a.id());
        assertEquals(List.of(other.id()),service.listFolders(null).stream().map(f->f.id()).toList());
        jdbi.useHandle(h->{
            assertEquals(1,h.createQuery("SELECT count(*) FROM connections").mapTo(Integer.class).one());
            assertNull(h.createQuery("SELECT folder_id FROM connections WHERE id='conn'").mapTo(String.class).one());
        });
    }
    private String parent(String id) { return jdbi.withHandle(h->h.createQuery("SELECT parent_id FROM connection_folders WHERE id=:id").bind("id",id).mapTo(String.class).one()); }
}
