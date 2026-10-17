package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dsql.dataplane.DsqlDatabases;
import io.github.hectorvent.floci.services.dsql.dataplane.DsqlEndpoint;
import io.github.hectorvent.floci.services.dsql.model.DsqlCluster;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runs each cluster's database and serves it on the DSQL endpoint. A cluster's database starts in
 * the background, and the cluster becomes ACTIVE once it answers, as on AWS. With
 * {@code floci.services.dsql.mock} on, a cluster has none and is ready at once, which is enough
 * for the control plane and CloudFormation.
 */
@ApplicationScoped
public class DsqlDataPlane {

    private static final Logger LOG = Logger.getLogger(DsqlDataPlane.class);

    private final boolean mock;
    private final boolean memoryStorage;
    private final DsqlDatabases databases;
    private final DsqlEndpoint endpoint;
    private final Map<String, DsqlCluster> running = new ConcurrentHashMap<>();
    private final Set<String> wanted = ConcurrentHashMap.newKeySet();

    @Inject
    public DsqlDataPlane(EmulatorConfig config, DsqlDatabases databases, DsqlEndpoint endpoint) {
        this.mock = config.services().dsql().mock();
        this.memoryStorage = "memory".equals(config.storage().mode());
        this.databases = databases;
        this.endpoint = endpoint;
    }

    /** Starts the cluster's database, then reports whether it is ready to serve. */
    public void provision(DsqlCluster cluster, Consumer<Boolean> onReady) {
        if (mock) {
            onReady.accept(true);
            return;
        }
        String arn = cluster.getArn();
        wanted.add(arn);
        Thread.ofVirtual().name("dsql-start-" + cluster.getIdentifier()).start(() -> {
            boolean ready = false;
            try {
                DsqlDatabases.Backend backend = databases.start(cluster);
                if (wanted.contains(arn)) {
                    endpoint.register(cluster, backend);
                    running.put(arn, cluster);
                    ready = true;
                } else {
                    // Deleted while it started.
                    databases.stop(cluster, true);
                }
            } catch (Exception e) {
                LOG.warnv(e, "The database of DSQL cluster {0} did not start", cluster.getIdentifier());
            }
            onReady.accept(ready);
        });
    }

    /** Stops the cluster's database and drops its data, then runs {@code onGone}. */
    public void deprovision(DsqlCluster cluster, Runnable onGone) {
        if (mock) {
            onGone.run();
            return;
        }
        wanted.remove(cluster.getArn());
        running.remove(cluster.getArn());
        endpoint.unregister(cluster);
        Thread.ofVirtual().name("dsql-stop-" + cluster.getIdentifier()).start(() -> {
            try {
                databases.stop(cluster, true);
            } finally {
                onGone.run();
            }
        });
    }

    /**
     * Stops every database when Floci stops. Their data stays for the next start, unless storage is
     * in memory, where the clusters themselves do not survive.
     */
    void onStop(@Observes ShutdownEvent ignored) {
        if (mock) {
            return;
        }
        endpoint.stop();
        for (DsqlCluster cluster : running.values()) {
            databases.stop(cluster, memoryStorage);
        }
        running.clear();
    }
}
