package org.example.yari;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.example.yari.executor.AiProvider;
import org.example.yari.executor.GeminiProvider;
import org.example.yari.model.*;
import org.example.yari.model.OutputSchemaTemplate.FieldSpec;
import org.example.yari.model.OutputSchemaTemplate.FieldType;
import org.example.yari.model.OutputSchemaTemplate.OutputFormat;
import org.example.yari.schema.SchemaRegistry;
import org.example.yari.schema.SchemaValidator;
import org.example.yari.standalone.*;
import org.example.yari.state.TaskStateMachine;
import org.example.yari.util.PromptValidator;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public final class MainRunner {

    private static final String ARTIFACTS_DIR = "artifacts";
    private static final String LOG_DIR = "logs";
    private static final int WEB_PORT = 8080;

    // Web Senkronizasyon Durumları
    private static final AtomicReference<String> currentStatus = new AtomicReference<>("IDLE");
    private static final AtomicReference<String> currentStageInfo = new AtomicReference<>("Sistem hazır. Web panelinden görevi başlatabilirsiniz.");
    private static volatile CompletableFuture<ConsoleCheckpointGate.Decision> pendingDecision = null;
    private static volatile CompletableFuture<TopicPayload> initialUserTopicFuture = new CompletableFuture<>();

    public record TopicPayload(String topic, String excludedContext) {}

    private MainRunner() {}

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║   YARI OTOMASYON — Cold Backlog Harvester (Web + CLI)     ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── 0. Web Sunucusunu Başlat ──────────────────────────────────────────
        try {
            startInternalWebServer(WEB_PORT, Path.of(ARTIFACTS_DIR));
            System.out.println("[WEB] Kontrol Masası yayında: http://localhost:" + WEB_PORT);
            System.out.println("[WEB] Tarayıcıdan giriş yapıp konuyu belirleyebilir veya konsoldan devam edebilirsiniz.");
        } catch (IOException e) {
            System.err.println("[WARN] Web sunucusu başlatılamadı: " + e.getMessage());
        }

        // ── 1. Build and validate configuration ──────────────────────────────
        RunnerConfig config;
        try {
            config = buildConfig(args);
            System.out.println("[CONFIG] " + config);
        } catch (IllegalArgumentException e) {
            System.err.println("[FATAL] Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // ── 2. Register output schemas ───────────────────────────────────────
        registerSchemas();
        System.out.println("[INIT] Registered " + SchemaRegistry.getInstance().size() + " output schemas.");

        // ── 3. Kullanıcı İsteğini Bekle (Web UI veya Varsayılan) ──────────────
        currentStatus.set("WAITING_INPUT");
        currentStageInfo.set("Web panelinden (localhost:8080) işlenecek yazılım gereksinimlerini girip 'Boru Hattını Başlat'a basın ya da konsoldan Enter'a basın.");

        System.out.println();
        System.out.println("[GİRDİ] http://localhost:" + WEB_PORT + " üzerinden brifi girin VEYA varsayılan brif ile başlamak için ENTER'a basın...");

        TopicPayload customPayload = null;
        ExecutorService inputWaitExecutor = Executors.newSingleThreadExecutor();
        Future<String> consoleInputFuture = inputWaitExecutor.submit(() -> {
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            return br.readLine();
        });

        try {
            while (!initialUserTopicFuture.isDone() && !consoleInputFuture.isDone()) {
                Thread.sleep(200);
            }
            if (initialUserTopicFuture.isDone()) {
                customPayload = initialUserTopicFuture.get();
                consoleInputFuture.cancel(true);
            } else {
                String line = consoleInputFuture.get();
                if (line != null && !line.isBlank()) {
                    customPayload = new TopicPayload(line.trim(), "");
                }
            }
        } catch (Exception e) {
            System.out.println("[INIT] Varsayılan mühendislik brifi ile başlanıyor.");
        } finally {
            inputWaitExecutor.shutdownNow();
        }

        List<StagedBacklogTask> backlog = buildTaskBacklog(customPayload);
        System.out.println("[INIT] Loaded " + backlog.size() + " tasks across "
                + backlog.stream().mapToInt(StagedBacklogTask::getStageNumber).distinct().count()
                + " stages.");

        // ── 4. Initialize infrastructure ─────────────────────────────────────
        GeminiProvider provider;
        try {
            provider = new GeminiProvider(config.getApiKey(), config.getApiEndpoint());
            System.out.println("[INIT] GeminiProvider initialized (model: " + config.getModelId() + ").");
        } catch (IllegalArgumentException e) {
            System.err.println("[FATAL] Provider initialization failed: " + e.getMessage());
            System.exit(1);
            return;
        }

        ArtifactWriter artifactWriter;
        try {
            artifactWriter = new ArtifactWriter(Path.of(ARTIFACTS_DIR));
            System.out.println("[INIT] Artifact output directory: " + Path.of(ARTIFACTS_DIR).toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[FATAL] Cannot create artifacts directory: " + e.getMessage());
            System.exit(1);
            return;
        }

        ConsoleCheckpointGate checkpointGate = new ConsoleCheckpointGate();
        PipelineExecutionLog executionLog = new PipelineExecutionLog(config);

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  PIPELINE EXECUTION STARTING");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        // ── 5. Execute pipeline stage by stage ───────────────────────────────
        currentStatus.set("RUNNING");
        int exitCode = executePipeline(backlog, provider, config, artifactWriter,
                checkpointGate, executionLog);

        // ── 6. Emit execution log ────────────────────────────────────────────
        Path logPath = Path.of(LOG_DIR, "execution_"
                + System.currentTimeMillis() + ".json");
        try {
            executionLog.writeToFile(logPath);
            System.out.println("[LOG] Execution log written to: " + logPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write execution log: " + e.getMessage());
        }

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  " + executionLog.toConsoleSummary());
        System.out.println("═══════════════════════════════════════════════════════════════");

        currentStatus.set(exitCode == 0 ? "COMPLETED" : "ABORTED");
        currentStageInfo.set(exitCode == 0
                ? "Boru hattı tamamlandı. Şartnameleri sol panelden inceleyebilirsiniz."
                : "Boru hattı durduruldu.");

        System.out.println("\n[WEB] Sunucu paneli açık tutuluyor: http://localhost:" + WEB_PORT);
        System.out.println("[INFO] Çıkmak için konsolda Ctrl + C yapabilirsiniz.");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  PIPELINE ORCHESTRATOR
    // ═════════════════════════════════════════════════════════════════════════

    private static int executePipeline(List<StagedBacklogTask> backlog,
                                       GeminiProvider provider,
                                       RunnerConfig config,
                                       ArtifactWriter artifactWriter,
                                       ConsoleCheckpointGate checkpointGate,
                                       PipelineExecutionLog executionLog) {

        Map<Integer, List<StagedBacklogTask>> stageMap = backlog.stream()
                .collect(Collectors.groupingBy(
                        StagedBacklogTask::getStageNumber,
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<Integer> stageNumbers = new ArrayList<>(stageMap.keySet());
        Collections.sort(stageNumbers);

        String previousStageOutput = "";

        for (int i = 0; i < stageNumbers.size(); i++) {
            int stageNum = stageNumbers.get(i);
            boolean isLastStage = (i == stageNumbers.size() - 1);

            boolean stageNeedsRetry = true;

            while (stageNeedsRetry) {
                stageNeedsRetry = false;

                currentStatus.set("RUNNING");
                currentStageInfo.set(String.format("Stage %d yürütülüyor (%d görev)...", stageNum, stageMap.get(stageNum).size()));

                System.out.println("───────────────────────────────────────────────────────────");
                System.out.printf("  ▶ STAGE %d  (%d tasks)%n", stageNum, stageMap.get(stageNum).size());
                System.out.println("───────────────────────────────────────────────────────────");

                List<StagedBacklogTask> currentStageTasks = stageMap.get(stageNum).stream()
                        .map(StagedBacklogTask::freshCopy)
                        .collect(Collectors.toCollection(ArrayList::new));
                stageMap.put(stageNum, new ArrayList<>(currentStageTasks));

                StringBuilder stageOutputAccumulator = new StringBuilder();
                boolean stageHasCheckpoint = false;
                boolean stageFailed = false;

                for (int taskIdx = 0; taskIdx < currentStageTasks.size(); taskIdx++) {
                    StagedBacklogTask staged = currentStageTasks.get(taskIdx);

                    if (!previousStageOutput.isEmpty()) {
                        staged = staged.withPreviousStageContext(previousStageOutput);

                        // Garanti Bağlam Enjeksiyonu: Şablon içinde literal {{previousStageContext}} varsa doğrudan çöz
                        BacklogTask innerTask = staged.getBacklogTask();
                        if (innerTask.getPromptTemplate() != null && innerTask.getPromptTemplate().contains("{{previousStageContext}}")) {
                            innerTask.setPromptTemplate(innerTask.getPromptTemplate().replace("{{previousStageContext}}", previousStageOutput));
                        }
                        currentStageTasks.set(taskIdx, staged);
                    }

                    if (staged.isRequiresCheckpointApproval()) {
                        stageHasCheckpoint = true;
                    }

                    BacklogTask task = staged.getBacklogTask();

                    currentStageInfo.set(String.format("Stage %d [%d/%d]: %s", stageNum, taskIdx + 1, currentStageTasks.size(), task.getName()));

                    System.out.printf("%n  [%d/%d] Task: %s (id=%s, priority=%s)%n",
                            taskIdx + 1, currentStageTasks.size(),
                            task.getName(), task.getId(), task.getPriority());

                    long taskStartMs = System.currentTimeMillis();
                    boolean taskSuccess = executeTask(task, provider, config);
                    long taskDurationMs = System.currentTimeMillis() - taskStartMs;

                    String artifactPath = null;
                    if (task.getState() == TaskState.COMPLETED) {
                        try {
                            artifactPath = artifactWriter.writeArtifact(task, stageNum);
                            System.out.println("    [ARTIFACT] Written: " + artifactPath);
                        } catch (IOException e) {
                            System.err.println("    [ERROR] Artifact write failed: " + e.getMessage());
                        }

                        String taskOutput = task.getValidatedOutput() != null
                                ? task.getValidatedOutput() : task.getRawOutput();
                        if (taskOutput != null) {
                            stageOutputAccumulator.append("--- Output from: ")
                                    .append(task.getName()).append(" ---\n")
                                    .append(taskOutput).append("\n\n");
                        }
                    } else {
                        System.out.printf("    [FAILED] %s%n", task.getErrorMessage());
                        stageFailed = true;
                    }

                    executionLog.addTaskEntry(task, stageNum, artifactPath, taskDurationMs);

                    if (taskIdx < currentStageTasks.size() - 1 && config.getInterTaskPauseMs() > 0) {
                        sleepQuietly(config.getInterTaskPauseMs());
                    }
                }

                String stageOutput = stageOutputAccumulator.toString();
                if (!stageOutput.isBlank()) {
                    previousStageOutput = stageOutput;
                }

                // Checkpoint Kapısı
                if (stageHasCheckpoint && !isLastStage) {
                    String summary = stageFailed
                            ? String.format("Stage %d had failures. Review artifacts in %s/",
                            stageNum, ARTIFACTS_DIR)
                            : String.format("Stage %d tamamlandı. %d görev işlendi. Stage %d'ye aktarılsın mı?",
                            stageNum, currentStageTasks.size(), stageNumbers.get(i + 1));

                    currentStatus.set("WAITING_APPROVAL");
                    currentStageInfo.set(summary);

                    pendingDecision = new CompletableFuture<>();

                    System.out.println();
                    System.out.println("───────────────────────────────────────────────────────────");
                    System.out.printf("  [CHECKPOINT] Stage %d bitti.%n  Web panelinden (localhost:%d) onaylayabilir veya terminale yazabilirsiniz.%n", stageNum, WEB_PORT);
                    System.out.print("  Proceed to next stage? (y: Approve / r: Retry / q: Abort): ");

                    ExecutorService checkpointPool = Executors.newSingleThreadExecutor();
                    checkpointPool.submit(() -> {
                        ConsoleCheckpointGate.Decision cliDecision = checkpointGate.requestApproval(stageNum, summary);
                        if (pendingDecision != null && !pendingDecision.isDone()) {
                            pendingDecision.complete(cliDecision);
                        }
                    });

                    ConsoleCheckpointGate.Decision decision;
                    try {
                        decision = pendingDecision.get();
                    } catch (Exception e) {
                        decision = ConsoleCheckpointGate.Decision.ABORT;
                    } finally {
                        checkpointPool.shutdownNow();
                        pendingDecision = null;
                    }

                    switch (decision) {
                        case APPROVE:
                            System.out.println("\n  [CHECKPOINT] Approved. Proceeding to next stage.");
                            break;
                        case RETRY:
                            stageNeedsRetry = true;
                            System.out.printf("%n  ↻ Retrying Stage %d...%n%n", stageNum);
                            break;
                        case ABORT:
                            System.out.println("\n  ✗ Pipeline aborted by user at Stage " + stageNum);
                            executionLog.finalize("ABORTED",
                                    "User aborted at checkpoint after Stage " + stageNum);
                            return 1;
                    }
                } else if (!isLastStage) {
                    System.out.printf("%n  ✓ Stage %d complete (no checkpoint required). Proceeding.%n", stageNum);
                }
            }
        }

        executionLog.finalize("COMPLETED", null);
        System.out.println("\n  ✓ All stages completed.");
        return 0;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TASK EXECUTOR
    // ═════════════════════════════════════════════════════════════════════════

    private static boolean executeTask(BacklogTask task, GeminiProvider provider, RunnerConfig config) {
        TaskStateMachine.transition(task, TaskState.VALIDATING, "Validating prompt template");
        System.out.println("    [STATE] QUEUED → VALIDATING");

        List<String> validationErrors = PromptValidator.validate(
                task.getPromptTemplate(), task.getPromptParameters());
        if (!validationErrors.isEmpty()) {
            task.setErrorMessage("Prompt validation failed: " + String.join("; ", validationErrors));
            TaskStateMachine.transition(task, TaskState.FAILED, task.getErrorMessage());
            System.out.println("    [STATE] VALIDATING → FAILED (prompt validation)");
            return false;
        }

        String resolvedPrompt = task.resolvePrompt();

        TaskStateMachine.transition(task, TaskState.RUNNING, "Prompt validated, executing");
        System.out.println("    [STATE] VALIDATING → RUNNING");

        String rawOutput = null;
        boolean apiSuccess = false;

        for (int attempt = 0; attempt <= config.getMaxRetriesPerTask(); attempt++) {
            try {
                if (attempt > 0) {
                    System.out.printf("    [RETRY] Attempt %d/%d%n", attempt, config.getMaxRetriesPerTask());
                }

                rawOutput = provider.execute(resolvedPrompt, config.getModelId());
                apiSuccess = true;
                break;

            } catch (AiProvider.RateLimitException e) {
                task.setRetryCount(attempt + 1);

                long computedBackoff = (long) (config.getInitialBackoffMs()
                        * Math.pow(config.getBackoffMultiplier(), attempt));
                long backoff = Math.min(computedBackoff, config.getMaxBackoffMs());
                backoff = Math.max(backoff, e.getRetryAfterMs());

                task.setBackoffUntilEpochMs(System.currentTimeMillis() + backoff);

                if (task.getState() == TaskState.RUNNING) {
                    TaskStateMachine.transition(task, TaskState.PAUSED_RATE_LIMIT,
                            String.format("Rate limited (attempt %d), backoff %d ms", attempt + 1, backoff));
                    System.out.printf("    [STATE] RUNNING → PAUSED_RATE_LIMIT (backoff %d ms)%n", backoff);
                }

                System.out.printf("    [RATE_LIMIT] %s — waiting %d ms before retry%n",
                        truncateMessage(e.getMessage()), backoff);

                sleepQuietly(backoff);

                if (task.getState() == TaskState.PAUSED_RATE_LIMIT) {
                    TaskStateMachine.transition(task, TaskState.RUNNING, "Resuming after rate-limit backoff");
                    System.out.println("    [STATE] PAUSED_RATE_LIMIT → RUNNING");
                }

            } catch (AiProvider.ProviderException e) {
                task.setRetryCount(attempt + 1);

                if (attempt < config.getMaxRetriesPerTask()) {
                    long backoff = (long) (config.getInitialBackoffMs()
                            * Math.pow(config.getBackoffMultiplier(), attempt));
                    backoff = Math.min(backoff, config.getMaxBackoffMs());

                    System.out.printf("    [API_ERROR] %s — retrying in %d ms%n",
                            truncateMessage(e.getMessage()), backoff);
                    sleepQuietly(backoff);
                } else {
                    task.setErrorMessage("Max retries exceeded: " + e.getMessage());
                    TaskStateMachine.transition(task, TaskState.FAILED,
                            "Max retries exceeded: " + truncateMessage(e.getMessage()));
                    System.out.println("    [STATE] RUNNING → FAILED (max retries exceeded)");
                    return false;
                }
            }
        }

        if (!apiSuccess) {
            task.setErrorMessage("API execution failed after all retry attempts.");
            if (task.getState() != TaskState.FAILED) {
                TaskStateMachine.transition(task, TaskState.FAILED, "All retry attempts exhausted");
                System.out.println("    [STATE] → FAILED (all retries exhausted)");
            }
            return false;
        }

        task.setRawOutput(rawOutput);
        System.out.println("    [API] Response received (" + rawOutput.length() + " chars)");

        TaskStateMachine.transition(task, TaskState.VALIDATING_OUTPUT, "API response received, validating output");
        System.out.println("    [STATE] RUNNING → VALIDATING_OUTPUT");

        if (task.getOutputSchemaId() != null && !task.getOutputSchemaId().isBlank()) {
            Optional<OutputSchemaTemplate> schemaOpt =
                    SchemaRegistry.getInstance().get(task.getOutputSchemaId());

            if (schemaOpt.isEmpty()) {
                task.setErrorMessage("Schema '" + task.getOutputSchemaId() + "' not found.");
                TaskStateMachine.transition(task, TaskState.FAILED, "Missing schema definition");
                System.out.println("    [STATE] VALIDATING_OUTPUT → FAILED (missing schema)");
                return false;
            }

            SchemaValidator.ValidationResult result =
                    SchemaValidator.validate(rawOutput, schemaOpt.get());

            if (!result.isValid()) {
                task.setErrorMessage("Schema validation failed: " + result.getErrorSummary());
                TaskStateMachine.transition(task, TaskState.FAILED, "Output schema mismatch: " + result.getErrorSummary());
                System.out.println("    [STATE] VALIDATING_OUTPUT → FAILED (schema mismatch)");
                return false;
            }

            task.setValidatedOutput(result.getCleanedOutput());
            System.out.println("    [SCHEMA] Validated against '" + task.getOutputSchemaId() + "'");
        } else {
            task.setValidatedOutput(rawOutput);
            System.out.println("    [SCHEMA] No schema required (passthrough)");
        }

        task.setCompletedAt(Instant.now());
        TaskStateMachine.transition(task, TaskState.COMPLETED, "Task completed successfully");
        System.out.println("    [STATE] VALIDATING_OUTPUT → COMPLETED ✓");
        return true;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  WEB SERVER (EMBEDDED DASHBOARD & API)
    // ═════════════════════════════════════════════════════════════════════════

    private static void startInternalWebServer(int port, Path artifactsPath) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendHttp(exchange, 405, "Method Not Allowed");
                return;
            }
            String html = getDashboardHtml();
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/api/status", exchange -> {
            List<String> files = listArtifacts(artifactsPath);
            String json = String.format(
                    "{\"status\":\"%s\",\"details\":\"%s\",\"waitingApproval\":%b,\"waitingInput\":%b,\"artifacts\":[%s]}",
                    escapeJson(currentStatus.get()),
                    escapeJson(currentStageInfo.get()),
                    (pendingDecision != null && !pendingDecision.isDone()),
                    (!initialUserTopicFuture.isDone()),
                    files.stream().map(f -> "\"" + escapeJson(f) + "\"").collect(Collectors.joining(","))
            );
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/api/submit-topic", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendHttp(exchange, 405, "Method Not Allowed");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseFormData(body);
            String topic = params.getOrDefault("topic", "").trim();
            String excluded = params.getOrDefault("excludedContext", "").trim();

            if (!initialUserTopicFuture.isDone()) {
                initialUserTopicFuture.complete(new TopicPayload(topic, excluded));
            }
            sendHttp(exchange, 200, "{\"ok\":true}");
        });

        server.createContext("/api/decision", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendHttp(exchange, 405, "Method Not Allowed");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String action = parseFormData(body).getOrDefault("action", "").toLowerCase();

            if (pendingDecision != null && !pendingDecision.isDone()) {
                if ("approve".equals(action) || "y".equals(action)) {
                    pendingDecision.complete(ConsoleCheckpointGate.Decision.APPROVE);
                } else if ("retry".equals(action) || "r".equals(action)) {
                    pendingDecision.complete(ConsoleCheckpointGate.Decision.RETRY);
                } else if ("abort".equals(action) || "q".equals(action)) {
                    pendingDecision.complete(ConsoleCheckpointGate.Decision.ABORT);
                }
            }
            sendHttp(exchange, 200, "{\"ok\":true}");
        });

        server.createContext("/api/artifact", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendHttp(exchange, 405, "Method Not Allowed");
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String fileName = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2 && "file".equals(pair[0])) {
                        fileName = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        break;
                    }
                }
            }

            if (fileName == null || fileName.isBlank()) {
                sendHttp(exchange, 400, "Dosya parametresi eksik.");
                return;
            }

            Path target = artifactsPath.resolve(fileName).normalize();
            if (!target.startsWith(artifactsPath) || !Files.exists(target) || !Files.isRegularFile(target)) {
                sendHttp(exchange, 404, "Dosya bulunamadı: " + fileName);
                return;
            }

            String content = Files.readString(target, StandardCharsets.UTF_8);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private static List<String> listArtifacts(Path dir) {
        if (!Files.exists(dir)) return Collections.emptyList();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static Map<String, String> parseFormData(String form) {
        Map<String, String> map = new HashMap<>();
        if (form == null || form.isBlank()) return map;
        for (String pair : form.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                map.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static void sendHttp(HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DASHBOARD HTML (TEK SAYFA — DARK THEME)
    // ═════════════════════════════════════════════════════════════════════════

    private static String getDashboardHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"tr\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <title>Cold Backlog Harvester | Kontrol Masası</title>\n" +
                "    <style>\n" +
                "        :root { --bg: #0d1117; --card: #161b22; --border: #30363d; --text: #c9d1d9; --accent: #58a6ff; --green: #238636; --yellow: #d29922; --red: #da3633; }\n" +
                "        * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
                "        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); padding: 20px; font-size: 14px; }\n" +
                "        .container { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }\n" +
                "        .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); padding-bottom: 12px; }\n" +
                "        .badge { padding: 4px 10px; border-radius: 6px; font-weight: bold; font-size: 12px; }\n" +
                "        .badge-IDLE { background: #21262d; color: #8b949e; }\n" +
                "        .badge-RUNNING { background: var(--green); color: #fff; }\n" +
                "        .badge-WAITING_INPUT { background: var(--accent); color: #fff; }\n" +
                "        .badge-WAITING_APPROVAL { background: var(--yellow); color: #fff; animation: blink 1.2s infinite; }\n" +
                "        .badge-COMPLETED { background: #1f6feb; color: #fff; }\n" +
                "        .badge-ABORTED { background: var(--red); color: #fff; }\n" +
                "        .card { background: var(--card); border: 1px solid var(--border); border-radius: 6px; padding: 16px; }\n" +
                "        .grid { display: grid; grid-template-columns: 320px 1fr; gap: 16px; }\n" +
                "        button { cursor: pointer; border: none; padding: 8px 16px; border-radius: 6px; font-weight: 600; font-size: 13px; }\n" +
                "        .btn-green { background: var(--green); color: #fff; }\n" +
                "        .btn-yellow { background: var(--yellow); color: #fff; }\n" +
                "        .btn-red { background: var(--red); color: #fff; }\n" +
                "        textarea { width: 100%; background: #0d1117; border: 1px solid var(--border); color: #fff; padding: 10px; border-radius: 6px; font-family: inherit; font-size: 13px; resize: vertical; margin-bottom: 10px; }\n" +
                "        .files { display: flex; flex-direction: column; gap: 6px; max-height: 420px; overflow-y: auto; }\n" +
                "        .file-box { background: #0d1117; border: 1px solid var(--border); padding: 8px; border-radius: 4px; cursor: pointer; font-family: monospace; font-size: 12px; word-break: break-all; }\n" +
                "        .file-box:hover { border-color: var(--accent); }\n" +
                "        pre { background: #0d1117; border: 1px solid var(--border); padding: 14px; border-radius: 6px; max-height: 450px; overflow: auto; font-family: monospace; font-size: 13px; line-height: 1.5; white-space: pre-wrap; color: #f0f6fc; }\n" +
                "        @keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"container\">\n" +
                "    <div class=\"header\">\n" +
                "        <h2>Cold Backlog Harvester</h2>\n" +
                "        <span id=\"statusBadge\" class=\"badge badge-IDLE\">IDLE</span>\n" +
                "    </div>\n" +
                "    <div id=\"inputBox\" class=\"card\" style=\"display:none;\">\n" +
                "        <h4 style=\"margin-bottom:8px;\">Yeni Sistem Mühendisliği Brifi (OPERATOR_BRIEF)</h4>\n" +
                "        <textarea id=\"topicInput\" rows=\"4\" placeholder=\"Geliştirilecek sistemin mimarisini, senaryosunu ve kurallarını buraya girin (boş bırakılırsa varsayılan reaktör brifi çalışır)...\"></textarea>\n" +
                "        <h4 style=\"margin-bottom:8px;\">Kısıtlanan Alanlar / Hariç Tutulacaklar (EXCLUDED_CONTEXT)</h4>\n" +
                "        <textarea id=\"excludedInput\" rows=\"2\" placeholder=\"Modelin kullanmaması gereken kütüphaneleri veya kavramları yazın (opsiyonel, varsayılan: NONE)...\"></textarea>\n" +
                "        <button class=\"btn-green\" onclick=\"submitTopic()\">Boru Hattını Başlat</button>\n" +
                "    </div>\n" +
                "    <div class=\"card\">\n" +
                "        <h4 style=\"margin-bottom:6px;\">Boru Hattı Durumu</h4>\n" +
                "        <div id=\"statusDetails\" style=\"color:#8b949e;\">Yükleniyor...</div>\n" +
                "        <div id=\"approvalBox\" style=\"display:none; margin-top:14px; gap:10px;\">\n" +
                "            <button class=\"btn-green\" onclick=\"sendDecision('approve')\">Onayla ve Devam Et (y)</button>\n" +
                "            <button class=\"btn-yellow\" onclick=\"sendDecision('retry')\">Aşamayı Yeniden Dene (r)</button>\n" +
                "            <button class=\"btn-red\" onclick=\"sendDecision('abort')\">Durdur (q)</button>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    <div class=\"grid\">\n" +
                "        <div class=\"card\">\n" +
                "            <h4 style=\"margin-bottom:8px;\">Üretilen Şartnameler (artifacts/)</h4>\n" +
                "            <div id=\"fileList\" class=\"files\">Dosya yok.</div>\n" +
                "        </div>\n" +
                "        <div class=\"card\">\n" +
                "            <h4 id=\"previewTitle\" style=\"margin-bottom:8px;\">Şartname Önizleme</h4>\n" +
                "            <pre id=\"fileContent\">İncelemek için soldan bir şartname dosyası seçin.</pre>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "</div>\n" +
                "<script>\n" +
                "    async function poll() {\n" +
                "        try {\n" +
                "            const r = await fetch('/api/status');\n" +
                "            const d = await r.json();\n" +
                "            const b = document.getElementById('statusBadge');\n" +
                "            b.innerText = d.status;\n" +
                "            b.className = 'badge badge-' + d.status;\n" +
                "            document.getElementById('statusDetails').innerText = d.details;\n" +
                "            document.getElementById('inputBox').style.display = d.waitingInput ? 'block' : 'none';\n" +
                "            document.getElementById('approvalBox').style.display = d.waitingApproval ? 'flex' : 'none';\n" +
                "            const list = document.getElementById('fileList');\n" +
                "            if (d.artifacts && d.artifacts.length > 0) {\n" +
                "                list.innerHTML = d.artifacts.map(f => `<div class='file-box' onclick='view(\"${f}\")'>${f}</div>`).join('');\n" +
                "            }\n" +
                "        } catch(e){}\n" +
                "    }\n" +
                "    async function submitTopic() {\n" +
                "        const topic = document.getElementById('topicInput').value;\n" +
                "        const excluded = document.getElementById('excludedInput').value;\n" +
                "        const body = 'topic=' + encodeURIComponent(topic) + '&excludedContext=' + encodeURIComponent(excluded);\n" +
                "        await fetch('/api/submit-topic', { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body: body });\n" +
                "        poll();\n" +
                "    }\n" +
                "    async function sendDecision(act) {\n" +
                "        await fetch('/api/decision', { method:'POST', body:'action=' + act });\n" +
                "        poll();\n" +
                "    }\n" +
                "    async function view(name) {\n" +
                "        document.getElementById('previewTitle').innerText = name;\n" +
                "        const r = await fetch('/api/artifact?file=' + encodeURIComponent(name));\n" +
                "        document.getElementById('fileContent').innerText = await r.text();\n" +
                "    }\n" +
                "    setInterval(poll, 1500);\n" +
                "    poll();\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CONFIGURATION
    // ═════════════════════════════════════════════════════════════════════════

    private static RunnerConfig buildConfig(String[] args) {
        RunnerConfig.Builder builder = RunnerConfig.builder()
                .apiKey(envOrDefault("GEMINI_API_KEY", "temporary blank"))
                .modelId(envOrDefault("GEMINI_MODEL_ID", "gemini-3.6-flash"))
                .apiEndpoint(envOrDefault("GEMINI_API_ENDPOINT",
                        "https://generativelanguage.googleapis.com/v1beta/"));

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String next = (i + 1 < args.length) ? args[i + 1] : null;

            switch (arg) {
                case "--api-key":
                    if (next != null) { builder.apiKey(next); i++; }
                    break;
                case "--model":
                    if (next != null) { builder.modelId(next); i++; }
                    break;
                case "--endpoint":
                    if (next != null) { builder.apiEndpoint(next); i++; }
                    break;
                case "--pause":
                    if (next != null) { builder.interTaskPauseMs(parseLong(next, 1000)); i++; }
                    break;
                case "--retries":
                    if (next != null) { builder.maxRetriesPerTask(parseInt(next, 5)); i++; }
                    break;
                case "--backoff-init":
                    if (next != null) { builder.initialBackoffMs(parseLong(next, 2000)); i++; }
                    break;
                case "--backoff-mult":
                    if (next != null) { builder.backoffMultiplier(parseDouble(next, 2.0)); i++; }
                    break;
                case "--backoff-max":
                    if (next != null) { builder.maxBackoffMs(parseLong(next, 120_000)); i++; }
                    break;
            }
        }

        return builder.build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SCHEMA REGISTRATION
    // ═════════════════════════════════════════════════════════════════════════

    private static void registerSchemas() {
        SchemaRegistry registry = SchemaRegistry.getInstance();
        registry.clear();

        // Turn 1 Şeması: Veri Tipleri, Durum Makinesi ve Değişmezler
        registry.register(new OutputSchemaTemplate(
                "spec-stage1",
                "Stage 1 Domain Architecture Schema",
                "Requires data structures, state machine, and system invariants",
                List.of(
                        new FieldSpec("Data Structures and Type Definitions", FieldType.STRING, true, "Data types and structures", null),
                        new FieldSpec("State Machine and Transition Rules", FieldType.STRING, true, "Valid states and transitions", null),
                        new FieldSpec("Non-Negotiable System Invariants", FieldType.STRING, true, "System boundary constraints", null)
                ),
                OutputFormat.MARKDOWN
        ));

        // Turn 2 Şeması: Kontratlar, Akış Sırası ve Hata Yönetimi
        registry.register(new OutputSchemaTemplate(
                "spec-stage2",
                "Stage 2 Component Contracts Schema",
                "Requires contracts, execution sequence, and error handling",
                List.of(
                        new FieldSpec("Component Contracts and Method Signatures", FieldType.STRING, true, "Method signatures and types", null),
                        new FieldSpec("Execution Sequence and Data Flow", FieldType.STRING, true, "Deterministic flow sequence", null),
                        new FieldSpec("Edge Cases and Error Handling", FieldType.STRING, true, "Failure scenarios and edge cases", null)
                ),
                OutputFormat.MARKDOWN
        ));

        // Turn 3 Şeması: Master Blueprint, Kontrol Listesi ve Modüler Ayrıştırma
        registry.register(new OutputSchemaTemplate(
                "spec-stage3",
                "Stage 3 Master Implementation Blueprint Schema",
                "Requires manifest, blueprint, verification checklist, and phase decomposition",
                List.of(
                        new FieldSpec("File Manifest and Build Order", FieldType.STRING, true, "File paths and sequence", null),
                        new FieldSpec("Implementation Blueprint", FieldType.STRING, true, "Complete structural skeletons", null),
                        new FieldSpec("Verification Checklist", FieldType.STRING, true, "Self-verification test criteria", null),
                        new FieldSpec("Modular Phase Decomposition", FieldType.STRING, true, "Scope check and modular division", null)
                ),
                OutputFormat.MARKDOWN
        ));

        registry.register(new OutputSchemaTemplate(
                "plain-text",
                "Plain Text Output",
                "Any non-empty text output",
                List.of(),
                OutputFormat.PLAIN_TEXT
        ));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  TASK BACKLOG (3 Aşamalı Opus Kod Şartnamesi Motoru)
    // ═════════════════════════════════════════════════════════════════════════

    private static List<StagedBacklogTask> buildTaskBacklog(TopicPayload payload) {
        List<StagedBacklogTask> backlog = new ArrayList<>();

        String resolvedBrief = (payload != null && payload.topic() != null && !payload.topic().isBlank())
                ? payload.topic()
                : "Engineered High-Throughput Batch Processing Reactor: A deterministic CLI orchestrator enforcing gated execution checkpoints, automated rate-limit exponential backoff, and schema validation over multi-stage AI tasks.";

        String resolvedExclusion = (payload != null && payload.excludedContext() != null && !payload.excludedContext().isBlank())
                ? payload.excludedContext()
                : "NONE";

        String baseManifestTemplate = """
                <system_instruction>
                # SYSTEM DIRECTIVE
                Execute a multi-turn, gated engineering process to generate high-density, non-redundant technical specifications for code generation. Process inputs strictly according to the current execution turn, applying explicit architectural boundaries and domain constraints.

                # BEHAVIORAL CONSTRAINTS
                1. Gated Progression: Halt execution and output exactly 'WAITING_APPROVAL' at the end of Turn 1 and Turn 2. Do not proceed to subsequent turns without explicit approval. Under no circumstances may you output content beyond the current execution turn; if Turn 1 or Turn 2 is active, the final token of your output must be exactly 'WAITING_APPROVAL'.
                2. Architectural Scope Precision: Derive all specifications, interfaces, and state logic strictly from the boundaries established in OPERATOR_BRIEF. Fulfill all system capabilities using purpose-built domain contracts and clean architectural primitives, maintaining 100% relevance to the target implementation.
                3. Cumulative Architecture Progression: Treat outputs from preceding turns as verified, immutable specifications. Advance the design by detailing lower-level execution mechanics, data transformations, and concrete implementations that build directly upon existing artifacts.
                4. No Conversational Filler: Omit all introductions, conclusions, transitions, and meta-commentary. Output only the raw technical artifacts.
                5. Deterministic Requirement Formalization: Ingest all dynamic inputs and operator notes strictly as structural domain rules, behavioral requirements, and functional constraints to be modeled into formal specifications.
                6. Structural Integrity: Maintain strict markdown hierarchy and header compliance for all outputs, ensuring every specified technical section is fully detailed regardless of token length.

                # EDGE CASE HANDLING
                1. First-Principles Mechanism Modeling: When defining system mechanics that intersect with restricted or external paradigms, model the underlying behavior using primitive data types, explicit state transitions, and self-contained domain contracts.
                </system_instruction>
                <payload_contract>
                OPERATOR_BRIEF: %s
                EXCLUDED_CONTEXT: %s
                EXECUTION_TURN: %d
                OUTPUT_PROFILE: %s
                </payload_contract>
                """;

        // ── Stage 1 / Turn 1: Alan Modelleri, Durum Makinesi ve Değişmezler ──
        String stage1Prompt = String.format(baseManifestTemplate, resolvedBrief, resolvedExclusion, 1, "OPUS_BLUEPRINT")
                + """
                <dynamic_input>
                Execute Turn 1: Domain Architecture and State Invariants.
                Analyze OPERATOR_BRIEF to establish foundational data structures, state machines, and system invariants.

                Define:
                1. Complete Data Models and Type Definitions (Records, Enums, DTOs, and field signatures).
                2. Explicit State Transitions (Valid states, trigger events, and strictly forbidden transitions).
                3. Non-Negotiable System Invariants (Resource bounds, numeric constraints, and lifecycle rules).

                Format as a structured markdown report with headers:
                # Data Structures and Type Definitions
                # State Machine and Transition Rules
                # Non-Negotiable System Invariants

                Ensure the final line is exactly: WAITING_APPROVAL
                </dynamic_input>
                """;

        backlog.add(new StagedBacklogTask(
                1,
                "Turn 1 - Domain Architecture and Invariants",
                stage1Prompt,
                Map.of(),
                TaskPriority.HIGH,
                "spec-stage1",
                true
        ));

        // ── Stage 2 / Turn 2: Bileşen Kontratları, Veri Akışı ve Hata Matrisi ──
        String stage2Prompt = String.format(baseManifestTemplate, resolvedBrief, resolvedExclusion, 2, "OPUS_BLUEPRINT")
                + """
                <dynamic_input>
                Execute Turn 2: Component Contracts, Data Flow and Edge-Case Handling.
                Consume the validated Turn 1 specifications:
                {{previousStageContext}}

                Based on the established domain models and state rules, define:
                1. Component Contracts: Concrete interfaces, public method signatures, explicit input/output types, and failure exceptions.
                2. Execution Sequence: Deterministic execution paths and data flow from initial input intake to final state/artifact resolution.
                3. Edge Cases and Error Behaviors: Concrete handling for malformed data, resource exhaustion, timeout limits, and illegal state transitions.

                Format as a structured markdown report with headers:
                # Component Contracts and Method Signatures
                # Execution Sequence and Data Flow
                # Edge Cases and Error Handling

                Ensure the final line is exactly: WAITING_APPROVAL
                </dynamic_input>
                """;

        backlog.add(new StagedBacklogTask(
                2,
                "Turn 2 - Contracts and Execution Sequence",
                stage2Prompt,
                Map.of(),
                TaskPriority.HIGH,
                "spec-stage2",
                true
        ));

        // ── Stage 3 / Turn 3: Master Kodlama Şartnamesi ve Sentez ─────────────
        String stage3Prompt = String.format(baseManifestTemplate, resolvedBrief, resolvedExclusion, 3, "OPUS_BLUEPRINT")
                + """
                <dynamic_input>
                Execute Turn 3: Master Implementation Blueprint and Execution Assembly.
                Consume the validated specifications from Turn 1 and Turn 2:
                {{previousStageContext}}

                Synthesize previous artifacts into a self-contained master blueprint for direct code generation, strictly adhering to these rules:
                - Non-Redundancy: Do not echo or re-explain Turn 1 and Turn 2 artifacts. Advance directly to concrete implementation.
                - Zero Placeholders: Provide complete structural skeletons for critical components. Never emit placeholder comments like '// TODO' or '...'.
                - Density Integrity: If the system cannot be fully specified without losing technical depth, fully detail the core foundational engine and document deferred components under Modular Phase Decomposition.

                Format as a structured markdown report with headers:
                # File Manifest and Build Order
                # Implementation Blueprint
                # Verification Checklist
                # Modular Phase Decomposition

                Do not append WAITING_APPROVAL; this completes the specification pipeline.
                </dynamic_input>.
                """;

        backlog.add(new StagedBacklogTask(
                3,
                "Turn 3 - Master Implementation Blueprint",
                stage3Prompt,
                Map.of(),
                TaskPriority.CRITICAL,
                "spec-stage3",
                false
        ));

        return backlog;
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  UTILITY METHODS
    // ═════════════════════════════════════════════════════════════════════════

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double parseDouble(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String truncateMessage(String msg) {
        if (msg == null) return "";
        if (msg.length() <= 120) return msg;
        return msg.substring(0, 120) + "…";
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}