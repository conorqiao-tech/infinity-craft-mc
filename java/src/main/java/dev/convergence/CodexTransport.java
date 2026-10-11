package dev.convergence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Ephemeral, answer-only Codex app-server sessions. Model output is never dispatched. */
final class CodexTransport implements ServerAssistant.Transport {
    static final String AUDITED_VERSION = "codex-cli 0.155.1";
    static final int MAX_LINE = 65_536, MAX_TOTAL = 262_144, MAX_ANSWER = 16_384;
    static final int MAX_INSTRUCTIONS = 24_576;
    static final Set<String> DISABLED_FEATURES = Set.of("apps", "plugins", "remote_plugin", "hooks",
        "shell_tool", "shell_snapshot", "code_mode", "code_mode_host", "browser_use", "browser_use_external",
        "computer_use", "multi_agent", "multi_agent_v2", "image_generation", "view_image", "memories",
        "skill_search", "skill_mcp_dependency_install", "tool_suggest", "workspace_dependencies",
        "in_app_browser", "in_app_local_automation", "goals", "sleep_tool", "default_mode_request_user_input",
        "request_permissions_tool", "guardian_approval", "realtime_conversation");

    interface ProcessFactory { Process start(List<String> command, Path directory) throws IOException; }
    private final ProcessFactory factory;
    private final ExecutorService executor;
    private final Set<Call> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    CodexTransport() {
        this((command, directory) -> new ProcessBuilder(command).directory(directory.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start());
    }
    CodexTransport(ProcessFactory factory) {
        this.factory = factory;
        this.executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8),
            task -> { var thread = new Thread(task, "infinity-codex-chat"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    }

    static JsonObject lockdownConfig() {
        var config = new JsonObject();
        var features = new JsonObject();
        DISABLED_FEATURES.stream().sorted().forEach(name -> features.addProperty(name, false));
        features.addProperty("skip_host_skill_discovery", true);
        config.add("features", features);
        var bundled = new JsonObject(); bundled.addProperty("enabled", false);
        var skills = new JsonObject(); skills.add("bundled", bundled); skills.addProperty("include_instructions", false);
        config.add("skills", skills);
        var disabled = new JsonObject(); disabled.addProperty("enabled", false);
        var tools = new JsonObject(); tools.add("update_plan", disabled.deepCopy());
        tools.add("experimental_request_user_input", disabled.deepCopy()); config.add("tools", tools);
        config.addProperty("web_search", "disabled");
        config.addProperty("approval_policy", "never");
        config.addProperty("sandbox_mode", "read-only");
        config.addProperty("project_doc_max_bytes", 0);
        config.addProperty("developer_instructions", "");
        var history = new JsonObject(); history.addProperty("persistence", "none"); config.add("history", history);
        var memories = new JsonObject(); memories.addProperty("use_memories", false);
        memories.addProperty("generate_memories", false); memories.addProperty("dedicated_tools", false); config.add("memories", memories);
        return config;
    }

    /** Empty MCP maps merge with inherited config. Disable every effective ID explicitly. */
    static JsonObject threadConfig(JsonObject effective) throws IOException {
        var requested = lockdownConfig();
        var features = effective.has("features") && effective.get("features").isJsonObject()
            ? effective.getAsJsonObject("features") : new JsonObject();
        for (String name : DISABLED_FEATURES) {
            var value = features.get(name);
            boolean off = value != null && (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean()
                || value.isJsonObject() && value.getAsJsonObject().has("enabled") && !value.getAsJsonObject().get("enabled").getAsBoolean());
            if (!off) throw new IOException("Codex capability isolation could not be verified.");
        }
        if (!features.has("skip_host_skill_discovery") || !features.get("skip_host_skill_discovery").getAsBoolean())
            throw new IOException("Codex skill isolation could not be verified.");
        // 0.155.1's typed config/read response omits update_plan and request_user_input;
        // strict-config validates those overrides at startup, and protocol events fail closed.
        for (var entry : requested.entrySet()) if (!Set.of("features", "tools").contains(entry.getKey())
            && !matches(entry.getValue(), effective.get(entry.getKey())))
            throw new IOException("Codex configuration isolation could not be verified.");
        for (String field : List.of("model_instructions_file", "instructions"))
            if (effective.has(field) && !effective.get(field).isJsonNull() && !"".equals(string(effective, field)))
                throw new IOException("Codex has custom inherited instructions; answer-only chat is unavailable.");
        if (!"".equals(string(effective, "developer_instructions")) || !effective.has("project_doc_max_bytes")
            || effective.get("project_doc_max_bytes").getAsInt() != 0)
            throw new IOException("Codex instruction isolation could not be verified.");
        var servers = new JsonObject();
        if (effective.has("mcp_servers") && !effective.get("mcp_servers").isJsonNull()) {
            if (!effective.get("mcp_servers").isJsonObject() || effective.getAsJsonObject("mcp_servers").size() > 128)
                throw new IOException("Codex integration configuration is unavailable.");
            for (String name : effective.getAsJsonObject("mcp_servers").keySet()) {
                if (name.length() > 256) throw new IOException("Codex integration configuration is unavailable.");
                var server = new JsonObject(); server.addProperty("enabled", false); server.addProperty("required", false);
                servers.add(name, server);
            }
        }
        requested.add("mcp_servers", servers);
        return requested;
    }

    private static boolean matches(com.google.gson.JsonElement expected, com.google.gson.JsonElement actual) {
        if (actual == null) return false;
        if (!expected.isJsonObject()) return expected.equals(actual);
        if (!actual.isJsonObject()) return false;
        for (var entry : expected.getAsJsonObject().entrySet())
            if (!matches(entry.getValue(), actual.getAsJsonObject().get(entry.getKey()))) return false;
        return true;
    }

    private static void flags(List<String> command, String prefix, JsonObject value) {
        for (var entry : value.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue().isJsonObject()) flags(command, key, entry.getValue().getAsJsonObject());
            else { command.add("-c"); command.add(key + "=" + entry.getValue()); }
        }
    }

    @Override public CompletableFuture<String> ask(ServerAssistant.AiConfig config, Path unusedKeyFile,
            String question, String instructions) {
        if (closed) return CompletableFuture.failedFuture(new IOException("Codex chat is closed."));
        String executable = config.codexExecutable;
        if (executable == null || executable.isBlank() || !Path.of(executable).isAbsolute()
            || ServerAssistant.invalid(question) != null || instructions == null || instructions.length() > MAX_INSTRUCTIONS)
            return CompletableFuture.failedFuture(new IOException("Codex chat configuration is unavailable."));
        var call = new Call();
        active.add(call);
        call.whenComplete((answer, error) -> { call.stop(); active.remove(call); });
        call.orTimeout(60, TimeUnit.SECONDS);
        try { executor.execute(() -> run(call, executable, config.model, question, instructions)); }
        catch (RuntimeException rejected) { call.completeExceptionally(new IOException("Codex chat is busy.")); }
        return call;
    }

    private void run(Call call, String executable, String model, String question, String instructions) {
        Path directory = null;
        try {
            if (call.isDone() || closed) return;
            directory = Files.createTempDirectory("infinity-codex-chat-");
            try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
            catch (UnsupportedOperationException ignored) { /* Non-POSIX hosts use their user temp-directory ACL. */ }
            Process version = factory.start(List.of(executable, "--version"), directory);
            call.attach(version);
            var versionBytes = new BoundedReader(version.getInputStream(), 4096, 4096);
            String actualVersion = versionBytes.line();
            if (!AUDITED_VERSION.equals(actualVersion) || !version.waitFor(5, TimeUnit.SECONDS) || version.exitValue() != 0)
                throw new IOException("Codex CLI version needs a compatibility review before chat can run.");
            call.stop();
            if (call.isDone() || closed) return;
            var command = new ArrayList<>(List.of(executable, "app-server", "--stdio", "--strict-config"));
            flags(command, "", lockdownConfig());
            Process process = factory.start(List.copyOf(command), directory);
            call.attach(process);
            var reader = new BoundedReader(process.getInputStream(), MAX_LINE, MAX_TOTAL);
            var protocol = new Protocol(process, reader);
            var initialize = new JsonObject();
            var client = new JsonObject(); client.addProperty("name", "infinity_minecraft_chat"); client.addProperty("version", "1.0");
            initialize.add("clientInfo", client);
            var capabilities = new JsonObject(); capabilities.addProperty("experimentalApi", true); initialize.add("capabilities", capabilities);
            protocol.request(1, "initialize", initialize);
            protocol.result(1);
            protocol.notify("initialized", new JsonObject());
            var read = new JsonObject(); read.addProperty("includeLayers", false);
            protocol.request(2, "config/read", read);
            var configuration = protocol.result(2);
            if (!configuration.has("config") || !configuration.get("config").isJsonObject()) throw new IOException("Codex configuration is unavailable.");
            JsonObject isolated = threadConfig(configuration.getAsJsonObject("config"));
            // User/MCP config contents never enter the model prompt or logs.
            configuration = null;
            var start = new JsonObject();
            start.addProperty("model", model); start.addProperty("cwd", directory.toString());
            start.addProperty("approvalPolicy", "never"); start.addProperty("sandbox", "read-only");
            start.addProperty("ephemeral", true); start.add("environments", new JsonArray());
            start.add("dynamicTools", new JsonArray()); start.add("selectedCapabilityRoots", new JsonArray());
            start.add("config", isolated);
            start.addProperty("baseInstructions", "You are an answer-only Minecraft helper. You have no tools or environment access. Answer briefly in plain text. Never claim to run commands, change files, approve actions, or share another Codex chat's memory.");
            start.addProperty("developerInstructions", instructions);
            protocol.request(3, "thread/start", start);
            var started = protocol.result(3);
            if (!started.has("instructionSources") || !started.get("instructionSources").isJsonArray()
                || !started.getAsJsonArray("instructionSources").isEmpty())
                throw new IOException("Codex inherited an instruction file; answer-only chat stopped.");
            if (!"never".equals(string(started, "approvalPolicy"))) throw new IOException("Codex approval isolation could not be verified.");
            if (!started.has("sandbox") || !started.get("sandbox").isJsonObject()
                || !"readOnly".equals(string(started.getAsJsonObject("sandbox"), "type")))
                throw new IOException("Codex read-only isolation could not be verified.");
            var thread = started.getAsJsonObject("thread");
            String threadId = string(thread, "id");
            if (threadId == null || threadId.length() > 200 || !thread.has("ephemeral") || !thread.get("ephemeral").getAsBoolean())
                throw new IOException("Codex ephemeral session could not be verified.");
            protocol.thread = threadId;
            var turn = new JsonObject(); turn.addProperty("threadId", threadId);
            turn.add("environments", new JsonArray()); turn.addProperty("approvalPolicy", "never");
            var sandbox = new JsonObject(); sandbox.addProperty("type", "readOnly"); sandbox.addProperty("networkAccess", false); turn.add("sandboxPolicy", sandbox);
            var input = new JsonObject(); input.addProperty("type", "text"); input.addProperty("text", question);
            var inputs = new JsonArray(); inputs.add(input); turn.add("input", inputs);
            protocol.request(4, "turn/start", turn);
            protocol.result(4);
            while (!protocol.complete && !call.isDone()) protocol.event(protocol.next());
            if (!call.isDone()) {
                if (protocol.answer == null || protocol.answer.isBlank()) throw new IOException("Codex returned no readable answer.");
                call.stop();
                call.complete(protocol.answer);
            }
        } catch (InterruptedException interrupted) {
            call.stop(); Thread.currentThread().interrupt(); call.completeExceptionally(new IOException("Codex chat was interrupted."));
        } catch (Exception failure) {
            // Never surface raw process output, provider errors, prompts, or configuration.
            call.stop(); call.completeExceptionally(new IOException("Codex chat is unavailable or its answer-only checks failed."));
        } finally {
            call.stop();
            if (directory != null) try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) { /* Private temp cleanup is retried by the OS; no credential was copied here. */ }
        }
    }

    static String string(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isString()
            ? object.get(key).getAsString() : null;
    }

    static final class Call extends CompletableFuture<String> {
        private Process process;
        synchronized void attach(Process child) { process = child; if (isDone()) stop(); }
        synchronized void stop() {
            Process owned = process; process = null;
            if (owned == null) return;
            try { owned.descendants().forEach(child -> { child.destroy(); if (child.isAlive()) child.destroyForcibly(); }); }
            catch (UnsupportedOperationException ignored) { }
            owned.destroy(); if (owned.isAlive()) owned.destroyForcibly();
            try { owned.getOutputStream().close(); } catch (IOException ignored) { }
        }
    }

    static final class BoundedReader {
        final InputStream input; final int lineLimit, totalLimit; int total;
        BoundedReader(InputStream input, int lineLimit, int totalLimit) { this.input = input; this.lineLimit = lineLimit; this.totalLimit = totalLimit; }
        String line() throws IOException {
            var bytes = new ByteArrayOutputStream(); int value;
            while ((value = input.read()) != -1) {
                if (++total > totalLimit || bytes.size() >= lineLimit) throw new IOException("Codex output exceeded its size limit.");
                if (value == '\n') return bytes.toString(StandardCharsets.UTF_8).stripTrailing();
                bytes.write(value);
            }
            if (bytes.size() != 0) return bytes.toString(StandardCharsets.UTF_8).stripTrailing();
            throw new IOException("Codex chat ended before answering.");
        }
    }

    static final class Protocol {
        final Process process; final BoundedReader reader;
        String thread, answer; boolean complete;
        Protocol(Process process, BoundedReader reader) { this.process = process; this.reader = reader; }
        void request(int id, String method, JsonObject params) throws IOException { var message = message(method, params); message.addProperty("id", id); write(message); }
        void notify(String method, JsonObject params) throws IOException { write(message(method, params)); }
        private JsonObject message(String method, JsonObject params) { var result = new JsonObject(); result.addProperty("method", method); result.add("params", params); return result; }
        private void write(JsonObject message) throws IOException {
            byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 65_536) throw new IOException("Codex input exceeded its size limit.");
            process.getOutputStream().write(bytes); process.getOutputStream().flush();
        }
        JsonObject next() throws IOException {
            try { var value = JsonParser.parseString(reader.line()); if (value.isJsonObject()) return value.getAsJsonObject(); }
            catch (RuntimeException malformed) { throw new IOException("Codex returned invalid protocol data."); }
            throw new IOException("Codex returned invalid protocol data.");
        }
        JsonObject result(int id) throws IOException {
            while (true) {
                var message = next();
                if (message.has("id") && !message.has("method")) {
                    if (!message.get("id").isJsonPrimitive() || !message.getAsJsonPrimitive("id").isNumber() || message.get("id").getAsInt() != id
                        || message.has("error") || !message.has("result") || !message.get("result").isJsonObject())
                        throw new IOException("Codex session initialization failed.");
                    return message.getAsJsonObject("result");
                }
                event(message);
            }
        }
        void event(JsonObject message) throws IOException {
            String method = string(message, "method");
            if (message.has("id")) throw new IOException("Codex requested a tool or approval; answer-only chat stopped.");
            if (method == null) throw new IOException("Codex returned invalid protocol data.");
            var params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();
            if (method.startsWith("mcpServer/") || method.startsWith("hook/") || method.equals("error"))
                throw new IOException("Codex answer-only isolation failed.");
            if (method.equals("item/started") || method.equals("item/completed")) {
                var item = params.getAsJsonObject("item"); String type = string(item, "type");
                if (!Set.of("userMessage", "agentMessage", "reasoning").contains(type == null ? "" : type))
                    throw new IOException("Codex attempted a tool action; answer-only chat stopped.");
                if (thread != null && !thread.equals(string(params, "threadId"))) throw new IOException("Codex session identity changed.");
                if (method.equals("item/completed") && "agentMessage".equals(type) && !"commentary".equals(string(item, "phase"))) {
                    String text = string(item, "text");
                    if (text == null || text.length() > MAX_ANSWER) throw new IOException("Codex answer exceeded its size limit.");
                    answer = text;
                }
            }
            if (method.equals("turn/completed")) {
                if (!thread.equals(string(params, "threadId")) || !"completed".equals(string(params.getAsJsonObject("turn"), "status")))
                    throw new IOException("Codex could not complete its answer.");
                complete = true;
            }
        }
    }

    @Override public void close() { closed = true; for (Call call : active) call.cancel(true); executor.shutdownNow(); }
}
