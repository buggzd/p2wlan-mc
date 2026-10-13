package io.p2wlan.mc.legacybootstrap;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** File-backed canonical child URLs revalidate the owner at the actual read. */
final class VerifiedJar {
    final String name;
    final OwnerFile file;
    final Map<String,byte[]> entries;
    final Manifest manifest;
    VerifiedJar(String name, OwnerFile file, Map<String,byte[]> entries) {
        this.name = name; this.file = file; this.entries = entries;
        byte[] metadata = entries.get("META-INF/MANIFEST.MF");
        try { manifest = metadata == null ? null : new Manifest(new ByteArrayInputStream(metadata)); }
        catch (IOException failure) { throw new LegacyFailure(Code.ARCHIVE_CONTRACT); }
    }
    URL resource(LegacyOwner owner, String entry) {
        byte[] value = entries.get(entry);
        if (value == null) throw new LegacyFailure(Code.RESOURCE_MISSING);
        try {
            return new URL(null, "jar:" + file.path.toUri().toASCIIString() + "!/" + entry,
                    new URLStreamHandler() {
                        @Override protected URLConnection openConnection(URL url) throws IOException {
                            return new VerifiedConnection(url, owner, entry, value);
                        }
                    });
        } catch (IOException failure) { throw new LegacyFailure(Code.RESOURCE_IO); }
    }
    private final class VerifiedConnection extends JarURLConnection {
        private final LegacyOwner owner;
        private final String entry;
        private final byte[] expected;
        private JarFile opened;
        private byte[] snapshot;
        VerifiedConnection(URL url, LegacyOwner owner, String entry, byte[] expected) throws IOException {
            super(url); this.owner = owner; this.entry = entry; this.expected = expected;
        }
        @Override public void connect() throws IOException {
            if (connected) return;
            owner.requireCurrent(); file.requireCurrent(true);
            JarFile candidate = new JarFile(file.path.toFile(), false);
            try {
                var member = candidate.getJarEntry(entry);
                if (member == null || member.isDirectory() || member.getSize() != expected.length) throw new LegacyFailure(Code.CONTENT_IDENTITY);
                try (InputStream input = candidate.getInputStream(member)) {
                    snapshot = input.readNBytes(expected.length + 1);
                }
                file.requireCurrent(true); owner.requireCurrent();
                if (snapshot.length != expected.length || !OwnerFile.digest(snapshot,"SHA-256").equals(OwnerFile.digest(expected,"SHA-256"))) {
                    throw new LegacyFailure(Code.CONTENT_IDENTITY);
                }
                opened = candidate; connected = true;
            } catch (IOException | RuntimeException failure) { candidate.close(); throw failure; }
        }
        @Override public JarFile getJarFile() throws IOException { connect(); return opened; }
        @Override public Manifest getManifest() throws IOException { connect(); return manifest == null ? null : new Manifest(manifest); }
        @Override public int getContentLength() { return expected.length; }
        @Override public long getContentLengthLong() { return expected.length; }
        @Override public InputStream getInputStream() throws IOException {
            connect();
            return new FilterInputStream(new ByteArrayInputStream(snapshot)) {
                @Override public void close() throws IOException { super.close(); opened.close(); }
            };
        }
    }
}
