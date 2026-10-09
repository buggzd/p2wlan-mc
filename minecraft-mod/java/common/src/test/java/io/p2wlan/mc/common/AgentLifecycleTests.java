package io.p2wlan.mc.common;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** Explicit process/application fencing tests, not Minecraft acceptance. */
public final class AgentLifecycleTests {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static Throwable failed(java.util.concurrent.CompletableFuture<?> future) throws Exception {
        try { future.get(5, TimeUnit.SECONDS); throw new AssertionError("expected failure"); }
        catch (ExecutionException error) { checks++; return error.getCause(); }
    }
    public static void main(String[] arguments) throws Exception {
        Path profile = TestProfiles.create("p2wlan-lifecycle-test-");
        try {
            try { new AgentLifecycle(Path.of("relative"), false); throw new AssertionError("relative profile accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            AgentLifecycle closed = new AgentLifecycle(profile, true);
            check(closed.status().phase() == AgentLifecycle.Phase.NEW);
            check(!closed.isCurrent(null));
            closed.close(); closed.start(); closed.close();
            check(closed.status().phase() == AgentLifecycle.Phase.CLOSED);
            closed.stopped().get(5, TimeUnit.SECONDS);
            check(failed(closed.ready()) instanceof AgentException agent && agent.code() == AgentException.Code.CLOSED);
            if (arguments.length == 0) {
                AgentLifecycle missing = new AgentLifecycle(profile, true);
                missing.start(); missing.start();
                check(failed(missing.ready()) instanceof NativeBundleException bundle && bundle.code() == NativeBundleException.Code.RESOURCE_MISSING);
                failed(missing.stopped());
                check(missing.status().phase() == AgentLifecycle.Phase.FAILED);
                check(missing.status().reason().equals(NativeBundleException.Code.RESOURCE_MISSING.name()));
                check(!missing.isCurrent(null));
                missing.close(); missing.start();
            } else {
                Path invalidProfile = java.nio.file.Files.createTempFile(profile, "not-a-profile-", ".fixture");
                AgentLifecycle rejected = new AgentLifecycle(invalidProfile, true);
                rejected.start();
                Throwable startup = failed(rejected.ready());
                check(startup instanceof AgentException agent && agent.code() == AgentException.Code.INITIALIZATION_FAILED
                        && "PROFILE_INVALID".equals(agent.remoteCode()));
                rejected.stopped().get(5, TimeUnit.SECONDS);
                check(rejected.status().phase() == AgentLifecycle.Phase.FAILED);
                check(rejected.status().reason().equals("INITIALIZATION_FAILED / PROFILE_INVALID"));
                check(!rejected.status().reason().contains(invalidProfile.toString()));
                check(rejected.current().isEmpty());
                rejected.close();
                java.nio.file.Files.delete(invalidProfile);
                for (int iteration = 0; iteration < 3; iteration++) {
                    AgentLifecycle lifecycle = new AgentLifecycle(profile, true);
                    lifecycle.start(); lifecycle.start();
                    AgentClient client = lifecycle.ready().get(5, TimeUnit.SECONDS);
                    check(lifecycle.status().phase() == AgentLifecycle.Phase.READY);
                    check(lifecycle.isCurrent(client));
                    check(client.isCurrent(client.request("ping", new com.google.gson.JsonObject()).get(5, TimeUnit.SECONDS)));
                    if (iteration == 2) {
                        client.close();
                        lifecycle.stopped().get(5, TimeUnit.SECONDS);
                        check(lifecycle.status().phase() == AgentLifecycle.Phase.FAILED);
                        check(lifecycle.status().reason().equals(AgentException.Code.UNEXPECTED_EXIT.name()));
                    } else {
                        lifecycle.close();
                        check(!lifecycle.isCurrent(client));
                        lifecycle.stopped().get(5, TimeUnit.SECONDS);
                        check(lifecycle.status().phase() == AgentLifecycle.Phase.CLOSED);
                    }
                    check(!lifecycle.isCurrent(client));
                    check(client.terminated().isDone());
                    check(client.snapshot().isEmpty());
                    lifecycle.close(); lifecycle.start();
                }
                for (int iteration = 0; iteration < 8; iteration++) {
                    AgentLifecycle lifecycle = new AgentLifecycle(profile, true);
                    lifecycle.start();
                    if (iteration % 2 == 1) Thread.sleep(2);
                    lifecycle.close();
                    try { lifecycle.stopped().get(5, TimeUnit.SECONDS); checks++; }
                    catch (ExecutionException error) { check(error.getCause() instanceof NativeBundleException || error.getCause() instanceof AgentException); }
                    check(!lifecycle.isCurrent(null));
                    check(lifecycle.status().phase() == AgentLifecycle.Phase.CLOSED);
                    lifecycle.start();
                }
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            boolean ended;
            do {
                ended = Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                        && (thread.getName().equals("p2wlan-mod-startup") || thread.getName().startsWith("p2wlan-agent-")));
                if (!ended) Thread.sleep(10);
            } while (!ended && System.nanoTime() - deadline < 0);
            check(ended);
        } finally { TestProfiles.dispose(profile); }
        System.out.println("PASS application agent lifecycle: " + checks + " explicit checks" + (arguments.length == 1 ? " against actual packaged agents" : ""));
    }
}
