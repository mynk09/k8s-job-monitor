package com.example;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public class DatabaseMetricsJobCreator {

    private final BatchV1Api batchApi;

    public DatabaseMetricsJobCreator() {
        this.batchApi = new FileMetricsJobCreator().createKubernetesClient();
    }

    public void createDatabaseMetricsJob() {
        Span span = Span.current();

        try {
            System.out.println("Creating database metrics job...");

            // Create the Job object
            V1Job job = createDatabaseMetricsJobObject();

            // Create the job in Kubernetes
            V1Job createdJob = batchApi.createNamespacedJob("default", job, null, null, null, null);

            System.out.println("✅ Database metrics job created: " + createdJob.getMetadata().getName());

            // Monitor job completion
            monitorJobCompletion(createdJob.getMetadata().getName());

        } catch (Exception e) {
            System.err.println("❌ Error creating database metrics job: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private V1Job createDatabaseMetricsJobObject() {
        // ULTRA SIMPLE - guaranteed to work
        V1Container dbMetricsContainer = new V1Container()
                .name("database-metrics-generator")
                .image("busybox:latest")
                .command(Arrays.asList("sh", "-c"))
                .args(Arrays.asList(
                        "echo 'Starting database metrics'; " +
                                "echo 'db_queries_total 10'; " +
                                "echo 'db_connections_active 5'; " +
                                "echo 'db_response_time_seconds 0.25'; " +
                                "echo 'Database metrics done'; " +
                                "exit 0"
                ))
                .resources(new V1ResourceRequirements()
                        .requests(Map.of("cpu", new Quantity("100m"), "memory", new Quantity("64Mi")))
                        .limits(Map.of("cpu", new Quantity("200m"), "memory", new Quantity("128Mi"))));

        // Pod template
        V1PodTemplateSpec templateSpec = new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta()
                        .labels(Map.of("app", "database-metrics")))
                .spec(new V1PodSpec()
                        .containers(Arrays.asList(dbMetricsContainer))
                        .restartPolicy("Never"));

        // Job specification
        V1JobSpec jobSpec = new V1JobSpec()
                .template(templateSpec)
                .backoffLimit(0)
                .ttlSecondsAfterFinished(300);

        // Job metadata
        V1ObjectMeta metadata = new V1ObjectMeta()
                .name("database-metrics-" + System.currentTimeMillis())
                .labels(Map.of(
                        "app", "database-metrics",
                        "metrics-type", "database"
                ));

        return new V1Job()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(metadata)
                .spec(jobSpec);
    }

    private void monitorJobCompletion(String jobName) {
        try {
            System.out.println("Monitoring job: " + jobName);

            for (int i = 0; i < 10; i++) {
                try {
                    V1Job job = batchApi.readNamespacedJob(jobName, "default", null);
                    V1JobStatus status = job.getStatus();

                    if (status != null) {
                        Integer succeeded = status.getSucceeded();
                        Integer failed = status.getFailed();

                        if (succeeded != null && succeeded > 0) {
                            System.out.println("✅ Database job completed");
                            return;
                        }

                        if (failed != null && failed > 0) {
                            System.out.println("❌ Database job failed");
                            return;
                        }
                    }

                    System.out.println("Waiting... (" + (i + 1) + "/10)");
                    Thread.sleep(3000);

                } catch (ApiException e) {
                    System.err.println("Error monitoring: " + e.getMessage());
                }
            }

            System.out.println("Timeout reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}