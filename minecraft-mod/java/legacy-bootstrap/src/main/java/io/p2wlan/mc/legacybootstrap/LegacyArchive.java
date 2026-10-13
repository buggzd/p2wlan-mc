package io.p2wlan.mc.legacybootstrap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import static io.p2wlan.mc.legacybootstrap.LegacyFailure.Code;

/** Bounded ZIP snapshots; duplicate and noncanonical names fail before effects. */
final class LegacyArchive {
    static final int OUTER_MAXIMUM = 144 * 1024 * 1024;
    static final int MEMBER_MAXIMUM = 128 * 1024 * 1024;
    static Map<String,byte[]> read(byte[] bytes, int inflatedMaximum) {
        Map<String,byte[]> result = new HashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++count > 4096) throw new LegacyFailure(Code.CAPACITY);
                String name = entry.getName();
                canonical(name);
                if (result.containsKey(name)) throw new LegacyFailure(Code.ARCHIVE_CONTRACT);
                if (entry.getSize() > MEMBER_MAXIMUM) throw new LegacyFailure(Code.CAPACITY);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] block = new byte[32768];
                int read;
                while ((read = archive.read(block)) != -1) {
                    total += read;
                    if (total > inflatedMaximum || (long) output.size() + read > MEMBER_MAXIMUM) throw new LegacyFailure(Code.CAPACITY);
                    output.write(block,0,read);
                }
                if (entry.isDirectory() && output.size() != 0) throw new LegacyFailure(Code.ARCHIVE_CONTRACT);
                result.put(name,output.toByteArray());
            }
        } catch (IOException failure) { throw new LegacyFailure(Code.ARCHIVE_CONTRACT); }
        return Map.copyOf(result);
    }
    static void canonical(String name) {
        if (name == null || name.isEmpty() || name.length() > 512 || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            throw new LegacyFailure(Code.ARCHIVE_CONTRACT);
        }
        String plain = name.endsWith("/") ? name.substring(0,name.length()-1) : name;
        for (String part : plain.split("/",-1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new LegacyFailure(Code.ARCHIVE_CONTRACT);
    }
    static byte[] required(Map<String,byte[]> entries, String name) {
        byte[] value = entries.get(name);
        if (value == null || value.length == 0) throw new LegacyFailure(Code.RESOURCE_MISSING);
        return value;
    }
}
