package com.otilm.np.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Holds what this connector exchanges to goldens recorded on the Spring Boot 3.5 line: the REST JSON core reads, and
 * the webhook body a receiver gets, byte for byte. Record with {@code -Dwire.golden.write=true} on the 3.5 line only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:hsqldb:mem:wireGolden;sql.syntax_pgs=true")
class WireGoldenTest {

    private static final Path GOLDENS = Path.of("src/test/resources/wire");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID_PATTERN = Pattern
            .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern EPOCH_MILLIS = Pattern.compile("\"timestamp\":[1-9]\\d*");
    /** The parser's own account of a body it could not read, which each JSON library words differently. */
    private static final Pattern PARSER_MESSAGE = Pattern
            .compile("\"(detail|error)\":\"(?:[^\"\\\\]|\\\\.)*Source: REDACTED(?:[^\"\\\\]|\\\\.)*\"");
    /** Lists the connector builds from hash maps, so their order differs from run to run. */
    private static final Set<String> UNORDERED = Set.of("endPoints");
    private static final String UNKNOWN_UUID = "00000000-0000-4000-8000-000000000000";
    private static final String NOTIFICATIONS = "/v1/notificationProvider/notifications";
    private static final String ATTRIBUTES = "/v1/notificationProvider/WEBHOOK/attributes";
    /** Core fills the path from the selected content type's data, which is the enum name. */
    private static final String TEMPLATE_CALLBACK = "/v1/notificationProvider/callbacks/template/%s/attributes";
    private static final String JSON_TEMPLATE = """
            {"event":"${event}","resource":"${resource}","certificate":"${notificationData.subjectDn?json_string}",\
            "status":"${notificationData.newStatus}","daysLeft":${notificationData.daysLeft?c},\
            "weight":${notificationData.weight?c},\
            "recipients":[<#list recipients as recipient>"${recipient.email}"<#sep>,</#sep></#list>],\
            "subject":"${objectData.subject.name}","unicode":"${notificationData.nested.unicode?json_string}"}""";
    private static final String XML_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <notification event="${event}" resource="${resource}">
              <certificate status="${notificationData.newStatus}">${notificationData.subjectDn?xml}</certificate>
              <escapes>${notificationData.nested.escapes?xml}</escapes>
            <#list recipients as recipient>
              <recipient email="${recipient.email?xml}">${recipient.name?xml}</recipient>
            </#list>
            </notification>
            """;

    private static final BlockingQueue<Delivery> DELIVERIES = new LinkedBlockingQueue<>();
    private static HttpServer receiver;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Set<String> stableUuids = new HashSet<>(Set.of(UNKNOWN_UUID));

    @Value("${local.server.port}")
    private int port;

    @BeforeAll
    static void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext("/", exchange -> {
            DELIVERIES
                    .add(new Delivery(exchange.getRequestMethod(),
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop(0);
    }

    /** The definitions' UUIDs are constants, so they stay in the goldens; every other UUID is generated per run. */
    @BeforeEach
    void collectStableUuids() throws Exception {
        stableUuids.addAll(uuidsIn(get(ATTRIBUTES).body()));
        for (String contentType : List.of("RAW_JSON", "JSON", "XML")) {
            stableUuids.addAll(uuidsIn(get(TEMPLATE_CALLBACK.formatted(contentType)).body()));
        }
        stableUuids.addAll(uuidsIn(notifyRequest()));
    }

    @AfterEach
    void removeInstances() throws Exception {
        for (JsonNode instance : JSON.readTree(get(NOTIFICATIONS).body())) {
            delete(NOTIFICATIONS + "/" + instance.get("uuid").asText());
        }
        DELIVERIES.clear();
    }

    @Test
    void infoAndHealth() throws Exception {
        assertGolden("v1-info", get("/v1"));
        assertGolden("v1-health", get("/v1/health"));
    }

    @Test
    void attributeDefinitions() throws Exception {
        assertGolden("v1-attributes", get(ATTRIBUTES));
        assertGolden("v1-attributes-unsupported-kind", get("/v1/notificationProvider/EMAIL/attributes"));
        assertGolden("v1-attributes-validate", post(ATTRIBUTES + "/validate",
                attributes("https://example.com/hook", "json", "JSON", "json", JSON_TEMPLATE)));
        assertGolden("v1-attributes-validate-invalid",
                post(ATTRIBUTES + "/validate", attributes("ftp://example.com", "raw_json", "RAW_JSON", null, null)));
        assertGolden("v1-mapping-attributes", get(ATTRIBUTES + "/mapping"));
        assertGolden("v1-template-callback-raw-json", get(TEMPLATE_CALLBACK.formatted("RAW_JSON")));
        assertGolden("v1-template-callback-json", get(TEMPLATE_CALLBACK.formatted("JSON")));
        assertGolden("v1-template-callback-xml", get(TEMPLATE_CALLBACK.formatted("XML")));
        assertGolden("v1-template-callback-unknown", get(TEMPLATE_CALLBACK.formatted("YAML")));
    }

    @Test
    void instanceLifecycle() throws Exception {
        HttpResponse<String> created = post(NOTIFICATIONS,
                instance("wire-lifecycle", "raw_json", "RAW_JSON", null, null));
        assertGolden("v1-instance-create", created);
        String instance = NOTIFICATIONS + "/" + JSON.readTree(created.body()).get("uuid").asText();
        assertGolden("v1-instance-get", get(instance));
        assertGolden("v1-instances", get(NOTIFICATIONS));
        assertGolden("v1-instance-update",
                put(instance, instance("wire-lifecycle", "json", "JSON", "json", JSON_TEMPLATE)));
        assertGolden("v1-instance-create-duplicate",
                post(NOTIFICATIONS, instance("wire-lifecycle", "raw_json", "RAW_JSON", null, null)));
        assertGolden("v1-instance-delete", delete(instance));
        assertGolden("v1-instance-not-found", get(instance));
    }

    @Test
    void rawJsonWebhookForwardsTheRequest() throws Exception {
        String instance = create("wire-raw-json", "raw_json", "RAW_JSON", null, null);

        assertGolden("v1-notify", post(instance + "/notify", notifyRequest()));

        assertDelivery("raw-json.json", "application/json");
    }

    @Test
    void jsonTemplateWebhookRendersTheTemplate() throws Exception {
        String instance = create("wire-json-template", "json", "JSON", "json", JSON_TEMPLATE);

        post(instance + "/notify", notifyRequest());

        assertDelivery("json-template.json", "application/json");
    }

    @Test
    void xmlTemplateWebhookRendersTheTemplate() throws Exception {
        String instance = create("wire-xml-template", "xml", "XML", "xml", XML_TEMPLATE);

        post(instance + "/notify", notifyRequest());

        assertDelivery("xml-template.xml", "application/xml");
    }

    @Test
    void requestsThatCannotBeServed() throws Exception {
        assertGolden("v1-unreadable-body", post(NOTIFICATIONS, "{"));
        assertGolden("v1-invalid-format", post(NOTIFICATIONS, """
                {"name":"wire-invalid","kind":"WEBHOOK","attributes":[{"uuid":"not-a-uuid","name":"data_webhookUrl",
                 "contentType":"string","content":[{"data":"https://example.com/hook"}],"version":"v2"}]}"""));
        assertGolden("v1-notify-unknown-instance",
                post(NOTIFICATIONS + "/" + UNKNOWN_UUID + "/notify", notifyRequest()));
    }

    private static String notifyRequest() throws IOException {
        return Files.readString(GOLDENS.resolve("notify-request.json"));
    }

    private String create(String name, String reference, String contentType, String language, String template)
            throws Exception {
        HttpResponse<String> created = post(NOTIFICATIONS, instance(name, reference, contentType, language, template));
        assertEquals(200, created.statusCode(), created.body());
        return NOTIFICATIONS + "/" + JSON.readTree(created.body()).get("uuid").asText();
    }

    private String instance(String name, String reference, String contentType, String language, String template) {
        return """
                {"name":"%s","kind":"WEBHOOK","attributes":%s}"""
                .formatted(name, attributes(receiverUrl(), reference, contentType, language, template));
    }

    /** Instance attributes as core writes them: the content type's wire name as reference, its enum name as data. */
    private static String attributes(String url, String reference, String contentType, String language,
            String template) {
        List<String> attributes = new ArrayList<>(List
                .of(string("3b8a11b3-a59d-427c-9491-56c8ce27cee7", "data_webhookUrl", url, url),
                        string("b104d74d-8a54-4aa3-9e00-9c535f8bb80c", "data_contentType", reference, contentType)));
        if (template != null) {
            attributes
                    .add("""
                            {"name":"data_contentTemplate_%s","contentType":"codeblock",
                             "content":[{"reference":null,"data":{"language":"%s","code":"%s"}}],"version":"v2"}"""
                            .formatted(reference, language,
                                    Base64.getEncoder().encodeToString(template.getBytes(StandardCharsets.UTF_8))));
        }
        return "[" + String.join(",", attributes) + "]";
    }

    private static String string(String uuid, String name, String reference, String data) {
        return """
                {"uuid":"%s","name":"%s","contentType":"string","content":[{"reference":"%s","data":"%s"}],\
                "version":"v2"}""".formatted(uuid, name, reference, data);
    }

    private static String receiverUrl() {
        return "http://localhost:" + receiver.getAddress().getPort() + "/hook";
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        return send(HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> put(String path, String body) throws IOException, InterruptedException {
        return send(HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(uri(path)).DELETE());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static Set<String> uuidsIn(String text) {
        Set<String> uuids = new HashSet<>();
        Matcher matcher = UUID_PATTERN.matcher(text);
        while (matcher.find()) {
            uuids.add(matcher.group());
        }
        return uuids;
    }

    /** Masks what differs between runs and parsers: the receiver's port, the time and generated UUIDs. */
    private String normalized(String body) {
        String masked = body.replace(receiverUrl(), "<receiver>");
        masked = EPOCH_MILLIS.matcher(masked).replaceAll("\"timestamp\":\"<millis>\"");
        masked = PARSER_MESSAGE.matcher(masked).replaceAll("\"$1\":\"<parser message>\"");
        return UUID_PATTERN
                .matcher(masked)
                .replaceAll(match -> stableUuids.contains(match.group()) ? match.group() : "<uuid>");
    }

    /** Sorts every object's properties, since their order is not part of the REST contract and Jackson 3 changes it. */
    private static JsonNode canonical(JsonNode node) {
        if (node.isArray()) {
            return JSON.createArrayNode().addAll(node.valueStream().map(WireGoldenTest::canonical).toList());
        }
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            node
                    .properties()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(field -> sorted.set(field.getKey(), canonical(field.getValue())));
            UNORDERED.stream().filter(sorted::has).forEach(name -> {
                List<JsonNode> elements = new ArrayList<>(sorted.get(name).valueStream().toList());
                elements.sort(Comparator.comparing(JsonNode::toString));
                sorted.set(name, JSON.createArrayNode().addAll(elements));
            });
            return sorted;
        }
        return node;
    }

    private void assertGolden(String name, HttpResponse<String> response) throws IOException {
        ObjectNode actual = JSON.createObjectNode();
        actual.put("status", response.statusCode());
        actual.put("contentType", response.headers().firstValue("Content-Type").map(t -> t.split(";")[0]).orElse(""));
        String body = normalized(response.body());
        actual.set("body", body.isEmpty() ? null : canonical(JSON.readTree(body)));

        Path golden = GOLDENS.resolve(name + ".json");
        if (Boolean.getBoolean("wire.golden.write")) {
            Files.writeString(golden, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
        }
        JsonNode expected = JSON.readTree(Files.readString(golden));
        assertEquals(expected, actual, "Wire output drifted from " + golden + ": " + actual);
    }

    /** Receivers parse or verify the body as sent, so it is compared byte for byte, property order included. */
    private static void assertDelivery(String name, String contentType) throws Exception {
        Delivery delivery = DELIVERIES.poll(10, TimeUnit.SECONDS);
        assertNotNull(delivery, "the webhook never reached the receiver");
        assertEquals("POST", delivery.method());
        assertEquals(contentType, delivery.contentType());

        Path golden = GOLDENS.resolve("webhook").resolve(name);
        if (Boolean.getBoolean("wire.golden.write")) {
            Files.write(golden, delivery.body());
        }
        assertEquals(Files.readString(golden), new String(delivery.body(), StandardCharsets.UTF_8),
                "Webhook body drifted from " + golden);
    }

    private record Delivery(String method, String contentType, byte[] body) {
    }
}
