package com.example;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.instrumentation.annotations.WithSpan;

public class App {

    @WithSpan("application.main")
    public static void main(String[] args) {
        Span span = Span.current();

        try {
            span.setAttribute("application", "k8s-job-creator");
            span.setAttribute("version", "1.0-SNAPSHOT");

            System.out.println("🚀 Starting Kubernetes Job Creator with OpenTelemetry");
            System.out.println("=====================================================");
            System.out.println("Main container: busybox:latest");
            System.out.println("Sidecar: OpenTelemetry Collector");
            System.out.println("=====================================================");

            JobCreator jobCreator = new JobCreator();

            if (!jobCreator.isKubernetesAvailable()) {
                System.err.println("❌ Kubernetes is not available.");
                System.err.println("💡 Make sure: 1) Kubernetes is enabled in Docker Desktop");
                System.err.println("              2) ConfigMap is created: kubectl apply -f otel-sidecar-config.yaml");
                System.exit(1);
            }

            System.out.println("✅ Kubernetes cluster is available");

            // Create a job with busybox main container and OTEL sidecar
            jobCreator.createJobWithSidecar();

            span.setStatus(StatusCode.OK);
            System.out.println("=====================================================");
            System.out.println("🎉 Application completed successfully!");
            System.out.println("📊 Check metrics with: kubectl logs <pod-name> -c otel-sidecar");

        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Application failed: " + e.getMessage());
            System.err.println("💥 Application failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}