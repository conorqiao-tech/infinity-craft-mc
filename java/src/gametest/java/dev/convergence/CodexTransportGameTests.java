package dev.convergence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Scripted child-process protocols: no Codex model calls, auth reads, or external charges. */
public class CodexTransportGameTests {
    enum Behavior { ANSWER, APPROVAL, TOOL, OVERSIZE, WAIT, INHERITED, WRONG_THREAD }

    static final class FinishedProcess extends Process {
        final InputStream stdout;
        FinishedProcess(String version) { stdout = new ByteArrayInputStream((version + "\n").getBytes(StandardCharsets.UTF_8)); }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public boolean isAlive() { return false; }
        @Override public void destroy() {}
    }

    static final class FakeProcess extends Process {
        final Behavior behavior;
        final PipedInputStream stdout = new PipedInputStream(1_048_576);
        final PipedOutputStream reply;
        final List<JsonObject> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch asked = new CountDownLatch(1);
        volatile boolean alive = true;
        final OutputStream stdin = new OutputStream() {
            final ByteArrayOutputStream line = new ByteArrayOutputStream();
            @Override public void write(int value) throws IOException {
                if (value == '\n') {
                    var message = JsonParser.parseString(line.toString(StandardCharsets.UTF_8)).getAsJsonObject();
                    line.reset(); receive(message);
                } else line.write(value);
            }
        };
        FakeProcess(Behavior behavior) throws IOException { this.behavior = behavior; reply = new PipedOutputStream(stdout); }
        void emit(JsonObject message) throws IOException { reply.write((message + "\n").getBytes(StandardCharsets.UTF_8)); reply.flush(); }
        void result(int id, JsonObject value) throws IOException { var message = new JsonObject(); message.addProperty("id", id); message.add("result", value); emit(message); }
        void event(String method, JsonObject params) throws IOException {
            var message = new JsonObject(); message.addProperty("method", method); message.add("params", params); emit(message);
        }
        void receive(JsonObject message) throws IOException {
            requests.add(message.deepCopy());
            String method = message.get("method").getAsString();
            if (method.equals("initialized")) return;
            int id = message.get("id").getAsInt();
            switch (method) {
                case "initialize" -> result(id, new JsonObject());
                case "config/read" -> {
                    var config = CodexTransport.lockdownConfig();
                    var inherited = new JsonObject(); inherited.addProperty("url", "https://unused.invalid");
                    inherited.addProperty("private_test_value", "never-forward-this-config-value");
                    var servers = new JsonObject(); servers.add("inherited.server", inherited); config.add("mcp_servers", servers);
                    var response = new JsonObject(); response.add("config", config); result(id, response);
                }
                case "thread/start" -> {
                    var response = new JsonObject(); response.addProperty("approvalPolicy", "never");
                    var sandbox = new JsonObject(); sandbox.addProperty("type", "readOnly"); sandbox.addProperty("networkAccess", false); response.add("sandbox", sandbox);
                    var thread = new JsonObject(); thread.addProperty("id", "thread-test"); thread.addProperty("ephemeral", true); response.add("thread", thread);
                    var sources = new JsonArray(); if (behavior == Behavior.INHERITED) sources.add("private/AGENTS.md"); response.add("instructionSources", sources);
                    result(id, response);
                }
                case "turn/start" -> {
                    asked.countDown(); result(id, new JsonObject());
                    if (behavior == Behavior.WAIT) return;
                    if (behavior == Behavior.APPROVAL) {
                        var request = new JsonObject(); request.addProperty("id", 99); request.addProperty("method", "item/commandExecution/requestApproval");
                        request.add("params", new JsonObject()); emit(request); return;
                    }
                    var item = new JsonObject(); item.addProperty("id", "answer-test");
                    item.addProperty("type", behavior == Behavior.TOOL ? "commandExecution" : "agentMessage");
                    item.addProperty("phase", "final_answer");
                    item.addProperty("text", behavior == Behavior.OVERSIZE ? "x".repeat(CodexTransport.MAX_ANSWER + 1) : "Your named helper is ready beside you.");
                    var params = new JsonObject(); params.addProperty("threadId", behavior == Behavior.WRONG_THREAD ? "another-thread" : "thread-test");
                    params.addProperty("turnId", "turn-test"); params.add("item", item); event("item/completed", params);
                    var turn = new JsonObject(); turn.addProperty("id", "turn-test"); turn.addProperty("status", "completed");
                    var complete = new JsonObject(); complete.addProperty("threadId", "thread-test"); complete.add("turn", turn); event("turn/completed", complete);
                }
                default -> throw new IOException("Unexpected test request");
            }
        }
        JsonObject params(String method) {
            return requests.stream().filter(request -> method.equals(CodexTransport.string(request, "method")))
                .map(request -> request.getAsJsonObject("params")).findFirst().orElse(null);
        }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { while (alive) Thread.sleep(1); return 0; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { alive = false; try { reply.close(); } catch (IOException ignored) {} }
        @Override public Process destroyForcibly() { destroy(); return this; }
    }

    static final class Fixture implements AutoCloseable {
        final FakeProcess process;
        final List<List<String>> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CodexTransport transport;
        final ServerAssistant.AiConfig config = new ServerAssistant.AiConfig();
        Fixture(Behavior behavior) throws IOException { this(behavior, CodexTransport.AUDITED_VERSION); }
        Fixture(Behavior behavior, String version) throws IOException {
            process = new FakeProcess(behavior);
            config.provider = "codex"; config.codexExecutable = "/audited/test/codex"; config.model = "gpt-6-luna";
            transport = new CodexTransport((command, directory) -> {
                commands.add(List.copyOf(command));
                return command.contains("--version") ? new FinishedProcess(version) : process;
            });
        }
        CompletableFuture<String> ask() { return transport.ask(config, Path.of("/not-read/private-key"), "Where is my helper?", "Answer from the supplied Minecraft facts only."); }
        @Override public void close() { transport.close(); process.destroy(); }
    }

    @GameTest public void codexChatUsesEphemeralNoEnvironmentSessionsAndNeverForwardsPrivateConfig(GameTestHelper c) throws Exception {
        try (var f = new Fixture(Behavior.ANSWER)) {
            c.assertValueEqual(f.ask().get(5, TimeUnit.SECONDS), "Your named helper is ready beside you.", "Only the completed answer reaches Minecraft");
            var start = f.process.params("thread/start");
            c.assertTrue(start.get("ephemeral").getAsBoolean(), "Chat has no persistent conversation history");
            for (String field : List.of("environments", "dynamicTools", "selectedCapabilityRoots"))
                c.assertTrue(start.getAsJsonArray(field).isEmpty(), "No access is granted through " + field);
            c.assertTrue(f.process.params("turn/start").getAsJsonArray("environments").isEmpty(), "The turn cannot re-enable environment access");
            var inherited = start.getAsJsonObject("config").getAsJsonObject("mcp_servers").getAsJsonObject("inherited.server");
            c.assertFalse(inherited.get("enabled").getAsBoolean(), "Every inherited MCP ID is explicitly disabled");
            c.assertValueEqual(inherited.size(), 2, "Only disabled and non-required flags are copied, never credentials or endpoints");
            c.assertFalse(start.toString().contains("never-forward-this-config-value"), "Private config never enters the thread prompt or settings");
            c.assertFalse(f.commands.toString().contains("Where is my helper?"), "Questions go over private stdin rather than process arguments");
            c.assertFalse(f.process.isAlive(), "The owned app-server exits after its answer");
        } c.succeed();
    }

    @GameTest public void codexChatRejectsToolsApprovalsForeignRepliesAndOversizedAnswers(GameTestHelper c) throws Exception {
        for (var behavior : List.of(Behavior.APPROVAL, Behavior.TOOL, Behavior.WRONG_THREAD, Behavior.OVERSIZE)) {
            try (var f = new Fixture(behavior)) {
                boolean failed = false;
                try { f.ask().get(5, TimeUnit.SECONDS); } catch (ExecutionException expected) { failed = true; }
                c.assertTrue(failed, "Answer-only transport rejects " + behavior);
                c.assertFalse(f.process.isAlive(), "A rejected " + behavior + " stops the owned child");
                c.assertValueEqual(f.process.requests.size(), 5, "No approval response or tool dispatch is sent");
            }
        } c.succeed();
    }

    @GameTest public void codexChatCancellationAndCloseStopOnlyTheirOwnedChildren(GameTestHelper c) throws Exception {
        try (var f = new Fixture(Behavior.WAIT)) {
            var pending = f.ask();
            c.assertTrue(f.process.asked.await(5, TimeUnit.SECONDS), "The fake request reaches its waiting state");
            pending.cancel(true);
            c.assertTrue(pending.isCancelled(), "Disconnect-style cancellation terminates the pending result");
            c.assertFalse(f.process.isAlive(), "Cancellation kills the associated child");
        }
        try (var f = new Fixture(Behavior.WAIT)) {
            var pending = f.ask();
            c.assertTrue(f.process.asked.await(5, TimeUnit.SECONDS), "The second fake request reaches its waiting state");
            f.transport.close();
            c.assertTrue(pending.isCancelled() && !f.process.isAlive(), "Server shutdown cancels and stops its pending child");
        } c.succeed();
    }

    @GameTest public void codexChatFailsClosedBeforeATurnForUnauditedVersionOrInheritedInstructions(GameTestHelper c) throws Exception {
        try (var f = new Fixture(Behavior.ANSWER, "codex-cli 0.100.0")) {
            boolean failed = false;
            try { f.ask().get(5, TimeUnit.SECONDS); } catch (ExecutionException expected) { failed = true; }
            c.assertTrue(failed, "Unknown CLI versions cannot silently ignore no-environment fields");
            c.assertValueEqual(f.commands.size(), 1, "Only the version probe runs on an unsupported CLI");
        }
        try (var f = new Fixture(Behavior.INHERITED)) {
            boolean failed = false;
            try { f.ask().get(5, TimeUnit.SECONDS); } catch (ExecutionException expected) { failed = true; }
            c.assertTrue(failed, "Inherited instruction files prevent model invocation");
            c.assertTrue(f.process.params("turn/start") == null, "No model turn begins with inherited instructions");
        }
        var config = CodexTransport.lockdownConfig(); config.getAsJsonObject("features").addProperty("shell_tool", true);
        boolean rejected = false;
        try { CodexTransport.threadConfig(config); } catch (IOException expected) { rejected = true; }
        c.assertTrue(rejected, "Effective capabilities must match the explicit disabled configuration");
        c.succeed();
    }

    @GameTest public void codexProtocolBoundsRawLinesAndAggregateBytes(GameTestHelper c) throws Exception {
        boolean lineRejected = false, totalRejected = false;
        try { new CodexTransport.BoundedReader(new ByteArrayInputStream("12345\n".getBytes(StandardCharsets.UTF_8)), 4, 100).line(); }
        catch (IOException expected) { lineRejected = true; }
        var reader = new CodexTransport.BoundedReader(new ByteArrayInputStream("abc\ndef\n".getBytes(StandardCharsets.UTF_8)), 10, 6);
        reader.line();
        try { reader.line(); } catch (IOException expected) { totalRejected = true; }
        c.assertTrue(lineRejected && totalRejected, "A verbose child cannot exhaust memory using one line or many small lines");
        c.succeed();
    }
}
