package com.jlshell.ui.view;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.jlshell.core.model.CommandRequest;
import com.jlshell.core.model.FontProfile;
import com.jlshell.core.model.ShellRequest;
import com.jlshell.core.model.TerminalSize;
import com.jlshell.core.service.AppSettingsService;
import com.jlshell.core.service.FontProfileService;
import com.jlshell.core.session.SshSession;
import com.jlshell.core.support.MutableValue;
import com.jlshell.plugin.api.ui.ObservableValue;
import com.jlshell.plugin.api.lifecycle.Registration;
import com.jlshell.terminal.model.TerminalColorScheme;
import com.jlshell.terminal.model.TerminalRuntimeSettings;
import com.jlshell.terminal.model.TerminalViewRequest;
import com.jlshell.terminal.service.TerminalViewFactory;
import com.jlshell.terminal.service.TerminalViewHandle;
import com.jlshell.terminal.support.DefaultTerminalViewHandle;
import com.jlshell.terminal.support.ShellTtyConnector;
import com.jlshell.terminal.support.SwingExecutors;
import com.jlshell.ui.config.JlshellDefaults;
import com.jlshell.ui.dialog.PreferencesDialog;
import com.jlshell.ui.service.I18nService;
import com.jlshell.ui.theme.ThemeService;
import com.jlshell.ui.swing.NativeTerminalHost;
import com.jlshell.ui.model.ServerMonitorSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 会话终端与监控控制器。Swing EDT 串行管理生命周期，SSH 采样异步执行。 */
public final class TerminalWorkspaceView {
    private static final Logger log = LoggerFactory.getLogger(TerminalWorkspaceView.class);
    public static final String STATUS_BAR_VISIBLE_KEY = "ui.terminal.statusBar.visible";
    private static final java.time.Duration STATUS_COMMAND_TIMEOUT = java.time.Duration.ofSeconds(8);
    private final SshSession sshSession;
    private final TerminalViewFactory terminalViewFactory;
    private final FontProfileService fontProfileService;
    private final AppSettingsService appSettingsService;
    private final I18nService i18nService;
    private final ThemeService themeService;
    private final java.util.function.Consumer<String> sessionDisconnectListener;
    private final List<TerminalViewHandle> handles = new ArrayList<>();
    private final List<Registration> cwdListeners = new ArrayList<>();
    private final MutableValue<String> cwd = new MutableValue<>("");
    private final MutableValue<ServerMonitorSnapshot> monitorSnapshot = new MutableValue<>(null);
    private final int warningThreshold, dangerThreshold;
    private volatile boolean closing, disconnected;
    private NativeTerminalHost nativeTerminalHost;
    private Runnable onReconnect;
    private javax.swing.Timer statusRefreshTimer, serverClockTimer;
    private boolean statusRefreshInFlight;
    private long statusSamplingGeneration;
    private long previousRxBytes = -1, previousTxBytes = -1, previousCpuTotal = -1, previousCpuIdle = -1, previousSampleNanos = -1;
    private String currentDownloadRate = "--", currentUploadRate = "--", currentCpuUsage = "--";
    private int currentCpuPercent = -1;
    private long remoteClockEpochSeconds, remoteClockSampleNanos = -1;
    private String remoteClockZone = "--", remoteClockOffset = "+0000", serverClock = "--";
    private StatusSummary lastStatusSummary;
    private long monitorSample;
    private double monitorNetworkRate;

    public ObservableValue<String> cwdProperty() { return cwd; }
    public ObservableValue<ServerMonitorSnapshot> monitorSnapshotProperty() { return monitorSnapshot; }
    public int monitorWarningThreshold() { return warningThreshold; }
    public int monitorDangerThreshold() { return dangerThreshold; }

    public TerminalWorkspaceView(SshSession session, TerminalViewFactory factory, FontProfileService fonts,
            AppSettingsService settings, I18nService i18n, ThemeService themes) {
        sshSession = session; terminalViewFactory = factory; fontProfileService = fonts;
        appSettingsService = settings; i18nService = i18n; themeService = themes;
        ResourceThresholds thresholds = ResourceThresholds.of(JlshellDefaults.statusWarningPercent(), JlshellDefaults.statusDangerPercent());
        warningThreshold = thresholds.warning(); dangerThreshold = thresholds.danger();
        sessionDisconnectListener = reason -> {
            com.jlshell.plugin.loader.HostExtensionRegistry.error("DISCONNECTED",sshSession.sessionId().toString(),null,reason);
            SwingExecutors.runOnEdtAsync(() -> onTerminalDisconnected(ShellTtyConnector.DisconnectReason.IO_ERROR));
        };
        sshSession.addDisconnectListener(sessionDisconnectListener);
    }

    public void focusTerminal(String terminalId) {
        var handle=handles.stream().filter(value -> terminalId==null || value.terminalAccess().map(access -> terminalId.equals(access.terminalId())).orElse(false))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Terminal not found"));
        handle.requestFocus();
    }
    public void setNativeTerminalHost(NativeTerminalHost host) { nativeTerminalHost = host; }
    public void setOnReconnect(Runnable reconnect) { onReconnect = reconnect; }
    public boolean ownsTerminalFocus(String terminalId, java.awt.Component focus) {
        if (focus == null) return false;
        return handles.stream().anyMatch(handle -> handle.terminalAccess().map(access -> terminalId.equals(access.terminalId())).orElse(false)
                && (focus == handle.component() || javax.swing.SwingUtilities.isDescendingFrom(focus, handle.component())));
    }
    public boolean isDisconnected() { return disconnected; }

    public CompletableFuture<Void> initialize() {
        log.info("Initializing terminal workspace for session {}", sshSession.sessionId());
        TerminalViewRequest request = new TerminalViewRequest(sshSession.displayName(),
                new ShellRequest("xterm-256color", new TerminalSize(120, 40, 0, 0), null),
                fontProfileService.activeProfile(), themeService.activeColorScheme(), terminalRuntimeSettings());
        return terminalViewFactory.createTerminalView(sshSession, request).thenCompose(handle -> SwingExecutors.runOnEdtAsync(() -> {
            if (closing) {
                handle.closeAsync();
                throw new java.util.concurrent.CancellationException("Terminal workspace is closing");
            }
            handles.add(handle);
            handle.textActions(() -> {
                String text=handle.selectedText();
                var selection=new com.jlshell.plugin.api.host.HostExtensions.TextSelection(handle.sessionId().toString(),text,handle.currentDirectory());
                var actions=new java.util.ArrayList<com.jlshell.terminal.support.RefreshableTerminalPanel.PopupAction>();
                var names = new java.util.HashMap<String, String>();
                for(var contribution:com.jlshell.plugin.loader.HostExtensionRegistry.textContributions(handle.sessionId().toString())) {
                    var action = contribution.value();
                    names.put(contribution.pluginId(), contribution.pluginName());
                    try { if(action.visible().test(selection)) actions.add(new com.jlshell.terminal.support.RefreshableTerminalPanel.PopupAction(
                            action.title(),"",true,false,() -> { try { action.execute().accept(selection); } catch(RuntimeException error) { com.jlshell.plugin.loader.HostExtensionRegistry.report(error); } },
                            action.placement() == com.jlshell.plugin.api.host.HostExtensions.MenuPlacement.TOP_LEVEL ? null : contribution.pluginId(), java.util.List.of())); }
                    catch(RuntimeException error) { com.jlshell.plugin.loader.HostExtensionRegistry.report(error); }
                }
                var grouped = new java.util.LinkedHashMap<String, java.util.List<com.jlshell.terminal.support.RefreshableTerminalPanel.PopupAction>>();
                var result = new java.util.ArrayList<com.jlshell.terminal.support.RefreshableTerminalPanel.PopupAction>();
                for (var action : actions) {
                    if (action.pluginGroup() == null) { result.add(action); continue; }
                    grouped.computeIfAbsent(action.pluginGroup(), key -> new java.util.ArrayList<>()).add(action);
                }
                for (var entry : grouped.entrySet()) {
                    String name = names.get(entry.getKey());
                    result.add(new com.jlshell.terminal.support.RefreshableTerminalPanel.PopupAction(name, "", true, false, null, null, entry.getValue()));
                }
                return result;
            });
            handle.terminalAccess().ifPresent(access -> cwdListeners.add(
                    com.jlshell.plugin.loader.HostExtensionRegistry.terminal(
                            new com.jlshell.plugin.api.host.HostExtensions.Session(
                                    access.sessionId(), sshSession.displayName(), sshSession.target().host(),
                                    sshSession.target().username(), true, false), access)));

            cwdListeners.add(handle.onDirectoryChanged(path -> {
                if (!closing && path != null && !path.isBlank()) cwd.set(path);
            }));
            if (handle instanceof DefaultTerminalViewHandle terminal) terminal.setOnDisconnected(reason ->
                    SwingExecutors.runOnEdtAsync(() -> onTerminalDisconnected(reason)));
            if (nativeTerminalHost == null) throw new IllegalStateException("Native terminal host is required");
            nativeTerminalHost.attach(handle);
            startStatusSampling();
            log.info("Terminal workspace initialized for session {}", sshSession.sessionId());
        }));
    }

    public void applyColorScheme(TerminalColorScheme scheme) {
        SwingExecutors.runOnEdtAsync(() -> handles.forEach(handle -> handle.updateColorScheme(scheme)));
    }

    public void setStatusBarVisible(boolean visible) {
        appSettingsService.set(STATUS_BAR_VISIBLE_KEY, String.valueOf(visible));
        // 监控是独立工具窗口，折叠底栏后仍可使用右侧的监控页。
    }

    public CompletableFuture<Void> closeAsync() {
        closing = true;
        sshSession.removeDisconnectListener(sessionDisconnectListener);
        return SwingExecutors.supplyOnEdtAsync(() -> {
            stopStatusSampling();
            cwdListeners.forEach(Registration::close); cwdListeners.clear();
            List<TerminalViewHandle> snapshot = List.copyOf(handles); handles.clear();
            snapshot.forEach(handle -> { if (handle instanceof DefaultTerminalViewHandle terminal) terminal.setOnDisconnected(null); });
            nativeTerminalHost = null; onReconnect = null;
            return snapshot;
        }).thenCompose(snapshot -> CompletableFuture.allOf(snapshot.stream().map(TerminalViewHandle::closeAsync).toArray(CompletableFuture[]::new)));
    }

    private void onTerminalDisconnected(ShellTtyConnector.DisconnectReason reason) {
        if (closing || disconnected || reason == ShellTtyConnector.DisconnectReason.USER_CLOSE) return;
        disconnected = true;
        com.jlshell.plugin.loader.HostExtensionRegistry.disconnected(sshSession.sessionId().toString());
        stopStatusSampling();
        String message = i18nService.get(reason == ShellTtyConnector.DisconnectReason.REMOTE_CLOSED
                ? "terminal.disconnected.remoteClosed" : "terminal.disconnected.ioError");
        log.warn("[Terminal] Session {} disconnected, reason={}", sshSession.sessionId(), reason);
        publishMonitorSnapshot(message);
        if (nativeTerminalHost != null) nativeTerminalHost.disconnected(message, () -> {
            if (!closing && onReconnect != null) onReconnect.run();
        });
        closeTerminalHandlesAfterDisconnect(reason);
    }

    public void markReconnected() {
        SwingExecutors.runOnEdtAsync(() -> { if (!closing) { disconnected = false; startStatusSampling(); } });
    }

    public void openFontSettings() {
        PreferencesDialog.show(null, fontProfileService, appSettingsService, i18nService, themeService,
                null, null, null, PreferencesDialog.TAB_TERMINAL).thenRun(() -> SwingExecutors.runOnEdtAsync(() -> {
                    FontProfile profile = fontProfileService.activeProfile();
                    handles.forEach(handle -> handle.updateFontProfile(profile));
                }));
    }

    private void startStatusSampling() {
        if (closing || disconnected || handles.isEmpty()) return;
        if (statusRefreshTimer == null) statusRefreshTimer = new javax.swing.Timer(3000, event -> refreshStatusSummary());
        if (serverClockTimer == null) serverClockTimer = new javax.swing.Timer(1000, event -> updateServerClockLabel());
        if (!statusRefreshTimer.isRunning()) { resetStatusSamplingBaseline(); refreshStatusSummary(); statusRefreshTimer.start(); }
        if (!serverClockTimer.isRunning()) serverClockTimer.start();
    }

    private void stopStatusSampling() {
        statusSamplingGeneration++;
        if (statusRefreshTimer != null) statusRefreshTimer.stop();
        if (serverClockTimer != null) serverClockTimer.stop();
        statusRefreshInFlight = false;
        resetStatusSamplingBaseline();
    }

    private void refreshStatusSummary() {
        if (closing || disconnected || statusRefreshInFlight || handles.isEmpty()) return;
        statusRefreshInFlight = true;
        long generation = statusSamplingGeneration;
        sshSession.execute(new CommandRequest(buildStatusSummaryCommand(), STATUS_COMMAND_TIMEOUT, false, null))
                .whenComplete((output, error) -> SwingExecutors.runOnEdtAsync(() -> {
                    if (closing || generation != statusSamplingGeneration) return;
                    statusRefreshInFlight = false;
                    if (error != null) {
                        publishMonitorSnapshot(error.getMessage());
                        log.debug("Unable to refresh terminal status for session {}", sshSession.sessionId(), error);
                    } else applyStatusSummary(parseStatusSummary(output.stdout() == null ? "" : output.stdout()));
                }));
    }

    private void closeTerminalHandlesAfterDisconnect(ShellTtyConnector.DisconnectReason reason) {
        List<TerminalViewHandle> snapshot = new ArrayList<>(handles);
        log.info("[Terminal] Closing {} terminal handle(s) after disconnect for session {}, reason={}",
                snapshot.size(), sshSession.sessionId(), reason);
        for (TerminalViewHandle handle : snapshot) {
            CompletableFuture<Void> closeFuture;
            if (handle instanceof DefaultTerminalViewHandle defaultHandle) {
                closeFuture = defaultHandle.closeAfterDisconnectAsync();
            } else {
                closeFuture = handle.closeAsync();
            }
            closeFuture.whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    log.warn("[Terminal] Terminal handle cleanup failed for session {}: {}",
                            sshSession.sessionId(), throwable.getMessage());
                }
            });
        }
    }

    /** Reuses the existing read-only SSH detail commands; caller may use the returned future from any thread. */
    public java.util.concurrent.CompletableFuture<String> queryMonitorDetail(String type) {
        if (disconnected) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Disconnected"));
        if (!java.util.Set.of("cpu", "mem", "ip", "os", "disk").contains(type))
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Unknown metric"));
        return sshSession.execute(new CommandRequest(buildDetailCmd(type), java.time.Duration.ofSeconds(10), false, null))
                .thenApply(output -> {
                    if (output.exitCode() != null && output.exitCode() != 0 && (output.stdout() == null || output.stdout().isBlank()))
                        throw new IllegalStateException(output.stderr());
                    return output.stdout() == null ? "" : output.stdout();
                });
    }

    private void publishMonitorSnapshot(String failure) {
        StatusSummary summary = lastStatusSummary;
        if (summary == null) {
            var missing = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource("—", "—", -1, -1);
            monitorSnapshot.set(new com.jlshell.ui.model.ServerMonitorSnapshot(0, "—", "—", "—", "—", "—", "—",
                    missing, missing, missing, missing, failure));
            return;
        }
        var cpu = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource(currentCpuUsage,
                summary.cores() + " cores · Load " + formatDecimal(summary.load()), currentCpuPercent, currentCpuPercent);
        int memory = usagePercent(summary.memUsed(), summary.memTotal());
        var mem = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource(memory < 0 ? "--" : memory + "%",
                formatBytes(summary.memUsed()) + " / " + formatBytes(summary.memTotal()), memory, memory);
        var network = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource("↓ " + currentDownloadRate,
                "↑ " + currentUploadRate, monitorNetworkRate, -1);
        var disk = new com.jlshell.ui.model.ServerMonitorSnapshot.Resource(summary.diskPercent() < 0 ? "--" : summary.diskPercent() + "%",
                formatBytes(summary.diskUsed()) + " / " + formatBytes(summary.diskTotal()), summary.diskPercent(), summary.diskPercent());
        monitorSnapshot.set(new com.jlshell.ui.model.ServerMonitorSnapshot(monitorSample, summary.host(), summary.os(), summary.kernel(),
                summary.ip(), summary.iface(), serverClock, cpu, mem, network, disk, failure));
    }

    private void resetStatusSamplingBaseline() {
        previousRxBytes = -1;
        previousTxBytes = -1;
        previousCpuTotal = -1;
        previousCpuIdle = -1;
        previousSampleNanos = -1;
    }

    private String buildStatusSummaryCommand() {
        return """
                LC_ALL=C
                if [ -r /etc/os-release ]; then
                  . /etc/os-release
                  jl_os="${PRETTY_NAME:-${NAME:-Linux}}"
                else
                  jl_os="$(uname -s 2>/dev/null) $(uname -r 2>/dev/null)"
                fi
                jl_host="$(hostname 2>/dev/null || uname -n 2>/dev/null)"
                jl_kernel="$(uname -srmo 2>/dev/null || uname -a 2>/dev/null)"
                jl_iface="$(ip route show default 2>/dev/null | awk 'NR==1 {print $5}')"
                if [ -z "$jl_iface" ]; then
                  jl_iface="$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')"
                fi
                jl_ip=""
                if [ -n "$jl_iface" ]; then
                  jl_ip="$(ip -o -4 addr show dev "$jl_iface" 2>/dev/null | awk 'NR==1 {split($4,a,"/"); print a[1]}')"
                  [ -n "$jl_ip" ] || jl_ip="$(ipconfig getifaddr "$jl_iface" 2>/dev/null)"
                fi
                [ -n "$jl_ip" ] || jl_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
                jl_rx=0; jl_tx=0
                if [ -n "$jl_iface" ] && [ -r "/sys/class/net/$jl_iface/statistics/rx_bytes" ]; then
                  jl_rx="$(cat "/sys/class/net/$jl_iface/statistics/rx_bytes" 2>/dev/null)"
                  jl_tx="$(cat "/sys/class/net/$jl_iface/statistics/tx_bytes" 2>/dev/null)"
                elif [ -n "$jl_iface" ]; then
                  set -- $(netstat -ibn 2>/dev/null | awk -v i="$jl_iface" '$1==i && $7 ~ /^[0-9]+$/ {rx=$7; tx=$10} END {print rx+0, tx+0}')
                  jl_rx="${1:-0}"; jl_tx="${2:-0}"
                fi
                set -- $(awk '/^cpu / {idle=$5+$6; total=0; for(i=2;i<=NF;i++) total+=$i; print total, idle; exit}' /proc/stat 2>/dev/null)
                jl_cpu_total="${1:-0}"; jl_cpu_idle="${2:-0}"
                jl_load="$(awk '{print $1}' /proc/loadavg 2>/dev/null)"
                [ -n "$jl_load" ] || jl_load="$(uptime 2>/dev/null | sed 's/.*load averages*[: ] *//' | cut -d, -f1 | xargs)"
                jl_cores="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.logicalcpu 2>/dev/null || echo 0)"
                set -- $(awk '/MemTotal:/{t=$2} /MemAvailable:/{a=$2} END{if(t>0) printf "%.0f %.0f\\n",t*1024,(t-a)*1024}' /proc/meminfo 2>/dev/null)
                jl_mem_total="${1:-0}"; jl_mem_used="${2:-0}"
                if [ "$jl_mem_total" = 0 ]; then
                  jl_mem_total="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
                  jl_page_size="$(pagesize 2>/dev/null || echo 4096)"
                  jl_mem_used="$(vm_stat 2>/dev/null | awk -v p="$jl_page_size" '
                    /Pages active:/ {a=$3} /Pages wired down:/ {w=$4} /Pages occupied by compressor:/ {c=$5}
                    END {gsub("\\.","",a); gsub("\\.","",w); gsub("\\.","",c); printf "%.0f\\n",(a+w+c)*p}')"
                  [ -n "$jl_mem_used" ] || jl_mem_used=0
                fi
                set -- $(df -Pk / 2>/dev/null | awk 'NR==2 {gsub("%","",$5); printf "%.0f %.0f %s\\n",$2*1024,$3*1024,$5}')
                jl_disk_total="${1:-0}"; jl_disk_used="${2:-0}"; jl_disk_pct="${3:-0}"
                jl_time_epoch="$(date +%s 2>/dev/null || echo 0)"
                jl_time_zone="$(date +%Z 2>/dev/null || echo UTC)"
                jl_time_offset="$(date +%z 2>/dev/null || echo +0000)"
                printf 'JL_OS\t%s\n' "$jl_os"
                printf 'JL_HOST\t%s\n' "$jl_host"
                printf 'JL_KERNEL\t%s\n' "$jl_kernel"
                printf 'JL_NET\t%s\t%s\t%s\t%s\n' "$jl_iface" "$jl_ip" "$jl_rx" "$jl_tx"
                printf 'JL_CPU\t%s\t%s\t%s\t%s\n' "$jl_cpu_total" "$jl_cpu_idle" "$jl_load" "$jl_cores"
                printf 'JL_MEM\t%s\t%s\n' "$jl_mem_total" "$jl_mem_used"
                printf 'JL_DISK\t%s\t%s\t%s\n' "$jl_disk_total" "$jl_disk_used" "$jl_disk_pct"
                printf 'JL_TIME\t%s\t%s\t%s\n' "$jl_time_epoch" "$jl_time_zone" "$jl_time_offset"
                """;
    }

    private StatusSummary parseStatusSummary(String raw) {
        String os = "--";
        String host = "--";
        String kernel = "--";
        String iface = "--";
        String ip = "--";
        long rx = 0;
        long tx = 0;
        long cpuTotal = 0;
        long cpuIdle = 0;
        double load = -1;
        int cores = 0;
        long memTotal = 0;
        long memUsed = 0;
        long diskTotal = 0;
        long diskUsed = 0;
        int diskPercent = 0;
        long remoteEpochSeconds = 0;
        String remoteTimezone = "--";
        String remoteTimezoneOffset = "+0000";
        for (String line : raw.split("\\R")) {
            String[] parts = line.split("\\t", -1);
            if (parts.length < 2) continue;
            switch (parts[0]) {
                case "JL_OS" -> os = parts[1];
                case "JL_HOST" -> host = parts[1];
                case "JL_KERNEL" -> kernel = parts[1];
                case "JL_NET" -> {
                    if (parts.length >= 5) {
                        iface = parts[1].isBlank() ? "--" : parts[1];
                        ip = parts[2].isBlank() ? "--" : parts[2];
                        rx = parseLong(parts[3]);
                        tx = parseLong(parts[4]);
                    }
                }
                case "JL_CPU" -> {
                    if (parts.length >= 5) {
                        cpuTotal = parseLong(parts[1]);
                        cpuIdle = parseLong(parts[2]);
                        load = parseDouble(parts[3]);
                        cores = (int) parseLong(parts[4]);
                    }
                }
                case "JL_MEM" -> {
                    if (parts.length >= 3) {
                        memTotal = parseLong(parts[1]);
                        memUsed = parseLong(parts[2]);
                    }
                }
                case "JL_DISK" -> {
                    if (parts.length >= 4) {
                        diskTotal = parseLong(parts[1]);
                        diskUsed = parseLong(parts[2]);
                        diskPercent = (int) parseLong(parts[3]);
                    }
                }
                case "JL_TIME" -> {
                    if (parts.length >= 4) {
                        remoteEpochSeconds = parseLong(parts[1]);
                        remoteTimezone = parts[2].isBlank() ? "--" : parts[2];
                        remoteTimezoneOffset = parts[3].isBlank() ? "+0000" : parts[3];
                    }
                }
                default -> { }
            }
        }
        return new StatusSummary(os, host, kernel, iface, ip, rx, tx, cpuTotal, cpuIdle, load, cores,
                memTotal, memUsed, diskTotal, diskUsed, diskPercent,
                remoteEpochSeconds, remoteTimezone, remoteTimezoneOffset);
    }

    private void applyStatusSummary(StatusSummary summary) {
        lastStatusSummary = summary;
        long now = System.nanoTime();
        if (summary.remoteEpochSeconds() > 0) {
            remoteClockEpochSeconds = summary.remoteEpochSeconds();
            remoteClockSampleNanos = now;
            remoteClockZone = summary.remoteTimezone();
            remoteClockOffset = summary.remoteTimezoneOffset();
            updateServerClockLabel();
        }
        if (previousSampleNanos > 0 && now > previousSampleNanos) {
            double elapsedSeconds = (now - previousSampleNanos) / 1_000_000_000.0;
            if (summary.rxBytes() >= previousRxBytes && summary.txBytes() >= previousTxBytes
                    && previousRxBytes >= 0 && previousTxBytes >= 0) {
                currentDownloadRate = formatRate((summary.rxBytes() - previousRxBytes) / elapsedSeconds);
                currentUploadRate = formatRate((summary.txBytes() - previousTxBytes) / elapsedSeconds);
                monitorNetworkRate = (summary.rxBytes() - previousRxBytes + summary.txBytes() - previousTxBytes) / elapsedSeconds;
            }
        }
        if (previousCpuTotal >= 0 && summary.cpuTotal() > previousCpuTotal) {
            long totalDelta = summary.cpuTotal() - previousCpuTotal;
            long idleDelta = summary.cpuIdle() - previousCpuIdle;
            int usage = (int) Math.round(100.0 * Math.max(0, totalDelta - idleDelta) / totalDelta);
            currentCpuPercent = clampPercent(usage);
            currentCpuUsage = currentCpuPercent + "%";
        } else if (summary.load() >= 0 && summary.cores() > 0) {
            int pressure = (int) Math.round(Math.min(100, summary.load() / summary.cores() * 100));
            currentCpuPercent = clampPercent(pressure);
            currentCpuUsage = currentCpuPercent + "%";
        }

        previousSampleNanos = now;
        previousRxBytes = summary.rxBytes();
        previousTxBytes = summary.txBytes();
        previousCpuTotal = summary.cpuTotal();
        previousCpuIdle = summary.cpuIdle();

        monitorSample++;
        publishMonitorSnapshot(null);
    }

    private void updateServerClockLabel() {
        if (closing || remoteClockEpochSeconds <= 0 || remoteClockSampleNanos <= 0) return;
        long elapsedSeconds = Math.max(0, (System.nanoTime() - remoteClockSampleNanos) / 1_000_000_000L);
        java.time.ZoneOffset offset = parseRemoteZoneOffset(remoteClockOffset);
        String time = java.time.Instant.ofEpochSecond(remoteClockEpochSeconds + elapsedSeconds)
                .atOffset(offset)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.ROOT));
        serverClock = time + " " + remoteClockZone;
        publishMonitorSnapshot(monitorSnapshot.get() == null ? null : monitorSnapshot.get().failure());
    }

    static java.time.ZoneOffset parseRemoteZoneOffset(String rawOffset) {
        String value = rawOffset == null ? "" : rawOffset.strip();
        if (value.matches("[+-]\\d{4}")) {
            value = value.substring(0, 3) + ":" + value.substring(3);
        }
        try {
            return java.time.ZoneOffset.of(value);
        } catch (java.time.DateTimeException ignored) {
            return java.time.ZoneOffset.UTC;
        }
    }

    private static int usagePercent(long used, long total) {
        if (total <= 0) return -1;
        return clampPercent((int) Math.round(used * 100.0 / total));
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private static String formatRate(double bytesPerSecond) {
        return formatBytes((long) Math.max(0, bytesPerSecond)) + "/s";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return (value >= 10 ? String.format(java.util.Locale.ROOT, "%.0f", value)
                : String.format(java.util.Locale.ROOT, "%.1f", value)) + " " + units[unit];
    }

    private static String formatDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null || value.isBlank() ? "0" : value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value == null || value.isBlank() ? "-1" : value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private record StatusSummary(
            String os, String host, String kernel, String iface, String ip,
            long rxBytes, long txBytes, long cpuTotal, long cpuIdle, double load, int cores,
            long memTotal, long memUsed, long diskTotal, long diskUsed, int diskPercent,
            long remoteEpochSeconds, String remoteTimezone, String remoteTimezoneOffset
    ) { }

    record ProcessUsage(
            String pid,
            String user,
            double cpuPercent,
            double memPercent,
            long residentBytes,
            String command
    ) { }

    record ResourceThresholds(int warning, int danger) {
        static ResourceThresholds of(int warning, int danger) {
            int normalizedWarning = Math.max(1, Math.min(98, warning));
            int normalizedDanger = Math.max(normalizedWarning + 1, Math.min(100, danger));
            return new ResourceThresholds(normalizedWarning, normalizedDanger);
        }
    }

    private String buildDetailCmd(String type) {
        switch (type) {
            case "ip":
                return "echo '---ADDRESS---'; (ip -brief addr show 2>/dev/null || ifconfig 2>/dev/null); "
                        + "echo '---ROUTE---'; (ip route show 2>/dev/null || netstat -rn 2>/dev/null)";
            case "os":
                return "hostnamectl 2>/dev/null || (echo \"Hostname: $(hostname)\"; "
                        + "echo \"Operating System: $(uname -s) $(uname -r)\"; "
                        + "echo \"Architecture: $(uname -m)\"; "
                        + "echo \"Kernel: $(uname -r)\")";
            case "cpu":
                return "echo \"Load average: $(cat /proc/loadavg 2>/dev/null | awk '{print $1, $2, $3}' || uptime)\"; "
                        + "lscpu 2>/dev/null || (echo \"Model name: $(sysctl -n machdep.cpu.brand_string 2>/dev/null)\"; "
                        + "echo \"CPU(s): $(sysctl -n hw.logicalcpu 2>/dev/null)\"); "
                        + "echo '---PROCESSES---'; " + buildProcessCommand("cpu");
            case "mem":
                return "free -h 2>/dev/null || (echo \"Total: $(sysctl -n hw.memsize 2>/dev/null | awk '{print int($1/1073741824)\" GB\"}')\"; "
                        + "vm_stat 2>/dev/null); "
                        + "echo \"---SWAP---\"; "
                        + "swapon --show 2>/dev/null || echo \"Swap: $(sysctl -n vm.swapusage 2>/dev/null || echo N/A)\"; "
                        + "echo '---PROCESSES---'; " + buildProcessCommand("mem");
            case "disk":
                return "df -Pk 2>/dev/null || df -k 2>/dev/null";
            default:
                return "echo N/A";
        }
    }

    private String buildProcessCommand(String type) {
        int sortColumn = "mem".equals(type) ? 5 : 3;
        return "{ ps -Ao pid=,user=,pcpu=,pmem=,rss=,comm= 2>/dev/null "
                + "|| ps aux 2>/dev/null | awk 'NR>1 {print $2, $1, $3, $4, $6, $11}'; } "
                + "| sort -k" + sortColumn + "," + sortColumn + "nr | head -n 10";
    }

    static List<ProcessUsage> parseTopProcesses(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<ProcessUsage> result = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\s+", 6);
            if (parts.length < 6 || !parts[0].matches("\\d+")) continue;
            result.add(new ProcessUsage(
                    parts[0],
                    parts[1],
                    parseDouble(parts[2]),
                    parseDouble(parts[3]),
                    parseLong(parts[4]) * 1024,
                    parts[5]
            ));
            if (result.size() == 10) break;
        }
        return List.copyOf(result);
    }

    private TerminalRuntimeSettings terminalRuntimeSettings() {
        String raw = appSettingsService.get(
                "terminal.scrollback.lines",
                String.valueOf(TerminalRuntimeSettings.DEFAULT_SCROLLBACK_LINES));
        try {
            return new TerminalRuntimeSettings(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return TerminalRuntimeSettings.defaults();
        }
    }

}
