package com.jlshell.plugin.api;

public final class PluginCompatibility {

    private PluginCompatibility() {}

    public static Result evaluate(String hostVersion, String minInclusive, String maxInclusive) {
        String host = normalize(hostVersion);
        String min = normalize(minInclusive);
        String max = normalize(maxInclusive);
        if (min.isBlank() && max.isBlank()) {
            return new Result(PluginCompatibilityStatus.UNDECLARED, "Plugin has not declared supported host versions.");
        }
        if (!min.isBlank() && compareVersions(host, min) < 0) {
            return new Result(PluginCompatibilityStatus.INCOMPATIBLE,
                    "Plugin requires host version >= " + minInclusive + ".");
        }
        if (!max.isBlank() && compareVersions(host, max) > 0) {
            return new Result(PluginCompatibilityStatus.INCOMPATIBLE,
                    "Plugin requires host version <= " + maxInclusive + ".");
        }
        return new Result(PluginCompatibilityStatus.COMPATIBLE, "");
    }

    public static int compareVersions(String left, String right) {
        int[] a = parts(normalize(left));
        int[] b = parts(normalize(right));
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        return trimmed.replace("-SNAPSHOT", "")
                .replace(".SNAPSHOT", "")
                .replace("-RELEASE", "")
                .replace(".RELEASE", "");
    }

    private static int[] parts(String version) {
        if (version == null || version.isBlank()) {
            return new int[] {0};
        }
        String[] raw = version.split("[^0-9]+");
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (String part : raw) {
            if (part == null || part.isBlank()) {
                continue;
            }
            try {
                out.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
                out.add(0);
            }
        }
        if (out.isEmpty()) {
            return new int[] {0};
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public record Result(PluginCompatibilityStatus status, String warning) {}
}
