# Kubernetes Job Monitor with OpenTelemetry

A Java application that demonstrates multiple patterns for monitoring Kubernetes Jobs using OpenTelemetry.

## 🎯 Features

- **Sidecar Pattern**: Real-time process monitoring with OpenTelemetry sidecar
- **File-based Pattern**: Scalable metrics collection using file-based approach
- **Dual Implementation**: Compare both patterns side-by-side
- **OpenTelemetry Integration**: Distributed tracing with `@WithSpan` annotations
- **Kubernetes Java Client**: Programmatic job creation and management

## 📊 Monitoring Patterns

### 1. Sidecar Pattern
- One OpenTelemetry sidecar per application container
- Real-time process metrics collection
- Direct container-level monitoring

### 2. File-based Pattern
- Application writes metrics to files
- Central OpenTelemetry agent scrapes files
- Scalable for high-volume workloads
- No sidecar resource overhead

## 🛠 Technologies

- Java 11+
- Maven
- Kubernetes Java Client
- OpenTelemetry SDK
- OpenTelemetry Collector
- Docker & Kubernetes




## Handy-Commands and what it does

# ===========================
# monitor-file-metrics.ps1
# Script to monitor file-based metrics pattern
# ===========================

Write-Host "🔍 FILE-BASED METRICS MONITORING" -ForegroundColor Green

# 1. Check OTEL Agent status
Write-Host "`n=== OTEL Agent Status ===" -ForegroundColor Yellow
kubectl get pods -l name=otel-agent

# 2. Get latest Busybox file metrics pod
Write-Host "`n=== Latest Busybox File Metrics Pod ===" -ForegroundColor Yellow
$POD_NAME = kubectl get pods -l app=busybox-file-metrics --sort-by=.metadata.creationTimestamp -o jsonpath="{.items[-1].metadata.name}" 2>$null
if ($POD_NAME) {
    Write-Host "📦 Using pod: $POD_NAME" -ForegroundColor Cyan
} else {
    Write-Host "❌ No Busybox file metrics pods found" -ForegroundColor Red
    exit 1
}

# 3. Logs from Busybox container (raw metrics output)
Write-Host "`n=== Busybox Container Logs (Raw Metrics) ===" -ForegroundColor Yellow
kubectl logs $POD_NAME

# 4. Check OTEL Agent logs for collected metrics
Write-Host "`n=== OTEL Agent Logs (Collected Metrics) ===" -ForegroundColor Yellow
kubectl logs -l name=otel-agent --tail=50 | Select-String -Pattern "process_|SYSTEM METRICS" -Context 2,2

# 5. Show all system metrics from agent
Write-Host "`n=== All System Metrics in Agent Logs ===" -ForegroundColor Yellow
kubectl logs -l name=otel-agent --tail=100 | Select-String -Pattern "process_cpu_time_seconds|process_memory_usage_bytes|process_memory_virtual_bytes|process_disk_io_bytes_total"

# 6. Check job status
Write-Host "`n=== File Metrics Jobs Status ===" -ForegroundColor Yellow
kubectl get jobs -l app=busybox-file-metrics

# 7. Pod details for debugging
Write-Host "`n=== Pod Details ===" -ForegroundColor Yellow
kubectl get pods -l app=busybox-file-metrics


# ===========================
# check-otel-agent.ps1
# Script to monitor OTEL Agent status and configuration
# ===========================

Write-Host "🔧 OTEL AGENT STATUS & CONFIGURATION" -ForegroundColor Green

# 1. Agent pod status
Write-Host "`n=== OTEL Agent Pods ===" -ForegroundColor Yellow
kubectl get pods -l name=otel-agent -o wide

# 2. Agent logs (recent)
Write-Host "`n=== Recent Agent Logs ===" -ForegroundColor Yellow
kubectl logs -l name=otel-agent --tail=20

# 3. Check agent configuration
Write-Host "`n=== OTEL Agent Configuration ===" -ForegroundColor Yellow
kubectl get configmap otel-agent-config -o yaml | Select-String -Pattern "verbosity|filelog|exporters" -Context 2,2

# 4. Agent pod details
Write-Host "`n=== Agent Pod Details ===" -ForegroundColor Yellow
kubectl describe pod -l name=otel-agent | Select-String -Pattern "Image:|State:|Ready:"

# 5. Check if agent is detecting files
Write-Host "`n=== File Detection Status ===" -ForegroundColor Yellow
kubectl logs -l name=otel-agent --tail=50 | Select-String -Pattern "Started watching file|no files match"


# ===========================
# cleanup-metrics.ps1
# Script to clean up all metrics resources
# ===========================

Write-Host "🧹 CLEANING UP METRICS RESOURCES" -ForegroundColor Green

# 1. Delete all file metrics jobs
Write-Host "`n=== Deleting File Metrics Jobs ===" -ForegroundColor Yellow
kubectl delete jobs -l app=busybox-file-metrics --ignore-not-found=true
Write-Host "✅ Jobs deleted" -ForegroundColor Green

# 2. Delete OTEL Agent
Write-Host "`n=== Deleting OTEL Agent ===" -ForegroundColor Yellow
kubectl delete daemonset otel-agent --ignore-not-found=true
Write-Host "✅ Agent deleted" -ForegroundColor Green

# 3. Delete configuration
Write-Host "`n=== Deleting Configuration ===" -ForegroundColor Yellow
kubectl delete configmap otel-agent-config --ignore-not-found=true
Write-Host "✅ Configuration deleted" -ForegroundColor Green

# 4. Verify cleanup
Write-Host "`n=== Verification ===" -ForegroundColor Yellow
Write-Host "Jobs:" -NoNewline
kubectl get jobs -l app=busybox-file-metrics 2>$null
Write-Host "Pods:" -NoNewline  
kubectl get pods -l app=busybox-file-metrics 2>$null
Write-Host "Agent:" -NoNewline
kubectl get pods -l name=otel-agent 2>$null

Write-Host "`n🎉 Cleanup completed!" -ForegroundColor Green

# ===========================
# deploy-file-metrics.ps1
# Script to deploy file-based metrics setup
# ===========================

Write-Host "🚀 DEPLOYING FILE-BASED METRICS" -ForegroundColor Green

# 1. Create OTEL Agent configuration
Write-Host "`n=== Creating OTEL Agent Config ===" -ForegroundColor Yellow
kubectl apply -f - << "EOF"
apiVersion: v1
kind: ConfigMap
metadata:
  name: otel-agent-config
  namespace: default
data:
  otel-config.yaml: |
    receivers:
      filelog:
        include: 
          - /var/log/containers/*busybox-file-metrics*.log
        start_at: beginning
    exporters:
      logging:
        verbosity: detailed
    service:
      pipelines:
        logs:
          receivers: [filelog]
          exporters: [logging]
EOF
Write-Host "✅ ConfigMap created" -ForegroundColor Green

# 2. Deploy OTEL Agent
Write-Host "`n=== Deploying OTEL Agent ===" -ForegroundColor Yellow
kubectl apply -f - << "EOF"
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: otel-agent
  namespace: default
spec:
  selector:
    matchLabels:
      name: otel-agent
  template:
    metadata:
      labels:
        name: otel-agent
    spec:
      hostNetwork: true
      containers:
      - name: otel-agent
        image: otel/opentelemetry-collector-contrib:0.80.0
        args:
          - "--config=/etc/otel-config.yaml"
        volumeMounts:
        - name: otel-agent-config
          mountPath: /etc/otel-config.yaml
          subPath: otel-config.yaml
        - name: var-log
          mountPath: /var/log
          readOnly: true
      volumes:
      - name: otel-agent-config
        configMap:
          name: otel-agent-config
      - name: var-log
        hostPath:
          path: /var/log
      tolerations:
      - effect: NoSchedule
        operator: Exists
EOF
Write-Host "✅ Agent deployed" -ForegroundColor Green

# 3. Wait for the agent to be ready
Write-Host "`n=== Waiting for agent to be ready ===" -ForegroundColor Yellow
Start-Sleep -Seconds 10
kubectl get pods -l name=otel-agent

Write-Host "`n🎉 Deployment completed! Run Java app to create metrics job." -ForegroundColor Green


# ===========================
# quick-status.ps1
# Quick status check of all components
# ===========================

Write-Host "📊 QUICK STATUS CHECK" -ForegroundColor Green

# 1. OTEL Agent status
Write-Host "`n🤖 OTEL Agent:" -ForegroundColor Cyan -NoNewline
kubectl get pods -l name=otel-agent --no-headers 2>$null | ForEach-Object { 
    $status = ($_ -split '\s+')[2]
    Write-Host " $status" -ForegroundColor $(if ($status -eq "Running") { "Green" } else { "Red" })
}

# 2. File metrics jobs
Write-Host "`n📦 File Metrics Jobs:" -ForegroundColor Cyan -NoNewline
$jobs = kubectl get jobs -l app=busybox-file-metrics --no-headers 2>$null
if ($jobs) {
    $completions = ($jobs -split '\s+')[1]
    Write-Host " $completions" -ForegroundColor Green
} else {
    Write-Host " No jobs" -ForegroundColor Yellow
}

# 3. Recent metrics in agent logs
Write-Host "`n📈 Recent Metrics:" -ForegroundColor Cyan
kubectl logs -l name=otel-agent --tail=30 2>$null | Select-String -Pattern "process_cpu_time_seconds|process_memory_usage_bytes" -CaseSensitive | Select-Object -Last 3

# 4. Configuration status
Write-Host "`n⚙️  Configuration:" -ForegroundColor Cyan -NoNewline
$config = kubectl get configmap otel-agent-config --no-headers 2>$null
if ($config) {
    Write-Host " Present" -ForegroundColor Green
} else {
    Write-Host " Missing" -ForegroundColor Red
}

Write-Host "`n✅ Status check completed!" -ForegroundColor Green

## 🎯 Usage Examples

# Run monitoring script
.\monitor-file-metrics.ps1

# Quick status check
.\quick-status.ps1

# Deploy everything fresh
.\deploy-file-metrics.ps1

# Clean up when done
.\cleanup-metrics.ps1

## 🚀 Quick Start

1. **Clone the repository**
   ```bash
   git clone https://github.com/YOUR_USERNAME/k8s-job-monitor.git
   cd k8s-job-monitor
