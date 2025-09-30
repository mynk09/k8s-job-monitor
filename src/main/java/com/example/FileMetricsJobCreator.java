package com.example;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.models.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.kubernetes.client.util.Config;

import java.util.Arrays;
import java.util.Map;
import java.io.IOException;
import java.util.Objects;

public class FileMetricsJobCreator {

    private final BatchV1Api batchApi;

    public FileMetricsJobCreator() {
        this.batchApi = createKubernetesClient();
    }

    private BatchV1Api createKubernetesClient() {
        try {
            ApiClient client = Config.defaultClient();
            Configuration.setDefaultApiClient(client);

            client.setConnectTimeout(30000);
            client.setReadTimeout(30000);
            client.setWriteTimeout(30000);

            System.out.println("Kubernetes client configured successfully for file metrics");
            return new BatchV1Api(client);

        } catch (IOException e) {
            System.err.println("Failed to configure Kubernetes client: " + e.getMessage());
            throw new RuntimeException("Kubernetes client configuration failed", e);
        }
    }

    @WithSpan("filemetrics.jobcreator.createJobWithFileMetrics")
    public void createJobWithFileMetrics() {
        Span span = Span.current();

        try {
            span.setAttribute("job.type", "file-metrics");
            span.setAttribute("job.namespace", "default");

            // Test connection first
            testKubernetesConnection();

            // Create the Job object with file-based metrics
            V1Job job = createFileMetricsJobObject();

            span.addEvent("Creating Kubernetes Job with file metrics");
            System.out.println("Creating job with file-based metrics pattern...");

            // Create the job in Kubernetes
            V1Job createdJob = batchApi.createNamespacedJob("default", job, null, null, null, null);

            span.addEvent("File metrics job created successfully");
            span.setAttribute("job.uid", Objects.requireNonNull(createdJob.getMetadata().getUid()));
            span.setAttribute("job.name", Objects.requireNonNull(createdJob.getMetadata().getName()));

            System.out.println("✅ File metrics job created successfully: " + createdJob.getMetadata().getName());
            System.out.println("📊 Metrics will be written to /tmp/metrics.prom");
            System.out.println("🔍 Agent will automatically scrape and process metrics");

            // Monitor job completion
            monitorJobCompletion(createdJob.getMetadata().getName());

            span.setStatus(StatusCode.OK);

        } catch (ApiException e) {
            span.recordException(e);
            String errorMsg = "Failed to create file metrics job: " + e.getMessage();
            span.setStatus(StatusCode.ERROR, errorMsg);
            System.err.println("❌ Error creating file metrics job: " + e.getMessage());
            if (e.getResponseBody() != null) {
                System.err.println("Response: " + e.getResponseBody());
            }
            throw new RuntimeException(errorMsg, e);
        } catch (Exception e) {
            span.recordException(e);
            String errorMsg = "Unexpected error: " + e.getMessage();
            span.setStatus(StatusCode.ERROR, errorMsg);
            System.err.println("❌ Unexpected error: " + e.getMessage());
            throw new RuntimeException(errorMsg, e);
        }
    }

    @WithSpan("filemetrics.jobcreator.testConnection")
    private void testKubernetesConnection() throws ApiException {
        Span span = Span.current();

        try {
            System.out.println("Testing connection to Kubernetes API...");
            batchApi.getAPIResources();
            span.addEvent("Kubernetes API connection successful");
            System.out.println("✅ Connected to Kubernetes API successfully");
        } catch (ApiException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Failed to connect to Kubernetes API");
            System.err.println("❌ Cannot connect to Kubernetes API");
            throw e;
        }
    }

    @WithSpan("filemetrics.jobcreator.createFileMetricsJobObject")
    private V1Job createFileMetricsJobObject() {
        Span span = Span.current();

        try {
            // MAIN CONTAINER: Busybox with file-based metrics
            V1Container mainContainer = new V1Container()
                    .name("busybox-file-metrics")
                    .image("busybox:latest")
                    .command(Arrays.asList("sh", "-c"))
                    .args(Arrays.asList(
                            "echo 'Starting Busybox metrics workload'; " +
                                    "counter=0; " +
                                    "while [ $counter -lt 5 ]; do " +
                                    "  echo '=== METRICS ==='; " +
                                    "  echo 'workload_progress ' $counter; " +
                                    "  echo 'items_processed_total ' $((counter * 10)); " +
                                    "  echo 'errors_encountered_total ' $((counter / 2)); " +
                                    "  sleep 2; " +
                                    "  counter=$((counter + 1)); " +
                                    "done; " +
                                    "echo '=== FINAL METRICS ==='; " +
                                    "echo 'workload_progress ' $counter; " +
                                    "echo 'workload_duration_seconds 10'; " +
                                    "echo 'workload_status 1'; " +
                                    "echo 'Workload completed'; " +
                                    "exit 0"
                    ));

            // EmptyDir volume for metrics files
            V1Volume metricsVolume = new V1Volume()
                    .name("metrics-volume")
                    .emptyDir(new V1EmptyDirVolumeSource());

            // Pod template
            V1PodTemplateSpec templateSpec = new V1PodTemplateSpec()
                    .metadata(new V1ObjectMeta()
                            .labels(Map.of("app", "busybox-file-metrics")))
                    .spec(new V1PodSpec()
                            .containers(Arrays.asList(mainContainer))
                            .volumes(Arrays.asList(metricsVolume))
                            .restartPolicy("Never"));

            // Job specification
            V1JobSpec jobSpec = new V1JobSpec()
                    .template(templateSpec)
                    .backoffLimit(0)
                    .ttlSecondsAfterFinished(300);

            // Job metadata
            V1ObjectMeta metadata = new V1ObjectMeta()
                    .name("busybox-file-metrics-" + System.currentTimeMillis())
                    .labels(Map.of(
                            "app", "busybox-file-metrics",
                            "metrics-type", "file-based",
                            "created-by", "java-app"
                    ));

            span.addEvent("File metrics job object created");
            span.setAttribute("metrics.strategy", "file-based");
            span.setAttribute("job.containers.count", 1);

            return new V1Job()
                    .apiVersion("batch/v1")
                    .kind("Job")
                    .metadata(metadata)
                    .spec(jobSpec);

        } catch (Exception e) {
            span.recordException(e);
            throw new RuntimeException("Failed to create file metrics job object", e);
        }
    }

    @WithSpan("filemetrics.jobcreator.monitorJobCompletion")
    private void monitorJobCompletion(String jobName) {
        Span span = Span.current();
        span.setAttribute("job.name", jobName);

        try {
            System.out.println("🔍 Monitoring file metrics job: " + jobName);
            System.out.println("📊 Metrics are being written to /tmp/metrics.prom");

            for (int i = 0; i < 20; i++) {
                try {
                    V1Job job = batchApi.readNamespacedJob(jobName, "default", null);

                    V1JobStatus status = job.getStatus();
                    if (status != null) {
                        Integer succeeded = status.getSucceeded();
                        Integer failed = status.getFailed();

                        span.setAttribute("monitor.iteration", i);
                        span.setAttribute("job.succeeded", succeeded != null ? succeeded : 0);
                        span.setAttribute("job.failed", failed != null ? failed : 0);

                        if (succeeded != null && succeeded > 0) {
                            span.addEvent("File metrics job completed successfully");
                            span.setStatus(StatusCode.OK);
                            System.out.println("✅ File metrics job completed successfully");
                            System.out.println("📈 Check agent logs for scraped metrics");
                            return;
                        }

                        if (failed != null && failed > 0) {
                            span.addEvent("File metrics job failed");
                            span.setStatus(StatusCode.ERROR, "Job failed");
                            System.out.println("❌ File metrics job failed");
                            return;
                        }
                    }

                    System.out.println("⏳ Waiting for file metrics job completion... (" + (i + 1) + "/20)");
                    Thread.sleep(5000);

                } catch (ApiException e) {
                    System.err.println("⚠️  Error monitoring job: " + e.getMessage());
                    span.recordException(e);
                }
            }

            span.addEvent("File metrics job monitoring timeout");
            span.setStatus(StatusCode.ERROR, "Job monitoring timeout");
            System.out.println("⏰ File metrics job monitoring timeout reached");
        } catch (InterruptedException e) {
            span.recordException(e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Monitoring interrupted", e);
        }
    }

    public boolean isKubernetesAvailable() {
        try {
            batchApi.getAPIResources();
            return true;
        } catch (ApiException e) {
            return false;
        }
    }
}