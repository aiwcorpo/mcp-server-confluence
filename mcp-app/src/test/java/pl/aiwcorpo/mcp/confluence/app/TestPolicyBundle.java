package pl.aiwcorpo.mcp.confluence.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import pl.aiwcorpo.mcp.platform.test.PolicyRepository;

/**
 * The example bundle of the contracts plus the two rules it lacks for this server: a call permit for the write group
 * of {@code mcp-confluence} (the examples permit only the read group, so {@code create_page} could not be called in
 * tests) and the update rule on {@code Confluence::Space} that the production bundle has
 * ({@code confluence-update-space-in-scope}). Everything else stays as signed in the contracts; the bundle is re-signed with their published test key,
 * which exists for tests only.
 */
final class TestPolicyBundle {

    private static final String CONTRACTS = "mcp-contracts/test-vectors/";
    private static final String REVISION = "20261026130000-b2c3d4e";
    private static final String CALL_UPDATE = """
            @id("confluence-call-update")
            permit (
              principal in UserGroup::"mcp-confluence-write",
              action == Action::"call",
              resource is Tool
            )
            when { resource.server == "mcp-confluence" && resource.risk == "UPDATE" };

            @id("confluence-update-space-in-scope")
            permit (
              principal in UserGroup::"mcp-confluence-write",
              action == Action::"update",
              resource is Confluence::Space
            )
            when { principal in resource.scopes };
            """;

    private TestPolicyBundle() {
    }

    static void publishOn(PolicyRepository repository) {
        try {
            Map<String, byte[]> files = untar(gunzip(resource("bundles/files/policies-" + REVISION + ".tar.gz")));
            files.put("policies/confluence-update.cedar", CALL_UPDATE.getBytes(StandardCharsets.UTF_8));
            byte[] archive = gzip(tar(files));
            repository.publishRaw(REVISION, archive, sign(archive));
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("cannot build the test bundle", ex);
        }
    }

    private static byte[] sign(byte[] archive) throws GeneralSecurityException, IOException {
        String pem = new String(resource("keys/test-policy-bundle.NOT-FOR-PRODUCTION.private.pem"), StandardCharsets.UTF_8)
                .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem))));
        signature.update(archive);
        return signature.sign();
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = TestPolicyBundle.class.getClassLoader().getResourceAsStream(CONTRACTS + path)) {
            if (in == null) {
                throw new IOException(path + " not found in the contracts test vectors");
            }
            return in.readAllBytes();
        }
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    /** Regular files of a ustar archive, which is all a bundle holds. */
    private static Map<String, byte[]> untar(byte[] tar) {
        Map<String, byte[]> files = new TreeMap<>();
        int offset = 0;
        while (offset + 512 <= tar.length && tar[offset] != 0) {
            String name = cString(tar, offset, 100);
            int size = Integer.parseInt(cString(tar, offset + 124, 12).trim(), 8);
            files.put(name, Arrays.copyOfRange(tar, offset + 512, offset + 512 + size));
            offset += 512 + (size + 511) / 512 * 512;
        }
        return files;
    }

    private static String cString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static byte[] tar(Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
            byte[] content = file.getValue();
            byte[] header = new byte[512];
            put(header, 0, file.getKey());
            put(header, 100, "0000644");
            put(header, 108, "0000000");
            put(header, 116, "0000000");
            put(header, 124, String.format("%011o", content.length));
            put(header, 136, "00000000000");
            Arrays.fill(header, 148, 156, (byte) ' ');
            header[156] = '0';
            put(header, 257, "ustar");
            put(header, 263, "00");
            long sum = 0;
            for (byte b : header) {
                sum += b & 0xff;
            }
            put(header, 148, String.format("%06o", sum));
            header[154] = 0;
            header[155] = ' ';
            out.writeBytes(header);
            out.writeBytes(content);
            out.writeBytes(new byte[(512 - content.length % 512) % 512]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }
}
