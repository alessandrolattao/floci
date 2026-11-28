package io.floci.aws.core.common.dns;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.DockerBridgeAddress;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import io.floci.aws.services.route53.Route53DnsRecordSource;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.RRType;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A public hosted zone named like Floci's own suffix, as a CDK app whose local stage domain is
 * {@code localhost.floci.io} creates, leaves the names Floci serves under it resolvable from a
 * container, while the records the zone holds still answer.
 */
@QuarkusTest
class EmulatorSuffixPublicZoneDockerIntegrationTest {

    private static final String SUFFIX = EmbeddedDnsServer.DEFAULT_SUFFIX;
    private static final String REGION = "us-east-1";
    private static final String DSQL_AUTH = "AWS4-HMAC-SHA256 Credential=000000000000/20261128/" + REGION
            + "/dsql/aws4_request, SignedHeaders=host, Signature=abc";

    @Inject
    Route53DnsRecordSource records;
    @Inject
    DockerClient docker;
    @Inject
    DockerBridgeAddress bridge;
    @Inject
    ImageCacheService images;
    @Inject
    Vertx vertx;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void flociNamesUnderAPublicZoneNamedLikeTheSuffixResolveFromAContainer() throws Exception {
        assumeTrue(DockerAvailability.isAvailable(), "Docker is required");
        String address = bridge.resolve().orElse(null);
        assumeTrue(address != null, "A local Docker bridge is required");
        String cluster = given().header("Authorization", DSQL_AUTH).contentType("application/json")
                .body(Map.of("deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).extract().path("identifier");
        cleanup.register(() -> given().header("Authorization", DSQL_AUTH)
                .delete("/cluster/" + cluster).then().statusCode(200));
        String dsqlEndpoint = given().header("Authorization", DSQL_AUTH)
                .get("/cluster/" + cluster).then().statusCode(200).extract().path("endpoint");
        assertEquals(cluster + ".dsql." + REGION + "." + SUFFIX, dsqlEndpoint);

        Route53Client route53 = AwsTestClients.create(Route53Client.builder(), "test", Region.US_EAST_1);
        cleanup.register(route53::close);
        String zone = route53.createHostedZone(request -> request.name(SUFFIX)
                .callerReference(UUID.randomUUID().toString())).hostedZone().id();
        cleanup.register(() -> route53.deleteHostedZone(request -> request.id(zone)));
        String plain = "plain." + SUFFIX;
        changeAddress(route53, zone, ChangeAction.CREATE, plain);
        cleanup.register(() -> changeAddress(route53, zone, ChangeAction.DELETE, plain));

        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), List.of(records));
        DatagramSocket socket = dns.bind(vertx, address, 0, false).toCompletionStage().toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        cleanup.register(() -> socket.close().toCompletionStage().toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join());
        String guest = startGuest();
        int port = socket.localAddress().port();

        for (String name : List.of(dsqlEndpoint, "uploads.s3." + SUFFIX, "sts." + REGION + "." + SUFFIX)) {
            assertAddress(DnsTestQuery.queryGuest(docker, guest, name, address, port), address, name);
        }
        assertAddress(DnsTestQuery.queryGuest(docker, guest, plain, address, port), "192.0.2.10", plain);
    }

    private static void changeAddress(Route53Client route53, String zone, ChangeAction action, String name) {
        route53.changeResourceRecordSets(request -> request.hostedZoneId(zone).changeBatch(batch -> batch
                .changes(change -> change.action(action).resourceRecordSet(record -> record
                        .name(name).type(RRType.A).ttl(60L)
                        .resourceRecords(value -> value.value("192.0.2.10"))))));
    }

    private String startGuest() {
        images.ensureImageExists(TestImages.PYTHON_ALPINE);
        String id = docker.createContainerCmd(TestImages.PYTHON_ALPINE).withCmd("sleep", "300").exec().getId();
        cleanup.register(() -> docker.removeContainerCmd(id).withForce(true).exec());
        docker.startContainerCmd(id).exec();
        return id;
    }

    private static void assertAddress(byte[] response, String address, String name) {
        ByteBuffer packet = ByteBuffer.wrap(response);
        assertEquals(0, packet.getShort(2) & 15, name);
        assertEquals(1, packet.getShort(6), name);
        assertArrayEquals(new DnsRecord.Address(1, address).data(),
                Arrays.copyOfRange(response, response.length - 4, response.length), name);
    }
}
