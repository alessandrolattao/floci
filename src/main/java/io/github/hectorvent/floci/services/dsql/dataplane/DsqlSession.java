package io.github.hectorvent.floci.services.dsql.dataplane;

import io.github.hectorvent.floci.services.dsql.DsqlIdentifiers;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One client connection to a DSQL endpoint, as Aurora DSQL serves it (measured on AWS, eu-west-1,
 * 2026-10-16): TLS is mandatory, the password is an IAM token for the endpoint, the cluster's
 * database runs each statement once DSQL's own syntax has become the {@code sys} calls that
 * implement it, its errors come back in DSQL's words, a transaction lives 300 seconds and a
 * connection an hour.
 */
final class DsqlSession {

    static final Duration CONNECTION_LIFETIME = Duration.ofHours(1);
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String ACCESS_DENIED = "unable to accept connection, access denied";

    private final Socket socket;
    private final DsqlEndpoint endpoint;
    private final String sessionId = DsqlIdentifiers.newId();
    private final Deque<String> commandTags = new ArrayDeque<>();
    private final Map<String, String> statementTags = new ConcurrentHashMap<>();
    private final Map<String, String> statementQueries = new ConcurrentHashMap<>();
    private final Map<String, String> portalStatements = new ConcurrentHashMap<>();
    private volatile Instant transactionStart;
    private volatile Instant lastQuery = Instant.now();
    private volatile char transactionStatus = 'I';
    private volatile long cancelKey;
    private boolean discardingUntilSync;

    DsqlSession(Socket socket, DsqlEndpoint endpoint) {
        this.socket = socket;
        this.endpoint = endpoint;
    }

    void serve() throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(endpoint.handshakeTimeoutMillis());
        InputStream rawIn = socket.getInputStream();
        OutputStream rawOut = socket.getOutputStream();
        PgWire.Startup first = PgWire.readStartup(rawIn);
        if (first.code() == PgWire.CANCEL_REQUEST) {
            endpoint.forwardCancel(first.body());
            return;
        }
        if (first.code() == PgWire.GSSENC_REQUEST) {
            rawOut.write('N');
            rawOut.flush();
            first = PgWire.readStartup(rawIn);
        }
        if (first.code() != PgWire.SSL_REQUEST) {
            refuse(rawOut, "08P01", "unable to accept connection, SSL is mandatory. AWS Authentication is required.",
                    null);
            return;
        }
        rawOut.write('S');
        rawOut.flush();

        String[] serverName = new String[1];
        SSLSocket tls = (SSLSocket) endpoint.sslContext().getSocketFactory().createSocket(socket, null, true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setSNIMatchers(List.of(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
            @Override
            public boolean matches(SNIServerName name) {
                if (name instanceof SNIHostName host) {
                    serverName[0] = host.getAsciiName().toLowerCase(Locale.ROOT);
                }
                return true;
            }
        }));
        tls.setSSLParameters(parameters);
        tls.setUseClientMode(false);
        tls.startHandshake();

        InputStream in = new BufferedInputStream(tls.getInputStream());
        OutputStream out = new BufferedOutputStream(tls.getOutputStream());
        PgWire.Startup startup = PgWire.readStartup(in);
        if (startup.code() == PgWire.CANCEL_REQUEST) {
            endpoint.forwardCancel(startup.body());
            return;
        }
        if (startup.code() != PgWire.PROTOCOL_3) {
            refuse(out, "08P01", "unsupported frontend protocol", null);
            return;
        }
        Map<String, String> startupParameters = PgWire.startupParameters(startup.body());
        String user = startupParameters.getOrDefault("user", "");
        String database = startupParameters.getOrDefault("database", user);

        PgWire.write(out, (byte) 'R', PgWire.int32(3));
        out.flush();
        PgWire.Message password = PgWire.read(in);
        if (password.type() != 'p') {
            refuse(out, "08006", ACCESS_DENIED, null);
            return;
        }
        String token = PgWire.cString(password.body(), 0).value();
        DsqlEndpoint.Admission admission = endpoint.admit(token, user, serverName[0]);
        if (!admission.allowed()) {
            refuse(out, admission.state(), ACCESS_DENIED, admission.hint());
            return;
        }
        if (!"postgres".equals(database)) {
            refuse(out, "55000", "database \"" + database + "\" is not currently accepting connections", null);
            return;
        }

        DsqlDatabases.Backend target = admission.route().backend();
        endpoint.opened(admission.route(), tls);
        Socket backend = new Socket();
        try (backend) {
            backend.connect(new InetSocketAddress(target.host(), target.port()), 5_000);
            backend.setTcpNoDelay(true);
            socket.setSoTimeout(0);
            InputStream backendIn = new BufferedInputStream(backend.getInputStream());
            OutputStream backendOut = new BufferedOutputStream(backend.getOutputStream());
            Map<String, String> backendStartup = new LinkedHashMap<>();
            startupParameters.forEach((name, value) -> {
                if (!name.startsWith("dsql.")) {
                    backendStartup.put(name, value);
                }
            });
            backendStartup.put("database", "postgres");
            backendStartup.put("dsql.iam_session_user", admission.principalArn());
            backendStartup.put("dsql.session_id", sessionId);
            backendOut.write(PgWire.startupMessage(backendStartup));
            backendOut.flush();

            Thread toClient = Thread.ofVirtual().name("dsql-backend-" + sessionId)
                    .start(() -> relayToClient(backendIn, out, target, tls));
            Thread lifetime = Thread.ofVirtual().name("dsql-lifetime-" + sessionId).start(() -> {
                try {
                    Thread.sleep(CONNECTION_LIFETIME.toMillis());
                    closeQuietly(tls);
                    closeQuietly(backend);
                } catch (InterruptedException ignored) {
                    // The connection ended first.
                }
            });
            try {
                relayToBackend(in, backendOut);
            } finally {
                lifetime.interrupt();
                closeQuietly(backend);
                toClient.interrupt();
            }
        } finally {
            endpoint.closed(admission.route(), tls);
            if (cancelKey != 0) {
                endpoint.forgetCancelKey(cancelKey);
            }
        }
    }

    private void relayToBackend(InputStream in, OutputStream backendOut) throws IOException {
        while (true) {
            PgWire.Message message = PgWire.read(in);
            byte type = message.type();
            if (discardingUntilSync) {
                // The transaction outlived DSQL's limit: what the client sent up to its Sync is
                // answered by the one error already on its way.
                if (type == 'S') {
                    discardingUntilSync = false;
                }
                continue;
            }
            switch (type) {
                case 'Q' -> {
                    String query = PgWire.cString(message.body(), 0).value();
                    lastQuery = Instant.now();
                    PgWire.write(backendOut, (byte) 'Q', PgWire.cStringBytes(rewriteSimpleQuery(query)));
                }
                case 'P' -> {
                    PgWire.CString name = PgWire.cString(message.body(), 0);
                    PgWire.CString query = PgWire.cString(message.body(), name.next());
                    lastQuery = Instant.now();
                    if (expired() && !endsTransaction(query.value())) {
                        failExpiredPipeline(backendOut);
                        continue;
                    }
                    statementQueries.put(name.value(), query.value());
                    DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(query.value());
                    if (rewritten.overridesTags() && rewritten.commandTags().size() == 1) {
                        statementTags.put(name.value(), rewritten.commandTags().get(0));
                    } else {
                        statementTags.remove(name.value());
                    }
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    body.writeBytes(PgWire.cStringBytes(name.value()));
                    body.writeBytes(PgWire.cStringBytes(rewritten.sql()));
                    body.writeBytes(Arrays.copyOfRange(message.body(), query.next(), message.body().length));
                    PgWire.write(backendOut, (byte) 'P', body.toByteArray());
                }
                case 'B' -> {
                    PgWire.CString portal = PgWire.cString(message.body(), 0);
                    PgWire.CString statement = PgWire.cString(message.body(), portal.next());
                    if (expired() && !endsTransaction(statementQueries.getOrDefault(statement.value(), ""))) {
                        failExpiredPipeline(backendOut);
                        continue;
                    }
                    portalStatements.put(portal.value(), statement.value());
                    PgWire.write(backendOut, message);
                }
                case 'E' -> {
                    PgWire.CString portal = PgWire.cString(message.body(), 0);
                    String statement = portalStatements.getOrDefault(portal.value(), "");
                    synchronized (commandTags) {
                        commandTags.addLast(statementTags.getOrDefault(statement, ""));
                    }
                    PgWire.write(backendOut, message);
                }
                case 'X' -> {
                    PgWire.write(backendOut, message);
                    backendOut.flush();
                    return;
                }
                default -> PgWire.write(backendOut, message);
            }
            if (in.available() == 0) {
                backendOut.flush();
            }
        }
    }

    private String rewriteSimpleQuery(String query) {
        if (expired() && !endsTransaction(query)) {
            return expiredStatement();
        }
        DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(query);
        if (rewritten.overridesTags()) {
            synchronized (commandTags) {
                rewritten.commandTags().forEach(tag -> commandTags.addLast(tag == null ? "" : tag));
            }
        }
        return rewritten.sql();
    }

    /**
     * Fails an extended-protocol exchange in a transaction past its age limit: the database raises
     * DSQL's error for a simple query, whose ErrorResponse and ReadyForQuery are what the client
     * expects for its whole exchange, and everything the client sends until its Sync is dropped.
     */
    private void failExpiredPipeline(OutputStream backendOut) throws IOException {
        discardingUntilSync = true;
        PgWire.write(backendOut, (byte) 'Q', PgWire.cStringBytes(expiredStatement()));
        backendOut.flush();
    }

    /** Whether the open transaction is older than DSQL lets one live. */
    private boolean expired() {
        Instant start = transactionStart;
        return start != null && transactionStatus == 'T'
                && Duration.between(start, Instant.now()).compareTo(endpoint.transactionAgeLimit()) > 0;
    }

    private String expiredStatement() {
        long age = Duration.between(transactionStart, Instant.now()).toSeconds();
        long limit = endpoint.transactionAgeLimit().toSeconds();
        return DsqlStatements.reject("54000", "transaction age limit of " + limit + "s exceeded",
                "Current transaction age " + age + "s exceeds " + limit + "s");
    }

    /** A ROLLBACK always gets through, so a client can leave a transaction that ran out of time. */
    private static boolean endsTransaction(String query) {
        String head = query.strip().toLowerCase(Locale.ROOT);
        return head.startsWith("rollback") || head.startsWith("abort");
    }

    private void relayToClient(InputStream backendIn, OutputStream out, DsqlDatabases.Backend backend, Socket client) {
        try {
            while (true) {
                PgWire.Message message = PgWire.read(backendIn);
                switch (message.type()) {
                    case 'C' -> {
                        String override;
                        synchronized (commandTags) {
                            override = commandTags.pollFirst();
                        }
                        if (override != null && !override.isEmpty()) {
                            message = new PgWire.Message((byte) 'C', PgWire.cStringBytes(override));
                        }
                    }
                    case 'I', 's' -> {
                        synchronized (commandTags) {
                            commandTags.pollFirst();
                        }
                    }
                    case 'E', 'N' -> message = new PgWire.Message(message.type(),
                            inDsqlWords(message.type(), message.body()));
                    case 'K' -> cancelKey = endpoint.registerCancelKey(message.body(), backend);
                    case 'Z' -> {
                        char status = (char) message.body()[0];
                        if (status == 'I') {
                            transactionStart = null;
                        } else if (transactionStart == null) {
                            transactionStart = lastQuery;
                        }
                        transactionStatus = status;
                        synchronized (commandTags) {
                            commandTags.clear();
                        }
                    }
                    default -> {
                    }
                }
                PgWire.write(out, message);
                if (backendIn.available() == 0) {
                    out.flush();
                }
            }
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    /**
     * An error or notice as DSQL words it. An optimistic concurrency conflict is DSQL's OC000.
     * What Floci's {@code sys} functions raise carries no PL/pgSQL context, as DSQL's own errors
     * carry none, and an error raised there no source location either; a notice keeps its
     * location, as the one {@code sys.wait_for_job} raises on DSQL does.
     */
    static byte[] inDsqlWords(byte type, byte[] body) {
        Map<Character, String> fields = PgWire.parseFields(body);
        if (type == 'E' && SERIALIZATION_FAILURE.equals(fields.get('C'))) {
            Map<Character, String> conflict = new LinkedHashMap<>();
            conflict.put('S', fields.getOrDefault('S', "ERROR"));
            conflict.put('V', fields.getOrDefault('V', "ERROR"));
            conflict.put('C', SERIALIZATION_FAILURE);
            conflict.put('M', "change conflicts with another transaction (OC000)");
            return PgWire.fields(conflict);
        }
        String where = fields.get('W');
        if (where == null || !where.contains("function sys.")) {
            return body;
        }
        fields.remove('W');
        if (type == 'E') {
            fields.remove('F');
            fields.remove('L');
            fields.remove('R');
        }
        return PgWire.fields(fields);
    }

    /** A refusal as DSQL words every one: FATAL, with the session's id as its detail. */
    private void refuse(OutputStream out, String state, String message, String hint) throws IOException {
        PgWire.write(out, (byte) 'E', PgWire.error("FATAL", state, message, "Session Id: " + sessionId, hint));
        out.flush();
    }

    private static void closeQuietly(Socket closing) {
        try {
            closing.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}
