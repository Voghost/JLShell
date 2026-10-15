package com.jlshell.ui.model;

/** Immutable sampling result shared by the FX session controller and native Swing presentation. */
public record ServerMonitorSnapshot(long sample, String host, String os, String kernel,
                                    String address, String networkInterface, String clock,
                                    Resource cpu, Resource memory, Resource network, Resource disk,
                                    String failure) {
    public record Resource(String value, String detail, double activity, int percent) {}
}
