package edu.files;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

record BrowseImpact(Set<Path> folders, Set<Path> entries) {
    static BrowseImpact from(FileOperationTask.Kind kind, FileChanges.Outcome outcome) {
        Set<Path> folders = new HashSet<>();
        Set<Path> entries = new HashSet<>();
        for (var item : outcome.items()) {
            if (item.state() == FileChanges.State.APPLIED) {
                item.change().folders().forEach(path -> add(folders, path));
                var receipt = item.receipt();
                if (receipt != null) {
                    add(entries, receipt.target());
                    if (kind != FileOperationTask.Kind.COPY) { add(entries, receipt.source()); }
                }
            } else if (item.state() == FileChanges.State.UNKNOWN) {
                item.change().folders().forEach(path -> add(folders, path));
                add(entries, item.change().path());
                if (item.change().evidence() != null) { add(entries, item.change().evidence().target()); }
                if (item.error() instanceof LocalFiles.UncertainWrite unknown) {
                    for (var observation : unknown.observations()) {
                        add(entries, observation.path());
                        add(folders, observation.path().getParent());
                    }
                }
            }
        }
        for (var issue : outcome.issues()) {
            add(entries, issue.path());
            add(folders, issue.path().getParent());
        }
        return new BrowseImpact(Set.copyOf(folders), Set.copyOf(entries));
    }

    static BrowseImpact ofParent(Path entry) {
        return new BrowseImpact(Set.of(normalize(entry.getParent())), Set.of(normalize(entry)));
    }

    boolean affectsDirectory(Path path, boolean recursive) {
        Path directory = normalize(path);
        return folders.contains(directory) || entries.stream().anyMatch(directory::startsWith)
                || (recursive && folders.stream().anyMatch(folder -> folder.startsWith(directory)));
    }

    boolean affectsEntry(Path path) {
        Path entry = normalize(path);
        return entries.stream().anyMatch(entry::startsWith);
    }

    private static void add(Set<Path> paths, Path path) {
        if (path != null) { paths.add(normalize(path)); }
    }

    private static Path normalize(Path path) { return path.toAbsolutePath().normalize(); }
}
