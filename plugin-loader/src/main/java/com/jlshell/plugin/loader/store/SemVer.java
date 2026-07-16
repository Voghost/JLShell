package com.jlshell.plugin.loader.store;

import java.util.List;

/** 最小的 SemVer 2.0 比较器，避免按字符串选择升级版本。 */
final class SemVer implements Comparable<SemVer> {
    private final long major;
    private final long minor;
    private final long patch;
    private final List<String> preRelease;

    private SemVer(long major, long minor, long patch, List<String> preRelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
    }

    static SemVer parse(String value) {
        if (value == null || !value.matches("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                + "(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?"
                + "(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?")) {
            throw new IllegalArgumentException("Invalid SemVer: " + value);
        }
        String withoutBuild = value.split("\\+", 2)[0];
        String[] mainAndPre = withoutBuild.split("-", 2);
        String[] numbers = mainAndPre[0].split("\\.");
        List<String> pre = mainAndPre.length == 1 ? List.of() : List.of(mainAndPre[1].split("\\."));
        return new SemVer(Long.parseLong(numbers[0]), Long.parseLong(numbers[1]), Long.parseLong(numbers[2]), pre);
    }

    @Override
    public int compareTo(SemVer other) {
        int compare = Long.compare(major, other.major);
        if (compare == 0) compare = Long.compare(minor, other.minor);
        if (compare == 0) compare = Long.compare(patch, other.patch);
        if (compare != 0) return compare;
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return preRelease.isEmpty() == other.preRelease.isEmpty() ? 0 : (preRelease.isEmpty() ? 1 : -1);
        }
        int count = Math.min(preRelease.size(), other.preRelease.size());
        for (int i = 0; i < count; i++) {
            String left = preRelease.get(i);
            String right = other.preRelease.get(i);
            boolean leftNumeric = left.chars().allMatch(Character::isDigit);
            boolean rightNumeric = right.chars().allMatch(Character::isDigit);
            if (leftNumeric && rightNumeric) compare = Long.compare(Long.parseLong(left), Long.parseLong(right));
            else if (leftNumeric) compare = -1;
            else if (rightNumeric) compare = 1;
            else compare = left.compareTo(right);
            if (compare != 0) return compare;
        }
        return Integer.compare(preRelease.size(), other.preRelease.size());
    }
}
