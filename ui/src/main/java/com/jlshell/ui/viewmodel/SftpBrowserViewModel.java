package com.jlshell.ui.viewmodel;

import java.nio.file.Path;
import java.util.List;

import com.jlshell.sftp.model.RemoteFileEntry;
import com.jlshell.ui.model.LocalFileEntry;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * SFTP 文件面板 ViewModel。
 */
public class SftpBrowserViewModel {

    private final ObservableList<LocalFileEntry> localEntries = FXCollections.observableArrayList();
    private final ObservableList<RemoteFileEntry> remoteEntries = FXCollections.observableArrayList();
    private final StringProperty localPath = new SimpleStringProperty();
    private final StringProperty remotePath = new SimpleStringProperty();
    private final StringProperty transferStatus = new SimpleStringProperty();
    private final StringProperty transferSpeed = new SimpleStringProperty();
    private final DoubleProperty transferProgress = new SimpleDoubleProperty(0);
    private final BooleanProperty transferring = new SimpleBooleanProperty(false);
    private final StringProperty transferFileName = new SimpleStringProperty();
    private final StringProperty transferFileIndex = new SimpleStringProperty();

    public ObservableList<LocalFileEntry> localEntries() {
        return localEntries;
    }

    public ObservableList<RemoteFileEntry> remoteEntries() {
        return remoteEntries;
    }

    public StringProperty localPathProperty() {
        return localPath;
    }

    public StringProperty remotePathProperty() {
        return remotePath;
    }

    public StringProperty transferStatusProperty() {
        return transferStatus;
    }

    public DoubleProperty transferProgressProperty() {
        return transferProgress;
    }

    public BooleanProperty transferringProperty() {
        return transferring;
    }

    public StringProperty transferFileNameProperty() {
        return transferFileName;
    }

    public StringProperty transferFileIndexProperty() {
        return transferFileIndex;
    }

    public StringProperty transferSpeedProperty() {
        return transferSpeed;
    }

    public void setLocalEntries(Path directory, List<LocalFileEntry> entries) {
        localPath.set(directory.toString());
        localEntries.setAll(entries);
    }

    public void setRemoteEntries(String directory, List<RemoteFileEntry> entries) {
        remotePath.set(directory);
        remoteEntries.setAll(entries);
    }

    public void resetTransferState() {
        transferring.set(false);
        transferProgress.set(0);
        transferFileName.set("");
        transferFileIndex.set("");
        transferSpeed.set("");
        transferStatus.set("");
    }
}
