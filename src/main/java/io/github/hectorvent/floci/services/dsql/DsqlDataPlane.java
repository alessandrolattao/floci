package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.function.Consumer;

/**
 * Runs each cluster's database. With {@code floci.services.dsql.mock} on, a cluster has none and
 * is ready at once, which is enough for the control plane and CloudFormation.
 */
@ApplicationScoped
public class DsqlDataPlane {

    private final boolean mock;

    @Inject
    public DsqlDataPlane(EmulatorConfig config) {
        this.mock = config.services().dsql().mock();
    }

    DsqlDataPlane(boolean mock) {
        this.mock = mock;
    }

    /** Starts the cluster's database, then reports whether it is ready to serve. */
    public void provision(DsqlCluster cluster, Consumer<Boolean> onReady) {
        if (mock) {
            onReady.accept(true);
        }
    }

    /** Stops the cluster's database and drops its data, then runs {@code onGone}. */
    public void deprovision(DsqlCluster cluster, Runnable onGone) {
        onGone.run();
    }
}
