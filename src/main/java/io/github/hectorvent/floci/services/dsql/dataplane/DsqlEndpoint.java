package io.github.hectorvent.floci.services.dsql.dataplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.dsql.model.DsqlCluster;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The address every DSQL cluster is reached at: one port, as on AWS, where the server name a client
 * sends picks the cluster. A connection is admitted as Aurora DSQL admits it (measured on AWS,
 * eu-west-1, 2026-10-16): the password is a SigV4 token for the endpoint's host, {@code admin}
 * signs {@code DbConnectAdmin} and every other role {@code DbConnect}, a token is taken up to 5
 * seconds past its expiry, and a role other than {@code admin} logs in only for an IAM role that
 * {@code AWS IAM GRANT} mapped to it. Every refusal is the same {@code access denied}, with a hint
 * only where DSQL gives one. DSQL lets clients in while a cluster is still CREATING; Floci's
 * clusters are CREATING only while their database starts, and serve once it answers.
 */
@ApplicationScoped
public class DsqlEndpoint {

    private static final Logger LOG = Logger.getLogger(DsqlEndpoint.class);
    private static final IamPolicyEvaluator POLICY_EVALUATOR = new IamPolicyEvaluator(new ObjectMapper());
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    static final Duration EXPIRY_GRACE = Duration.ofSeconds(5);
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 30_000;
    private static final String ADMIN = "admin";

    private final DsqlTlsCertificates certificates;
    private final IamService iamService;
    private final SigV4RequestValidator validator;
    private final boolean enforcement;
    private final int configuredPort;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Map<Long, DsqlDatabases.Backend> cancelKeys = new ConcurrentHashMap<>();
    private final Map<String, Set<Socket>> connections = new ConcurrentHashMap<>();
    private volatile ServerSocket server;
    private volatile Duration transactionAgeLimit = Duration.ofSeconds(300);

    /** A cluster being served, and where its database answers. */
    record Route(String host, String arn, String region, String accountId, DsqlDatabases.Backend backend) {
    }

    /**
     * Whether a connection may go on, and as whom; when it may not, the SQLSTATE DSQL answers
     * ({@code 08006} for the token, {@code 28000} for the role it would log in as) and its hint.
     */
    record Admission(boolean allowed, String state, String hint, Route route, String principalArn) {

        static Admission refused(String hint) {
            return new Admission(false, "08006", hint, null, null);
        }

        static Admission unauthorized() {
            return new Admission(false, "28000", null, null, null);
        }
    }

    @Inject
    public DsqlEndpoint(DsqlTlsCertificates certificates, IamService iamService, EmulatorConfig config) {
        this.certificates = certificates;
        this.iamService = iamService;
        this.validator = new SigV4RequestValidator(iamService);
        this.enforcement = config.services().iam().enforcementEnabled();
        this.configuredPort = config.services().dsql().port();
    }

    /** Serves {@code cluster} from {@code backend}, opening the endpoint's port on first use. */
    public void register(DsqlCluster cluster, DsqlDatabases.Backend backend) throws IOException {
        listen();
        String host = cluster.getEndpoint().toLowerCase(Locale.ROOT);
        routes.put(host, new Route(host, cluster.getArn(), cluster.getRegion(), cluster.getAccountId(), backend));
    }

    /** Stops serving {@code cluster} and closes its connections, as deleting a cluster does. */
    public void unregister(DsqlCluster cluster) {
        String host = cluster.getEndpoint().toLowerCase(Locale.ROOT);
        routes.remove(host);
        Set<Socket> open = connections.remove(host);
        if (open != null) {
            open.forEach(DsqlEndpoint::closeQuietly);
        }
    }

    /** The port the endpoint listens on, once it does: the configured one, or the one picked for 0. */
    public int port() {
        ServerSocket listening = server;
        return listening == null ? configuredPort : listening.getLocalPort();
    }

    @PreDestroy
    public synchronized void stop() {
        ServerSocket listening = server;
        server = null;
        if (listening != null) {
            closeQuietly(listening);
        }
        connections.values().forEach(open -> open.forEach(DsqlEndpoint::closeQuietly));
        connections.clear();
        routes.clear();
    }

    private synchronized void listen() throws IOException {
        if (server != null) {
            return;
        }
        ServerSocket listening = new ServerSocket();
        listening.setReuseAddress(true);
        listening.bind(new InetSocketAddress(configuredPort));
        server = listening;
        Thread.ofVirtual().name("dsql-endpoint-accept").start(() -> accept(listening));
        LOG.infov("DSQL endpoint listening on port {0}", String.valueOf(listening.getLocalPort()));
    }

    private void accept(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                Socket client = listening.accept();
                Thread.ofVirtual().name("dsql-endpoint-connection").start(() -> {
                    try {
                        new DsqlSession(client, this).serve();
                    } catch (IOException | RuntimeException e) {
                        LOG.debugv("DSQL connection ended: {0}", e.getMessage());
                    } finally {
                        closeQuietly(client);
                    }
                });
            } catch (IOException e) {
                if (!listening.isClosed()) {
                    LOG.warnv("DSQL endpoint accept failed: {0}", e.getMessage());
                }
            }
        }
    }

    SSLContext sslContext() {
        return certificates.sslContext();
    }

    int handshakeTimeoutMillis() {
        return HANDSHAKE_TIMEOUT_MILLIS;
    }

    /** How long a transaction may live, 300 seconds as on DSQL; tests shorten it. */
    Duration transactionAgeLimit() {
        return transactionAgeLimit;
    }

    void transactionAgeLimit(Duration limit) {
        this.transactionAgeLimit = limit;
    }

    /**
     * Whether {@code token} lets {@code user} in, checked in the order DSQL reports: the cluster
     * the client asked for by name (or, without a name, the one the token is for), the action
     * the user needs, the token's expiry, then its signature and, for a role other than
     * {@code admin}, the IAM role mapped to it.
     */
    Admission admit(String token, String user, String serverName) {
        URI uri;
        try {
            uri = URI.create("https://" + token);
        } catch (IllegalArgumentException e) {
            return Admission.refused(null);
        }
        String host = uri.getHost();
        String rawQuery = uri.getRawQuery();
        if (host == null || rawQuery == null || uri.getPort() != -1) {
            return Admission.refused(null);
        }
        host = host.toLowerCase(Locale.ROOT);
        String target = serverName == null ? host : serverName.toLowerCase(Locale.ROOT);
        Route route = routes.get(target);
        if (route == null || !route.host().equals(host)) {
            return Admission.refused(null);
        }

        String action = SigV4RequestValidator.queryParameter(rawQuery, "Action");
        String needed = ADMIN.equals(user) ? "DbConnectAdmin" : "DbConnect";
        if (!needed.equals(action)) {
            boolean dsqlAction = "DbConnectAdmin".equals(action) || "DbConnect".equals(action);
            return Admission.refused(dsqlAction
                    ? "Wrong user to action mapping. user: " + user + ", action: " + action : null);
        }

        String expiredHint = expiredHint(rawQuery);
        if (expiredHint != null) {
            return Admission.refused(expiredHint);
        }

        String[] scope = credentialScope(rawQuery);
        if (scope == null || !route.region().equals(scope[2]) || !"dsql".equals(scope[3])) {
            return Admission.refused(null);
        }
        if (!validator.validate(rawQuery, host, needed, "DBUser", false, null, "DSQL IAM token", null,
                EXPIRY_GRACE)) {
            return Admission.refused(null);
        }

        String accessKeyId = scope[0];
        if (enforcement && !allowedByIam(accessKeyId, "dsql:" + needed, route.arn())) {
            return Admission.refused(null);
        }
        String principal = iamService.resolvePrincipalArn(accessKeyId).orElseGet(() -> AwsArnUtils.Arn.global(
                AwsRegions.partitionFor(route.region()), "iam",
                iamService.resolveAccountId(accessKeyId).orElse(route.accountId()), "root").toString());
        if (!ADMIN.equals(user) && !mapped(route.backend(), principal, user)) {
            return Admission.unauthorized();
        }
        return new Admission(true, null, null, route, principal);
    }

    /**
     * DSQL's hint for a token past its expiry and the 5 seconds of grace after it:
     * {@code Signature expired: <expiry> is now earlier than <now - 5 s> (<now> - 0 min.)}.
     */
    private static String expiredHint(String rawQuery) {
        String date = SigV4RequestValidator.queryParameter(rawQuery, "X-Amz-Date");
        String expires = SigV4RequestValidator.queryParameter(rawQuery, "X-Amz-Expires");
        if (date == null || expires == null) {
            return null;
        }
        Instant expiry;
        try {
            expiry = Instant.from(AMZ_DATE.parse(date)).plusSeconds(Long.parseLong(expires));
        } catch (RuntimeException e) {
            return null;
        }
        Instant now = Instant.now();
        Instant earliest = now.minus(EXPIRY_GRACE);
        if (!expiry.isBefore(earliest)) {
            return null;
        }
        return "Signature expired: " + AMZ_DATE.format(expiry) + " is now earlier than " + AMZ_DATE.format(earliest)
                + " (" + AMZ_DATE.format(now) + " - 0 min.)";
    }

    private boolean allowedByIam(String accessKeyId, String action, String clusterArn) {
        CallerContext caller = iamService.resolveCallerContext(accessKeyId);
        if (caller == null) {
            return true;
        }
        return POLICY_EVALUATOR.simulatePrincipalPolicy(caller, action, clusterArn, Map.of())
                == IamPolicyEvaluator.SimulationDecision.ALLOWED;
    }

    /**
     * Whether {@code AWS IAM GRANT} mapped the IAM role {@code principal} to {@code role}. A
     * superuser is never one, as DSQL never lets {@code dbowner} in.
     */
    private static boolean mapped(DsqlDatabases.Backend backend, String principal, String role) {
        try (Connection connection = DsqlDatabases.superuser(backend);
             PreparedStatement query = connection.prepareStatement("SELECT 1 FROM sys.iam_pg_role_mappings m"
                     + " JOIN pg_roles r ON r.oid = m.pg_role_oid WHERE m.arn = ? AND m.pg_role_name = ?"
                     + " AND NOT r.rolsuper")) {
            query.setString(1, principal);
            query.setString(2, role);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException e) {
            LOG.warnv("Could not read the IAM role mappings of a DSQL cluster: {0}", e.getMessage());
            return false;
        }
    }

    private static String[] credentialScope(String rawQuery) {
        String credential = SigV4RequestValidator.queryParameter(rawQuery, "X-Amz-Credential");
        if (credential == null) {
            return null;
        }
        String[] parts = credential.split("/");
        return parts.length >= 5 ? parts : null;
    }

    void opened(Route route, Socket socket) {
        connections.computeIfAbsent(route.host(), host -> ConcurrentHashMap.newKeySet()).add(socket);
        if (!routes.containsKey(route.host())) {
            closeQuietly(socket);
        }
    }

    void closed(Route route, Socket socket) {
        Set<Socket> open = connections.get(route.host());
        if (open != null) {
            open.remove(socket);
        }
    }

    /** Remembers which database a BackendKeyData came from, so a CancelRequest can reach it. */
    long registerCancelKey(byte[] keyData, DsqlDatabases.Backend backend) {
        long key = ByteBuffer.wrap(keyData).getLong(0);
        cancelKeys.put(key, backend);
        return key;
    }

    void forgetCancelKey(long key) {
        cancelKeys.remove(key);
    }

    /** Hands a CancelRequest's process id and secret to the database that issued them. */
    void forwardCancel(byte[] keyData) {
        if (keyData.length < 8) {
            return;
        }
        DsqlDatabases.Backend backend = cancelKeys.get(ByteBuffer.wrap(keyData).getLong(0));
        if (backend == null) {
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(backend.host(), backend.port()), 5_000);
            OutputStream out = socket.getOutputStream();
            out.write(ByteBuffer.allocate(16).putInt(16).putInt(PgWire.CANCEL_REQUEST)
                    .put(keyData, 0, 8).array());
            out.flush();
        } catch (IOException e) {
            LOG.debugv("Could not forward a DSQL cancel request: {0}", e.getMessage());
        }
    }

    private static void closeQuietly(AutoCloseable closing) {
        try {
            closing.close();
        } catch (Exception ignored) {
            // Already closed.
        }
    }
}
