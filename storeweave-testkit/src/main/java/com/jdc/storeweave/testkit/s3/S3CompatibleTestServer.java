package com.jdc.storeweave.testkit.s3;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal path-style S3 HTTP implementation used only by provider tests. */
public final class S3CompatibleTestServer implements AutoCloseable {

    private static final String XMLNS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private final Map<String, Map<String, StoredObject>> buckets = new ConcurrentHashMap<>();
    private final Map<String, UploadSession> uploads = new ConcurrentHashMap<>();
    private final Map<String, String> lifecycleConfigurations = new ConcurrentHashMap<>();
    private final Map<String, String> notificationConfigurations = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpServer server;

    public S3CompatibleTestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start S3 test server", exception);
        }
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public String notificationConfiguration(String bucket) {
        return notificationConfigurations.get(bucket);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            RequestPath path = requestPath(exchange.getRequestURI());
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String method = exchange.getRequestMethod();

            if (path.bucket() == null) {
                if ("GET".equals(method)) {
                    listBuckets(exchange);
                } else {
                    error(exchange, 404, "NoSuchBucket", "Bucket was not specified");
                }
                return;
            }
            if (path.key() == null) {
                handleBucket(exchange, method, path.bucket(), query);
                return;
            }
            handleObject(exchange, method, path, query);
        } catch (Exception exception) {
            error(exchange, 500, "InternalError", exception.getMessage());
        } finally {
            exchange.close();
        }
    }

    private void handleBucket(
            HttpExchange exchange,
            String method,
            String bucket,
            Map<String, String> query) throws IOException {
        if (query.containsKey("lifecycle")) {
            handleLifecycle(exchange, method, bucket);
            return;
        }
        if (query.containsKey("notification")) {
            handleNotification(exchange, method, bucket);
            return;
        }
        if ("HEAD".equals(method)) {
            if (buckets.containsKey(bucket)) {
                empty(exchange, 200);
            } else {
                error(exchange, 404, "NoSuchBucket", bucket);
            }
            return;
        }
        if ("PUT".equals(method)) {
            buckets.computeIfAbsent(bucket, ignored -> new ConcurrentHashMap<>());
            empty(exchange, 200);
            return;
        }
        if ("DELETE".equals(method)) {
            Map<String, StoredObject> objects = buckets.get(bucket);
            if (objects == null) {
                error(exchange, 404, "NoSuchBucket", bucket);
            } else if (!objects.isEmpty()) {
                error(exchange, 409, "BucketNotEmpty", bucket);
            } else {
                buckets.remove(bucket);
                lifecycleConfigurations.remove(bucket);
                notificationConfigurations.remove(bucket);
                empty(exchange, 204);
            }
            return;
        }
        if ("GET".equals(method) && "2".equals(query.get("list-type"))) {
            listObjects(exchange, bucket, query);
            return;
        }
        error(exchange, 405, "MethodNotAllowed", method);
    }

    private void handleLifecycle(HttpExchange exchange, String method, String bucket) throws IOException {
        if (!buckets.containsKey(bucket)) {
            error(exchange, 404, "NoSuchBucket", bucket);
            return;
        }
        if ("PUT".equals(method)) {
            lifecycleConfigurations.put(bucket, new String(requestContent(exchange), StandardCharsets.UTF_8));
            empty(exchange, 200);
        } else if ("GET".equals(method)) {
            String configuration = lifecycleConfigurations.get(bucket);
            if (configuration == null) {
                error(exchange, 404, "NoSuchLifecycleConfiguration", bucket);
            } else {
                xml(exchange, 200, configuration);
            }
        } else if ("DELETE".equals(method)) {
            lifecycleConfigurations.remove(bucket);
            empty(exchange, 204);
        } else {
            error(exchange, 405, "MethodNotAllowed", method);
        }
    }

    private void handleNotification(HttpExchange exchange, String method, String bucket) throws IOException {
        if (!buckets.containsKey(bucket)) {
            error(exchange, 404, "NoSuchBucket", bucket);
            return;
        }
        if ("PUT".equals(method)) {
            String configuration = new String(requestContent(exchange), StandardCharsets.UTF_8);
            if (configuration.contains("QueueConfiguration")) {
                notificationConfigurations.put(bucket, configuration);
            } else {
                notificationConfigurations.remove(bucket);
            }
            empty(exchange, 200);
        } else if ("DELETE".equals(method)) {
            notificationConfigurations.remove(bucket);
            empty(exchange, 204);
        } else {
            error(exchange, 405, "MethodNotAllowed", method);
        }
    }

    private void handleObject(
            HttpExchange exchange,
            String method,
            RequestPath path,
            Map<String, String> query) throws IOException {
        Map<String, StoredObject> objects = buckets.get(path.bucket());
        if (objects == null) {
            error(exchange, 404, "NoSuchBucket", path.bucket());
            return;
        }

        String uploadId = query.get("uploadId");
        if ("POST".equals(method) && query.containsKey("uploads")) {
            initiateMultipart(exchange, path);
        } else if ("PUT".equals(method) && uploadId != null && query.containsKey("partNumber")) {
            uploadPart(exchange, uploadId, Integer.parseInt(query.get("partNumber")));
        } else if ("GET".equals(method) && uploadId != null) {
            listParts(exchange, uploadId);
        } else if ("POST".equals(method) && uploadId != null) {
            completeMultipart(exchange, uploadId);
        } else if ("DELETE".equals(method) && uploadId != null) {
            uploads.remove(uploadId);
            empty(exchange, 204);
        } else if ("PUT".equals(method)) {
            putObject(exchange, objects, path.key());
        } else if ("HEAD".equals(method)) {
            headObject(exchange, objects.get(path.key()), path.key());
        } else if ("GET".equals(method)) {
            getObject(exchange, objects.get(path.key()), path.key());
        } else if ("DELETE".equals(method)) {
            objects.remove(path.key());
            empty(exchange, 204);
        } else {
            error(exchange, 405, "MethodNotAllowed", method);
        }
    }

    private void putObject(
            HttpExchange exchange,
            Map<String, StoredObject> objects,
            String key) throws IOException {
        byte[] content = requestContent(exchange);
        String eTag = digest(content);
        Map<String, String> metadata = requestMetadata(exchange);
        StoredObject object = new StoredObject(
                content,
                exchange.getRequestHeaders().getFirst("Content-Type"),
                Map.copyOf(metadata),
                Instant.now(),
                eTag);
        objects.put(key, object);
        exchange.getResponseHeaders().set("ETag", quote(eTag));
        empty(exchange, 200);
    }

    private void headObject(HttpExchange exchange, StoredObject object, String key) throws IOException {
        if (object == null) {
            error(exchange, 404, "NoSuchKey", key);
            return;
        }
        objectHeaders(exchange.getResponseHeaders(), object);
        empty(exchange, 200);
    }

    private void getObject(HttpExchange exchange, StoredObject object, String key) throws IOException {
        if (object == null) {
            error(exchange, 404, "NoSuchKey", key);
            return;
        }
        objectHeaders(exchange.getResponseHeaders(), object);
        bytes(exchange, 200, object.content());
    }

    private void objectHeaders(Headers headers, StoredObject object) {
        headers.set("Content-Type", object.contentType() == null
                ? "application/octet-stream"
                : object.contentType());
        headers.set("ETag", quote(object.eTag()));
        headers.set("Content-Length", Integer.toString(object.content().length));
        headers.set("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME
                .format(object.lastModified().atZone(ZoneOffset.UTC)));
        object.metadata().forEach((name, value) -> headers.set("x-amz-meta-" + name, value));
    }

    private void listBuckets(HttpExchange exchange) throws IOException {
        StringBuilder xml = new StringBuilder()
                .append("<ListAllMyBucketsResult xmlns=\"").append(XMLNS).append("\"><Buckets>");
        buckets.keySet().stream().sorted().forEach(bucket -> xml
                .append("<Bucket><Name>").append(escape(bucket)).append("</Name>")
                .append("<CreationDate>2026-08-20T00:00:00.000Z</CreationDate></Bucket>"));
        xml.append("</Buckets></ListAllMyBucketsResult>");
        xml(exchange, 200, xml.toString());
    }

    private void listObjects(
            HttpExchange exchange,
            String bucket,
            Map<String, String> query) throws IOException {
        Map<String, StoredObject> objects = buckets.get(bucket);
        if (objects == null) {
            error(exchange, 404, "NoSuchBucket", bucket);
            return;
        }
        String prefix = query.getOrDefault("prefix", "");
        String token = query.get("continuation-token");
        if (token == null) {
            token = query.get("start-after");
        }
        String pageToken = token;
        int maxKeys = Integer.parseInt(query.getOrDefault("max-keys", "1000"));
        List<Map.Entry<String, StoredObject>> matches = objects.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .filter(entry -> pageToken == null || entry.getKey().compareTo(pageToken) > 0)
                .sorted(Map.Entry.comparingByKey())
                .limit((long) maxKeys + 1)
                .toList();
        boolean truncated = matches.size() > maxKeys;
        List<Map.Entry<String, StoredObject>> page =
                truncated ? matches.subList(0, maxKeys) : matches;
        String nextToken = truncated ? page.get(page.size() - 1).getKey() : null;

        StringBuilder xml = new StringBuilder()
                .append("<ListBucketResult xmlns=\"").append(XMLNS).append("\">")
                .append("<Name>").append(escape(bucket)).append("</Name>")
                .append("<Prefix>").append(escape(prefix)).append("</Prefix>")
                .append("<KeyCount>").append(page.size()).append("</KeyCount>")
                .append("<MaxKeys>").append(maxKeys).append("</MaxKeys>")
                .append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (nextToken != null) {
            xml.append("<NextContinuationToken>")
                    .append(escape(nextToken))
                    .append("</NextContinuationToken>");
        }
        page.forEach(entry -> xml
                .append("<Contents><Key>").append(escape(entry.getKey())).append("</Key>")
                .append("<LastModified>")
                .append(DateTimeFormatter.ISO_INSTANT.format(
                        entry.getValue().lastModified().truncatedTo(ChronoUnit.MILLIS)))
                .append("</LastModified>")
                .append("<ETag>").append(escape(quote(entry.getValue().eTag()))).append("</ETag>")
                .append("<Size>").append(entry.getValue().content().length).append("</Size>")
                .append("<StorageClass>STANDARD</StorageClass></Contents>"));
        xml.append("</ListBucketResult>");
        xml(exchange, 200, xml.toString());
    }

    private void initiateMultipart(HttpExchange exchange, RequestPath path) throws IOException {
        String uploadId = UUID.randomUUID().toString();
        uploads.put(uploadId, new UploadSession(
                path.bucket(),
                path.key(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                requestMetadata(exchange)));
        xml(exchange, 200, "<InitiateMultipartUploadResult xmlns=\"" + XMLNS + "\">"
                + "<Bucket>" + escape(path.bucket()) + "</Bucket>"
                + "<Key>" + escape(path.key()) + "</Key>"
                + "<UploadId>" + uploadId + "</UploadId>"
                + "</InitiateMultipartUploadResult>");
    }

    private void uploadPart(HttpExchange exchange, String uploadId, int partNumber) throws IOException {
        UploadSession upload = uploads.get(uploadId);
        if (upload == null) {
            error(exchange, 404, "NoSuchUpload", uploadId);
            return;
        }
        byte[] content = requestContent(exchange);
        String eTag = digest(content);
        upload.parts().put(partNumber, content);
        upload.eTags().put(partNumber, eTag);
        exchange.getResponseHeaders().set("ETag", quote(eTag));
        empty(exchange, 200);
    }

    private void listParts(HttpExchange exchange, String uploadId) throws IOException {
        UploadSession upload = uploads.get(uploadId);
        if (upload == null) {
            error(exchange, 404, "NoSuchUpload", uploadId);
            return;
        }
        StringBuilder xml = new StringBuilder()
                .append("<ListPartsResult xmlns=\"").append(XMLNS).append("\">")
                .append("<Bucket>").append(escape(upload.bucket())).append("</Bucket>")
                .append("<Key>").append(escape(upload.key())).append("</Key>")
                .append("<UploadId>").append(uploadId).append("</UploadId>")
                .append("<Initiator><ID>storeweave</ID><DisplayName>storeweave</DisplayName></Initiator>")
                .append("<Owner><ID>storeweave</ID><DisplayName>storeweave</DisplayName></Owner>")
                .append("<StorageClass>STANDARD</StorageClass>")
                .append("<PartNumberMarker>0</PartNumberMarker>")
                .append("<NextPartNumberMarker>0</NextPartNumberMarker>")
                .append("<MaxParts>1000</MaxParts>")
                .append("<IsTruncated>false</IsTruncated>");
        upload.parts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> xml
                        .append("<Part><PartNumber>").append(entry.getKey()).append("</PartNumber>")
                        .append("<LastModified>2026-08-20T00:00:00.000Z</LastModified>")
                        .append("<ETag>").append(escape(quote(upload.eTags().get(entry.getKey())))).append("</ETag>")
                        .append("<Size>").append(entry.getValue().length).append("</Size></Part>"));
        xml.append("</ListPartsResult>");
        xml(exchange, 200, xml.toString());
    }

    private void completeMultipart(HttpExchange exchange, String uploadId) throws IOException {
        exchange.getRequestBody().readAllBytes();
        UploadSession upload = uploads.remove(uploadId);
        if (upload == null) {
            error(exchange, 404, "NoSuchUpload", uploadId);
            return;
        }
        List<byte[]> ordered = upload.parts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        int length = ordered.stream().mapToInt(bytes -> bytes.length).sum();
        byte[] content = new byte[length];
        int offset = 0;
        for (byte[] part : ordered) {
            System.arraycopy(part, 0, content, offset, part.length);
            offset += part.length;
        }
        String eTag = digest(content);
        buckets.get(upload.bucket()).put(
                upload.key(),
                new StoredObject(content, upload.contentType(), upload.metadata(), Instant.now(), eTag));
        xml(exchange, 200, "<CompleteMultipartUploadResult xmlns=\"" + XMLNS + "\">"
                + "<Location>" + endpoint() + "/" + escape(upload.bucket()) + "/" + escape(upload.key()) + "</Location>"
                + "<Bucket>" + escape(upload.bucket()) + "</Bucket>"
                + "<Key>" + escape(upload.key()) + "</Key>"
                + "<ETag>" + escape(quote(eTag)) + "</ETag>"
                + "</CompleteMultipartUploadResult>");
    }

    private static RequestPath requestPath(URI uri) {
        String raw = uri.getRawPath();
        if (raw == null || raw.equals("/")) {
            return new RequestPath(null, null);
        }
        String path = raw.startsWith("/") ? raw.substring(1) : raw;
        int separator = path.indexOf('/');
        if (separator < 0) {
            return new RequestPath(decode(path), null);
        }
        return new RequestPath(decode(path.substring(0, separator)), decode(path.substring(separator + 1)));
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String name = decode(separator < 0 ? pair : pair.substring(0, separator));
            String value = separator < 0 ? "" : decode(pair.substring(separator + 1));
            values.put(name, value);
        }
        return values;
    }

    private static void xml(HttpExchange exchange, int status, String value) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        bytes(exchange, status, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod())) {
            empty(exchange, status);
            return;
        }
        xml(exchange, status, "<Error><Code>" + escape(code) + "</Code><Message>"
                + escape(message == null ? code : message) + "</Message></Error>");
    }

    private static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static void bytes(HttpExchange exchange, int status, byte[] value) throws IOException {
        exchange.sendResponseHeaders(status, value.length);
        exchange.getResponseBody().write(value);
    }

    private static byte[] requestContent(HttpExchange exchange) throws IOException {
        byte[] content = exchange.getRequestBody().readAllBytes();
        String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        if (encoding == null || !encoding.toLowerCase(Locale.ROOT).contains("aws-chunked")) {
            return content;
        }

        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < content.length) {
            int lineEnd = indexOfCrlf(content, offset);
            if (lineEnd < 0) {
                throw new IOException("Invalid aws-chunked request body");
            }
            String header = new String(content, offset, lineEnd - offset, StandardCharsets.US_ASCII);
            int extension = header.indexOf(';');
            String sizeValue = extension < 0 ? header : header.substring(0, extension);
            int chunkSize = Integer.parseInt(sizeValue, 16);
            offset = lineEnd + 2;
            if (chunkSize == 0) {
                break;
            }
            if (offset + chunkSize > content.length) {
                throw new IOException("Truncated aws-chunked request body");
            }
            decoded.write(content, offset, chunkSize);
            offset += chunkSize;
            if (offset + 1 >= content.length || content[offset] != '\r' || content[offset + 1] != '\n') {
                throw new IOException("Invalid aws-chunked chunk terminator");
            }
            offset += 2;
        }
        return decoded.toByteArray();
    }

    private static Map<String, String> requestMetadata(HttpExchange exchange) {
        Map<String, String> metadata = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (name.toLowerCase(Locale.ROOT).startsWith("x-amz-meta-") && !values.isEmpty()) {
                metadata.put(name.substring("x-amz-meta-".length()), values.get(0));
            }
        });
        return Map.copyOf(metadata);
    }

    private static int indexOfCrlf(byte[] value, int fromIndex) {
        for (int index = fromIndex; index + 1 < value.length; index++) {
            if (value[index] == '\r' && value[index + 1] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record RequestPath(String bucket, String key) {
    }

    private record StoredObject(
            byte[] content,
            String contentType,
            Map<String, String> metadata,
            Instant lastModified,
            String eTag) {
    }

    private static final class UploadSession {
        private final String bucket;
        private final String key;
        private final String contentType;
        private final Map<String, String> metadata;
        private final Map<Integer, byte[]> parts = new ConcurrentHashMap<>();
        private final Map<Integer, String> eTags = new ConcurrentHashMap<>();

        private UploadSession(String bucket, String key, String contentType, Map<String, String> metadata) {
            this.bucket = bucket;
            this.key = key;
            this.contentType = contentType;
            this.metadata = metadata;
        }

        private String bucket() {
            return bucket;
        }

        private String key() {
            return key;
        }

        private String contentType() {
            return contentType;
        }

        private Map<String, String> metadata() {
            return metadata;
        }

        private Map<Integer, byte[]> parts() {
            return parts;
        }

        private Map<Integer, String> eTags() {
            return eTags;
        }
    }
}
