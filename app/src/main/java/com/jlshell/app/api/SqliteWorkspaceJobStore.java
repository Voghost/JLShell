package com.jlshell.app.api;

import com.google.gson.Gson;
import com.jlshell.program.api.ProgramWorkspaceService.*;
import org.jdbi.v3.core.Jdbi;
import java.time.Instant;
import java.util.List;

/** Task state uses the host SQLite database. Command output and credentials are excluded. */
public final class SqliteWorkspaceJobStore implements WorkspaceJobStore {
    private final Jdbi jdbi;
    private final Gson gson = new Gson();
    public SqliteWorkspaceJobStore(Jdbi jdbi) { this.jdbi = jdbi; }
    public List<Job> load() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT job_json FROM agent_jobs ORDER BY updated_at DESC LIMIT 256")
                .mapTo(String.class).list().stream().map(json -> gson.fromJson(json, Job.class)).toList());
    }
    public void save(Job job) {
        jdbi.useTransaction(handle -> {
            handle.createUpdate("INSERT INTO agent_jobs (job_id, job_json, checkpoint_json, revision, updated_at) VALUES (:id,:json,'{}',0,:time) "
                    + "ON CONFLICT(job_id) DO UPDATE SET job_json=excluded.job_json, updated_at=excluded.updated_at")
                    .bind("id", job.jobId()).bind("json", gson.toJson(job)).bind("time", Instant.now().toString()).execute();
            handle.execute("DELETE FROM agent_jobs WHERE job_id NOT IN (SELECT job_id FROM agent_jobs ORDER BY updated_at DESC LIMIT 256)");
        });
    }
    public Checkpoint checkpoint(String id) {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT checkpoint_json, revision, updated_at FROM agent_jobs WHERE job_id=:id")
                .bind("id", id).map((row, ctx) -> new Checkpoint(id, row.getLong("revision"), row.getString("checkpoint_json"), row.getString("updated_at"), false))
                .findOne().orElseThrow(() -> new IllegalArgumentException("Job not found")));
    }
    public Checkpoint checkpoint(String id, long expected, String json) {
        String time = Instant.now().toString();
        int changed = jdbi.withHandle(handle -> handle.createUpdate("UPDATE agent_jobs SET checkpoint_json=:json, revision=revision+1, updated_at=:time WHERE job_id=:id AND revision=:revision")
                .bind("json", json).bind("time", time).bind("id", id).bind("revision", expected).execute());
        if (changed != 1) throw new IllegalStateException("Checkpoint revision conflict or missing job");
        return new Checkpoint(id, expected + 1, json, time, false);
    }
}
