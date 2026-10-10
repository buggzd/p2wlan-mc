package io.p2wlan.mc.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static io.p2wlan.mc.common.NativeBundleException.Code;

/** Owned bundle + child. Extraction and process startup belong off the game
 * thread. Closing is nonblocking; released completes only after child exit
 * AND identity-checked cleanup. No shared executable cache or auto-download. */
public final class BundledAgent implements AutoCloseable {
    private final AgentClient client;
    private final CompletableFuture<Void> released = new CompletableFuture<>();
    private BundledAgent(AgentClient client, NativeInstallation installation) {
        this.client = client;
        client.terminated().whenComplete((ignored, error) -> {
            try { installation.close(); released.complete(null); }
            catch (NativeBundleException cleanup) { released.completeExceptionally(cleanup); }
        });
    }
    public static BundledAgent launch(Path profile, boolean allowDevelopment) throws NativeBundleException {
        return launch(profile, allowDevelopment, null);
    }
    static BundledAgent launch(Path profile, boolean allowDevelopment, ModDiagnostics diagnostics) throws NativeBundleException {
        if (profile == null || !profile.isAbsolute()) throw new NativeBundleException(Code.START_FAILED);
        NativeInstallation installation = null;
        BundledAgent owner = null;
        try {
            if (diagnostics != null) diagnostics.stage(ModDiagnostics.Stage.BUNDLE_PLATFORM);
            String platform = NativeManifest.currentPlatform();
            String prefix = "p2wlan/native/" + platform + "/";
            NativeManifest manifest;
            if (diagnostics != null) diagnostics.stage(ModDiagnostics.Stage.BUNDLE_MANIFEST);
            try (InputStream metadata = resource(prefix + "manifest.properties")) {
                manifest = NativeManifest.read(metadata, platform, allowDevelopment);
            }
            if (diagnostics != null) { diagnostics.build(manifest); diagnostics.stage(ModDiagnostics.Stage.BUNDLE_EXTRACT); }
            try (InputStream bytes = resource(prefix + manifest.executableName())) {
                installation = NativeInstallation.extract(manifest, bytes, Path.of(System.getProperty("java.io.tmpdir")));
            }
            if (diagnostics != null) diagnostics.stage(ModDiagnostics.Stage.CHILD_START);
            AgentClient client = AgentClient.launch(installation.validatedExecutable(), List.of("--profile-dir", profile.toString()),
                    AgentClient.DEFAULT_TIMEOUT, diagnostics);
            owner = new BundledAgent(client, installation);
            installation.checkLaunchedIdentity();
            return owner;
        } catch (IOException | RuntimeException error) {
            if (diagnostics != null) diagnostics.failure(error);
            if (owner != null) owner.close(); // Child exit callback owns cleanup now.
            else if (installation != null) installation.close();
            if (error instanceof NativeBundleException known) throw known;
            throw new NativeBundleException(error instanceof AgentException ? Code.START_FAILED : Code.IO);
        }
    }
    private static InputStream resource(String name) throws IOException {
        var candidates = BundledAgent.class.getClassLoader().getResources(name);
        if (!candidates.hasMoreElements()) throw new NativeBundleException(Code.RESOURCE_MISSING);
        URL url = candidates.nextElement();
        if (candidates.hasMoreElements()) throw new NativeBundleException(Code.RESOURCE_AMBIGUOUS);
        var connection = url.openConnection();
        connection.setUseCaches(false);
        if (!url.getProtocol().equals("file") && !(connection instanceof JarURLConnection jar
                && jar.getJarFileURL().getProtocol().equals("file"))) throw new NativeBundleException(Code.RESOURCE_NOT_LOCAL);
        return connection.getInputStream();
    }
    public AgentClient client() { return client; }
    public CompletableFuture<Void> released() { return released.copy(); }
    @Override public void close() { client.close(); }
}
