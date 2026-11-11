package com.jlshell.app.api;

import com.jlshell.program.api.ProgramWorkspaceService.*;
import java.time.Instant;
import java.util.*;

public interface WorkspaceJobStore {
    List<Job> load();
    void save(Job job);
    Checkpoint checkpoint(String jobId);
    Checkpoint checkpoint(String jobId, long expectedRevision, String json);

    static WorkspaceJobStore memory() {
        return new WorkspaceJobStore() {
            private final Map<String, Job> jobs = new LinkedHashMap<>();
            private final Map<String, Checkpoint> checkpoints = new HashMap<>();
            public synchronized List<Job> load() { return List.copyOf(jobs.values()); }
            public synchronized void save(Job job) { jobs.put(job.jobId(), job); }
            public synchronized Checkpoint checkpoint(String id) {
                if (!jobs.containsKey(id)) throw new IllegalArgumentException("Job not found");
                return checkpoints.getOrDefault(id, new Checkpoint(id, 0, "{}", null, false));
            }
            public synchronized Checkpoint checkpoint(String id, long expected, String json) {
                Checkpoint previous = checkpoint(id);
                if (previous.revision() != expected) throw new IllegalStateException("Checkpoint revision conflict");
                var updated = new Checkpoint(id, expected + 1, json, Instant.now().toString(), false);
                checkpoints.put(id, updated);
                return updated;
            }
        };
    }
}
