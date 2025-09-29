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
import java.util.Collections;
import java.util.Map;
import java.io.IOException;

public class JobCreator {

    private final BatchV1Api batchApi;

    public JobCreator() {
        this.batchApi = createKubernetesClient();
    }

    private BatchV1Api createKubernetesClient() {
        try {
            ApiClient client = Config.defaultClient();
            Configuration.setDefaultApiClient(client);

            client.setConnectTimeout(30000);
            client.setReadTimeout(30000);
            client.setWriteTimeout(30000);

            System.out.println("Kubernetes client configured successfully");
            return new BatchV1Api(client);

        } catch (IOException e) {
            System.err.println("Failed to configure Kubernetes client: " + e.getMessage());
            throw new RuntimeException("Kubernetes client configuration failed", e);
        }
    }

    @WithSpan("job.creator.createJobWithSidecar")
    public void createJobWithSidecar() {
        Span span = Span.current();

        try {
            span.setAttribute("job.name", "busybox-with-metrics-sidecar");
            span.setAttribute("job.namespace", "default");

            // Test connection first
            testKubernetesConnection();

            // Create the Job object with busybox main container
            V1Job job = createJobObject();

            span.addEvent("Creating Kubernetes Job");
            System.out.println("Creating job with busybox main container...");

            // Create the job in Kubernetes
            V1Job createdJob = batchApi.createNamespacedJob("default", job, null, null, null, null);

            span.addEvent("Job created successfully");
            span.setAttribute("job.uid", createdJob.getMetadata().getUid());
            span.setAttribute("job.name", createdJob.getMetadata().getName());

            System.out.println("✅ Job created successfully: " + createdJob.getMetadata().getName());
            System.out.println("📊 Main container: busybox:latest");
            System.out.println("📈 Monitoring sidecar: otel/opentelemetry-collector-contrib:0.80.0");

            // Monitor job completion
            monitorJobCompletion(createdJob.getMetadata().getName());

            span.setStatus(StatusCode.OK);

        } catch (ApiException e) {
            span.recordException(e);
            String errorMsg = "Failed to create job: " + e.getMessage();
            span.setStatus(StatusCode.ERROR, errorMsg);
            System.err.println("❌ Error creating job: " + e.getMessage());
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

    @WithSpan("job.creator.testConnection")
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

    @WithSpan("job.creator.createJobObject")
    private V1Job createJobObject() {
        Span span = Span.current();

        try {
            // MAIN CONTAINER: Busybox - FIXED SHELL SCRIPT
            V1Container mainContainer = new V1Container()
                    .name("busybox-main")
                    .image("busybox:latest")
                    .command(Arrays.asList("sh", "-c"))
                    .args(Arrays.asList(
                            "echo '🚀 Starting Busybox workload'; " +
                                    "echo '📈 Monitoring with OpenTelemetry'; " +
                                    "counter=0; " +
                                    "while [ $counter -lt 35 ]; do " +
                                    "  echo 'Processing iteration: ' $counter; " +
                                    "  sleep 5; " +
                                    "  counter=$((counter + 1)); " +
                                    "done; " +
                                    "echo '✅ Workload completed successfully'"
                    ))
                    .resources(new V1ResourceRequirements()
                            .requests(Map.of("cpu", new Quantity("100m"), "memory", new Quantity("64Mi")))
                            .limits(Map.of("cpu", new Quantity("200m"), "memory", new Quantity("128Mi"))));

            // SIDECAR CONTAINER: OpenTelemetry - SIMPLIFIED
            V1Container sidecarContainer = new V1Container()
                    .name("otel-sidecar")
                    .image("otel/opentelemetry-collector-contrib:0.80.0")
                    .command(Arrays.asList("/otelcol-contrib"))
                    .args(Arrays.asList("--config=/etc/otel-config.yaml"))
                    .volumeMounts(Arrays.asList(
                            new V1VolumeMount()
                                    .name("otel-config")
                                    .mountPath("/etc/otel-config.yaml")
                                    .subPath("otel-config.yaml")
                    ))
                    .resources(new V1ResourceRequirements()
                            .requests(Map.of("cpu", new Quantity("100m"), "memory", new Quantity("128Mi")))
                            .limits(Map.of("cpu", new Quantity("200m"), "memory", new Quantity("256Mi"))));

            // ConfigMap volume
            V1Volume configVolume = new V1Volume()
                    .name("otel-config")
                    .configMap(new V1ConfigMapVolumeSource()
                            .name("otel-sidecar-config")
                            .defaultMode(420));

            // Pod template
            V1PodTemplateSpec templateSpec = new V1PodTemplateSpec()
                    .metadata(new V1ObjectMeta()
                            .labels(Map.of("app", "busybox-with-metrics")))
                    .spec(new V1PodSpec()
                            .containers(Arrays.asList(mainContainer, sidecarContainer))
                            .volumes(Collections.singletonList(configVolume))
                            .shareProcessNamespace(true)
                            .restartPolicy("Never"));

            // Job specification - INCREASE BACKOFF LIMIT
            V1JobSpec jobSpec = new V1JobSpec()
                    .template(templateSpec)
                    .backoffLimit(1)  // Allow 1 retry instead of 0
                    .ttlSecondsAfterFinished(300);

            // Job metadata
            V1ObjectMeta metadata = new V1ObjectMeta()
                    .name("busybox-monitored-job-" + System.currentTimeMillis())
                    .labels(Map.of(
                            "app", "busybox-monitored",
                            "created-by", "java-app"
                    ));

            return new V1Job()
                    .apiVersion("batch/v1")
                    .kind("Job")
                    .metadata(metadata)
                    .spec(jobSpec);

        } catch (Exception e) {
            span.recordException(e);
            throw new RuntimeException("Failed to create job object", e);
        }
    }

    @WithSpan("job.creator.monitorJobCompletion")
    private void monitorJobCompletion(String jobName) {
        Span span = Span.current();
        span.setAttribute("job.name", jobName);

        try {
            System.out.println("🔍 Monitoring job: " + jobName);
            System.out.println("📊 OpenTelemetry sidecar is collecting metrics from busybox container...");

            for (int i = 0; i < 15; i++) { // Increased timeout for slower startup
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
                            span.addEvent("Job completed successfully");
                            span.setStatus(StatusCode.OK);
                            System.out.println("✅ Job completed successfully");
                            System.out.println("📈 Check sidecar logs for collected metrics: kubectl logs <pod-name> -c otel-sidecar");
                            return;
                        }

                        if (failed != null && failed > 0) {
                            span.addEvent("Job failed");
                            span.setStatus(StatusCode.ERROR, "Job failed");
                            System.out.println("❌ Job failed - checking logs for details...");

                            // Try to get failure details immediately
                            try {
                                String podName = kubectlGetPodName(jobName);
                                System.out.println("Pod name: " + podName);
                                System.out.println("Check logs with: kubectl logs " + podName + " -c busybox-main");
                            } catch (Exception logEx) {
                                System.out.println("Could not retrieve pod logs automatically");
                            }
                            return;
                        }
                    }

                    System.out.println("⏳ Waiting for job completion... (" + (i + 1) + "/15)");
                    Thread.sleep(5000);

                } catch (ApiException e) {
                    System.err.println("⚠️  Error monitoring job: " + e.getMessage());
                    span.recordException(e);
                }
            }

            span.addEvent("Job monitoring timeout");
            span.setStatus(StatusCode.ERROR, "Job monitoring timeout");
            System.out.println("⏰ Job monitoring timeout reached");
        } catch (InterruptedException e) {
            span.recordException(e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Monitoring interrupted", e);
        }
    }

    // Helper method to get pod name
    private String kubectlGetPodName(String jobName) throws ApiException {
        // This is a simplified approach - in production you'd use the Kubernetes API
        // For now, we'll just return a message
        return "Use: kubectl get pods -l app=busybox-with-metrics";
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