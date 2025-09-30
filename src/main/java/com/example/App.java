package com.example;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.instrumentation.annotations.WithSpan;

public class App {

    @WithSpan("application.main")
    public static void main(String[] args) {
        Span span = Span.current();

        try {
            span.setAttribute("application", "k8s-job-monitor");
            span.setAttribute("version", "1.0-SNAPSHOT");

            System.out.println("🚀 Starting Kubernetes Job Monitor - Multiple Patterns");
            System.out.println("=====================================================");

            // Test connection first with either creator
            JobCreator basicCreator = new JobCreator();
            if (!basicCreator.isKubernetesAvailable()) {
                System.err.println("❌ Kubernetes is not available.");
                System.exit(1);
            }

            System.out.println("✅ Kubernetes cluster is available");

            // Option 1: Run Sidecar Pattern (Original)
            System.out.println("\n1️⃣  Testing SIDECAR Pattern...");
            System.out.println("=====================================================");
            JobCreator sidecarCreator = new JobCreator();
            sidecarCreator.createJobWithSidecar();

            // Wait a bit between patterns
            Thread.sleep(5000);

            // Option 2: Run File-Based Pattern (New)
            System.out.println("\n2️⃣  Testing FILE-BASED Pattern...");
            System.out.println("=====================================================");
            FileMetricsJobCreator fileMetricsCreator = new FileMetricsJobCreator();
            fileMetricsCreator.createJobWithFileMetrics();

            System.out.println("=====================================================");
            System.out.println("🎉 Both patterns completed successfully!");
            System.out.println("📊 Comparison:");
            System.out.println("   - Sidecar: Real-time process monitoring");
            System.out.println("   - File-based: Application metrics + Agent scalability");

            span.setStatus(StatusCode.OK);

        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Application failed: " + e.getMessage());
            System.err.println("💥 Application failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}