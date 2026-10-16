package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.dsql.DsqlService;
import io.github.hectorvent.floci.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::DSQL::Cluster}. {@code Ref} is the cluster's
 * identifier, the schema's primary identifier, and every read-only property is an attribute. As
 * CloudFormation's handler does, create waits for the cluster to be ACTIVE.
 */
@ApplicationScoped
public class DsqlCfnProvisioner implements CfnResourceProvisioner {

    private static final Duration READY_TIMEOUT = Duration.ofMinutes(5);
    /** Measured: {@code 2026-10-16T15:49:38.488Z}, milliseconds always written. */
    private static final DateTimeFormatter CREATION_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final DsqlService dsqlService;

    @Inject
    public DsqlCfnProvisioner(DsqlService dsqlService) {
        this.dsqlService = dsqlService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::DSQL::Cluster");
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        Boolean deletionProtection = props != null && props.has("DeletionProtectionEnabled")
                ? Boolean.valueOf(ctx.engine().resolve(props.get("DeletionProtectionEnabled"))) : null;
        String kmsKey = props != null && props.has("KmsEncryptionKey")
                ? ctx.engine().resolve(props.get("KmsEncryptionKey")) : null;
        JsonNode multiRegion = props == null ? null : ctx.engine().resolveNodeOmittingNoValue(props.get("MultiRegionProperties"));
        String witnessRegion = multiRegion != null && multiRegion.hasNonNull("WitnessRegion")
                ? multiRegion.get("WitnessRegion").asText() : null;
        List<String> linkedClusters = multiRegion != null && multiRegion.path("Clusters").isArray()
                ? strings(multiRegion.get("Clusters")) : null;
        Map<String, String> tags = tags(props, ctx);
        String policy = props != null && props.has("PolicyDocument")
                ? policyText(ctx.engine().resolveNode(props.get("PolicyDocument"))) : null;

        DsqlCluster cluster;
        if (ctx.priorPhysicalId() != null) {
            String identifier = ctx.priorPhysicalId();
            dsqlService.updateCluster(region, identifier,
                    deletionProtection == null ? Boolean.TRUE : deletionProtection,
                    kmsKey == null ? "" : kmsKey, witnessRegion, linkedClusters);
            reconcileTags(dsqlService.getCluster(region, identifier), tags);
            DsqlCluster current = dsqlService.getCluster(region, identifier);
            if (policy != null && !policy.equals(current.getPolicy())) {
                dsqlService.putClusterPolicy(region, identifier, policy, null);
            } else if (policy == null && current.getPolicy() != null) {
                dsqlService.deleteClusterPolicy(region, identifier, null);
            }
            cluster = dsqlService.getCluster(region, identifier);
        } else {
            cluster = dsqlService.createCluster(region, deletionProtection, kmsKey, tags, null, witnessRegion,
                    linkedClusters, policy);
            cluster = awaitActive(region, cluster.getIdentifier());
        }

        resource.setPhysicalId(cluster.getIdentifier());
        Map<String, String> attributes = resource.getAttributes();
        attributes.put("Identifier", cluster.getIdentifier());
        attributes.put("ResourceArn", cluster.getArn());
        attributes.put("CreationTime", CREATION_TIME.format(cluster.getCreationTime()));
        attributes.put("Status", cluster.getStatus());
        attributes.put("Endpoint", cluster.getEndpoint());
        attributes.put("VpcEndpoint", dsqlService.clusterVpcEndpoint(region, cluster.getIdentifier()));
        attributes.put("VpcEndpointServiceName", dsqlService.vpcEndpointServiceName(region, cluster.getIdentifier()));
        // Measured: PolicyVersion exists only with a policy, and EncryptionDetails is no attribute.
        if (cluster.getPolicyVersion() != null && cluster.getPolicy() != null) {
            attributes.put("PolicyVersion", cluster.getPolicyVersion());
        } else {
            attributes.remove("PolicyVersion");
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("DSQL cluster", physicalId,
                () -> dsqlService.deleteCluster(region, physicalId), "ResourceNotFoundException");
    }

    private DsqlCluster awaitActive(String region, String identifier) {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        while (true) {
            DsqlCluster cluster = dsqlService.getCluster(region, identifier);
            if ("ACTIVE".equals(cluster.getStatus())) {
                return cluster;
            }
            if ("FAILED".equals(cluster.getStatus()) || Instant.now().isAfter(deadline)) {
                throw new AwsException("InternalFailure", "Cluster " + identifier + " did not become ACTIVE"
                        + " (status " + cluster.getStatus() + ")", 500);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("InternalFailure", "Interrupted while cluster " + identifier
                        + " was being created", 500);
            }
        }
    }

    private void reconcileTags(DsqlCluster cluster, Map<String, String> wanted) {
        List<String> removed = new ArrayList<>();
        for (String key : cluster.getTags().keySet()) {
            if (!wanted.containsKey(key)) {
                removed.add(key);
            }
        }
        if (!removed.isEmpty()) {
            dsqlService.untagResource(cluster.getArn(), removed);
        }
        if (!wanted.isEmpty()) {
            dsqlService.tagResource(cluster.getArn(), wanted);
        }
    }

    private static Map<String, String> tags(JsonNode props, ProvisionContext ctx) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (props == null || !props.has("Tags")) {
            return tags;
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(props.get("Tags"));
        if (resolved != null && resolved.isArray()) {
            for (JsonNode tag : resolved) {
                if (tag.hasNonNull("Key")) {
                    tags.put(tag.get("Key").asText(), tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private static String policyText(JsonNode policy) {
        if (policy == null || policy.isNull() || policy.isMissingNode()) {
            return null;
        }
        return policy.isTextual() ? policy.asText() : policy.toString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }
}
