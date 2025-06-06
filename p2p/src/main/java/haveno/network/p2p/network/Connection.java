/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.network.p2p.network;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.inject.Inject;
import com.google.protobuf.InvalidProtocolBufferException;
import haveno.common.Proto;
import haveno.common.ThreadUtils;
import haveno.common.app.Capabilities;
import haveno.common.app.HasCapabilities;
import haveno.common.app.Version;
import haveno.common.config.Config;
import haveno.common.proto.ProtobufferException;
import haveno.common.proto.network.NetworkEnvelope;
import haveno.common.proto.network.NetworkProtoResolver;
import haveno.common.util.SingleThreadExecutorUtils;
import haveno.common.util.Utilities;
import haveno.network.p2p.BundleOfEnvelopes;
import haveno.network.p2p.CloseConnectionMessage;
import haveno.network.p2p.ExtendedDataSizePermission;
import haveno.network.p2p.NodeAddress;
import haveno.network.p2p.SendersNodeAddressMessage;
import haveno.network.p2p.SupportedCapabilitiesMessage;
import haveno.network.p2p.peers.keepalive.messages.KeepAliveMessage;
import haveno.network.p2p.storage.P2PDataStorage;
import haveno.network.p2p.storage.messages.AddDataMessage;
import haveno.network.p2p.storage.messages.AddPersistableNetworkPayloadMessage;
import haveno.network.p2p.storage.messages.RemoveDataMessage;
import haveno.network.p2p.storage.payload.CapabilityRequiringPayload;
import haveno.network.p2p.storage.payload.PersistableNetworkPayload;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.OptionalDataException;
import java.io.StreamCorruptedException;
import java.lang.ref.WeakReference;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.Nullable;

/**
 * Connection is created by the server thread or by sendMessage from NetworkNode.
 * All handlers are called on User thread.
 */
@Slf4j
public class Connection implements HasCapabilities, Runnable, MessageListener {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Static
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    @Nullable
    private static Config config;

    // Leaving some constants package-private for tests to know limits.
    private static final int PERMITTED_MESSAGE_SIZE = 200 * 1024;                       // 200 kb
    private static final int MAX_PERMITTED_MESSAGE_SIZE = 10 * 1024 * 1024;             // 10 MB (425 offers resulted in about 660 kb, mailbox msg will add more to it) offer has usually 2 kb, mailbox 3kb.
    //TODO decrease limits again after testing
    private static final int SOCKET_TIMEOUT = (int) TimeUnit.SECONDS.toMillis(240);
    private static final int SHUTDOWN_TIMEOUT = 100;
    private static final String THREAD_ID = Connection.class.getSimpleName();

    public static int getPermittedMessageSize() {
        return PERMITTED_MESSAGE_SIZE;
    }

    public static int getMaxPermittedMessageSize() {
        return MAX_PERMITTED_MESSAGE_SIZE;
    }

    public static int getShutdownTimeout() {
        return SHUTDOWN_TIMEOUT;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final Socket socket;
    private final ConnectionListener connectionListener;
    @Nullable
    private final BanFilter banFilter;
    @Getter
    private final String uid;
    private final ExecutorService executorService;
    @Getter
    private final Statistic statistic;
    @Getter
    private final ConnectionState connectionState;
    @Getter
    private final ConnectionStatistics connectionStatistics;

    // set in init
    private ProtoOutputStream protoOutputStream;

    // mutable data, set from other threads but not changed internally.
    @Getter
    private Optional<NodeAddress> peersNodeAddressOptional = Optional.empty();
    @Getter
    private volatile boolean stopped;

    @Getter
    private final ObjectProperty<NodeAddress> peersNodeAddressProperty = new SimpleObjectProperty<>();
    private final List<Long> messageTimeStamps = new ArrayList<>();
    private final CopyOnWriteArraySet<MessageListener> messageListeners = new CopyOnWriteArraySet<>();
    private volatile long lastSendTimeStamp = 0;
    // We use a weak reference here to ensure that no connection causes a memory leak in case it get closed without
    // the shutDown being called.
    private final CopyOnWriteArraySet<WeakReference<SupportedCapabilitiesListener>> capabilitiesListeners = new CopyOnWriteArraySet<>();

    @Getter
    private RuleViolation ruleViolation;
    private final ConcurrentHashMap<RuleViolation, Integer> ruleViolations = new ConcurrentHashMap<>();

    private final Capabilities capabilities = new Capabilities();

    // throttle logs of reported invalid requests
    private static final long LOG_THROTTLE_INTERVAL_MS = 30000; // throttle logging rule violations and warnings to once every 30 seconds
    private static long lastLoggedInvalidRequestReportTs = 0;
    private static int numThrottledInvalidRequestReports = 0;
    private static long lastLoggedWarningTs = 0;
    private static int numThrottledWarnings = 0;
    private static long lastLoggedInfoTs = 0;
    private static int numThrottledInfos = 0;

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    Connection(Socket socket,
               MessageListener messageListener,
               ConnectionListener connectionListener,
               @Nullable NodeAddress peersNodeAddress,
               NetworkProtoResolver networkProtoResolver,
               @Nullable BanFilter banFilter) {
        this.socket = socket;
        this.connectionListener = connectionListener;
        this.banFilter = banFilter;

        this.uid = UUID.randomUUID().toString();
        this.executorService = SingleThreadExecutorUtils.getSingleThreadExecutor("Executor service for connection with uid " + uid);

        statistic = new Statistic();

        addMessageListener(messageListener);

        this.networkProtoResolver = networkProtoResolver;
        connectionState = new ConnectionState(this);
        connectionStatistics = new ConnectionStatistics(this, connectionState);
        init(peersNodeAddress);
    }

    private void init(@Nullable NodeAddress peersNodeAddress) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT);
            // Need to access first the ObjectOutputStream otherwise the ObjectInputStream would block
            // See: https://stackoverflow.com/questions/5658089/java-creating-a-new-objectinputstream-blocks/5658109#5658109
            // When you construct an ObjectInputStream, in the constructor the class attempts to read a header that
            // the associated ObjectOutputStream on the other end of the connection has written.
            // It will not return until that header has been read.
            protoOutputStream = new ProtoOutputStream(socket.getOutputStream(), statistic);
            protoInputStream = socket.getInputStream();
            // We create a thread for handling inputStream data
            executorService.submit(this);

            if (peersNodeAddress != null) {
                setPeersNodeAddress(peersNodeAddress);
                if (banFilter != null && banFilter.isPeerBanned(peersNodeAddress)) {
                    reportInvalidRequest(RuleViolation.PEER_BANNED, "We created an outbound connection with a banned peer");
                }
            }
            ThreadUtils.execute(() -> connectionListener.onConnection(this), THREAD_ID);
        } catch (Throwable e) {
            handleException(e);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Capabilities getCapabilities() {
        return capabilities;
    }

    void sendMessage(NetworkEnvelope networkEnvelope) {
        long ts = System.currentTimeMillis();
        log.debug(">> Send networkEnvelope of type: {}", networkEnvelope.getClass().getSimpleName());

        if (stopped) {
            log.debug("called sendMessage but was already stopped");
            return;
        }

        if (banFilter != null &&
                peersNodeAddressOptional.isPresent() &&
                banFilter.isPeerBanned(peersNodeAddressOptional.get())) {
            String errorMessage = "We tried to send a message to a banned peer. message=" + networkEnvelope.getClass().getSimpleName();
            reportInvalidRequest(RuleViolation.PEER_BANNED, errorMessage);
            return;
        }

        if (!testCapability(networkEnvelope)) {
            log.debug("Capability for networkEnvelope is required but not supported");
            return;
        }
        int networkEnvelopeSize = networkEnvelope.toProtoNetworkEnvelope().getSerializedSize();
        try {
            // Throttle outbound network_messages
            long now = System.currentTimeMillis();
            long elapsed = now - lastSendTimeStamp;
            if (elapsed < getSendMsgThrottleTrigger()) {
                log.debug("We got 2 sendMessage requests in less than {} ms. We set the thread to sleep " +
                                "for {} ms to avoid flooding our peer. lastSendTimeStamp={}, now={}, elapsed={}, networkEnvelope={}",
                        getSendMsgThrottleTrigger(), getSendMsgThrottleSleep(), lastSendTimeStamp, now, elapsed,
                        networkEnvelope.getClass().getSimpleName());

                Thread.sleep(getSendMsgThrottleSleep());
            }

            lastSendTimeStamp = now;

            if (!stopped) {
                protoOutputStream.writeEnvelope(networkEnvelope);
                ThreadUtils.execute(() -> messageListeners.forEach(e -> e.onMessageSent(networkEnvelope, this)), THREAD_ID);
                ThreadUtils.execute(() -> connectionStatistics.addSendMsgMetrics(System.currentTimeMillis() - ts, networkEnvelopeSize), THREAD_ID);
            }
        } catch (Throwable t) {
            handleException(t);
            throw new RuntimeException(t);
        }
    }

    public boolean testCapability(NetworkEnvelope networkEnvelope) {
        if (networkEnvelope instanceof BundleOfEnvelopes) {
            // We remove elements in the list which fail the capability test
            BundleOfEnvelopes bundleOfEnvelopes = (BundleOfEnvelopes) networkEnvelope;
            updateBundleOfEnvelopes(bundleOfEnvelopes);
            // If the bundle is empty we dont send the networkEnvelope
            return !bundleOfEnvelopes.getEnvelopes().isEmpty();
        }

        return extractCapabilityRequiringPayload(networkEnvelope)
                .map(this::testCapability)
                .orElse(true);
    }

    private boolean testCapability(CapabilityRequiringPayload capabilityRequiringPayload) {
        boolean result = capabilities.containsAll(capabilityRequiringPayload.getRequiredCapabilities());
        if (!result) {
            log.debug("We did not send {} because capabilities are not supported.",
                    capabilityRequiringPayload.getClass().getSimpleName());
        }
        return result;
    }

    private void updateBundleOfEnvelopes(BundleOfEnvelopes bundleOfEnvelopes) {
        List<NetworkEnvelope> toRemove = bundleOfEnvelopes.getEnvelopes().stream()
                .filter(networkEnvelope -> !testCapability(networkEnvelope))
                .collect(Collectors.toList());
        bundleOfEnvelopes.getEnvelopes().removeAll(toRemove);
    }

    private Optional<CapabilityRequiringPayload> extractCapabilityRequiringPayload(Proto proto) {
        Proto candidate = proto;
        // Lets check if our networkEnvelope is a wrapped data structure
        if (proto instanceof AddDataMessage) {
            candidate = (((AddDataMessage) proto).getProtectedStorageEntry()).getProtectedStoragePayload();
        } else if (proto instanceof RemoveDataMessage) {
            candidate = (((RemoveDataMessage) proto).getProtectedStorageEntry()).getProtectedStoragePayload();
        } else if (proto instanceof AddPersistableNetworkPayloadMessage) {
            candidate = (((AddPersistableNetworkPayloadMessage) proto).getPersistableNetworkPayload());
        }

        if (candidate instanceof CapabilityRequiringPayload) {
            return Optional.of((CapabilityRequiringPayload) candidate);
        }
        return Optional.empty();
    }

    public void addMessageListener(MessageListener messageListener) {
        boolean isNewEntry = messageListeners.add(messageListener);
        if (!isNewEntry)
            log.warn("Try to add a messageListener which was already added.");
    }

    public void removeMessageListener(MessageListener messageListener) {
        boolean contained = messageListeners.remove(messageListener);
        if (!contained)
            log.debug("Try to remove a messageListener which was never added.\n\t" +
                    "That might happen because of async behaviour of CopyOnWriteArraySet");
    }

    public void addWeakCapabilitiesListener(SupportedCapabilitiesListener listener) {
        capabilitiesListeners.add(new WeakReference<>(listener));
    }

    private boolean violatesThrottleLimit() {
        long now = System.currentTimeMillis();

        messageTimeStamps.add(now);

        // clean list
        while (messageTimeStamps.size() > getMsgThrottlePer10Sec())
            messageTimeStamps.remove(0);

        return violatesThrottleLimit(now, 1, getMsgThrottlePerSec()) ||
                violatesThrottleLimit(now, 10, getMsgThrottlePer10Sec());
    }

    private int getMsgThrottlePerSec() {
        return config != null ? config.msgThrottlePerSec : 200;
    }

    private int getMsgThrottlePer10Sec() {
        return config != null ? config.msgThrottlePer10Sec : 1000;
    }

    private int getSendMsgThrottleSleep() {
        return config != null ? config.sendMsgThrottleSleep : 50;
    }

    private int getSendMsgThrottleTrigger() {
        return config != null ? config.sendMsgThrottleTrigger : 20;
    }

    private boolean violatesThrottleLimit(long now, int seconds, int messageCountLimit) {
        if (messageTimeStamps.size() >= messageCountLimit) {

            // find the entry in the message timestamp history which determines whether we overshot the limit or not
            long compareValue = messageTimeStamps.get(messageTimeStamps.size() - messageCountLimit);

            // if duration < seconds sec we received too much network_messages
            if (now - compareValue < TimeUnit.SECONDS.toMillis(seconds)) {
                log.error("violatesThrottleLimit {}/{} second(s)", messageCountLimit, seconds);

                return true;
            }
        }

        return false;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Only receive non - CloseConnectionMessage network_messages
    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        checkArgument(connection.equals(this));
        if (networkEnvelope instanceof BundleOfEnvelopes) {
            onBundleOfEnvelopes((BundleOfEnvelopes) networkEnvelope, connection);
        } else {
            ThreadUtils.execute(() -> messageListeners.forEach(e -> e.onMessage(networkEnvelope, connection)), THREAD_ID);
        }
    }

    private void onBundleOfEnvelopes(BundleOfEnvelopes bundleOfEnvelopes, Connection connection) {
        Map<P2PDataStorage.ByteArray, Set<NetworkEnvelope>> itemsByHash = new HashMap<>();
        Set<NetworkEnvelope> envelopesToProcess = new HashSet<>();
        List<NetworkEnvelope> networkEnvelopes = bundleOfEnvelopes.getEnvelopes();
        for (NetworkEnvelope networkEnvelope : networkEnvelopes) {
            // If SendersNodeAddressMessage we do some verifications and apply if successful, otherwise we return false.
            if (networkEnvelope instanceof SendersNodeAddressMessage) {
                boolean isValid = processSendersNodeAddressMessage((SendersNodeAddressMessage) networkEnvelope);
                if (!isValid) {
                    throttleWarn("Received an invalid " + networkEnvelope.getClass().getSimpleName() + " at processing BundleOfEnvelopes");
                    continue;
                }
            }

            if (networkEnvelope instanceof AddPersistableNetworkPayloadMessage) {
                PersistableNetworkPayload persistableNetworkPayload = ((AddPersistableNetworkPayloadMessage) networkEnvelope).getPersistableNetworkPayload();
                byte[] hash = persistableNetworkPayload.getHash();
                String itemName = persistableNetworkPayload.getClass().getSimpleName();
                P2PDataStorage.ByteArray byteArray = new P2PDataStorage.ByteArray(hash);
                itemsByHash.putIfAbsent(byteArray, new HashSet<>());
                Set<NetworkEnvelope> envelopesByHash = itemsByHash.get(byteArray);
                if (!envelopesByHash.contains(networkEnvelope)) {
                    envelopesByHash.add(networkEnvelope);
                    envelopesToProcess.add(networkEnvelope);
                } else {
                    log.debug("We got duplicated items for {}. We ignore the duplicates. Hash: {}",
                            itemName, Utilities.encodeToHex(hash));
                }
            } else {
                envelopesToProcess.add(networkEnvelope);
            }
        }
        envelopesToProcess.forEach(envelope -> ThreadUtils.execute(() -> {
                messageListeners.forEach(listener -> listener.onMessage(envelope, connection));
        }, THREAD_ID));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Setters
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setPeersNodeAddress(NodeAddress peerNodeAddress) {
        checkNotNull(peerNodeAddress, "peerAddress must not be null");
        peersNodeAddressOptional = Optional.of(peerNodeAddress);

        if (this instanceof InboundConnection) {
            log.debug("\n\n############################################################\n" +
                    "We got the peers node address set.\n" +
                    "peersNodeAddress= " + peerNodeAddress.getFullAddress() +
                    "\nconnection.uid=" + getUid() +
                    "\n############################################################\n");
        }

        peersNodeAddressProperty.set(peerNodeAddress);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean hasPeersNodeAddress() {
        return peersNodeAddressOptional.isPresent();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // ShutDown
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void shutDown(CloseConnectionReason closeConnectionReason) {
        shutDown(closeConnectionReason, null);
    }

    public void shutDown(CloseConnectionReason closeConnectionReason, @Nullable Runnable shutDownCompleteHandler) {
        log.debug("shutDown: peersNodeAddressOptional={}, closeConnectionReason={}",
                peersNodeAddressOptional, closeConnectionReason);

        connectionState.shutDown();

        if (!stopped) {
            String peersNodeAddress = peersNodeAddressOptional.map(NodeAddress::toString).orElse("null");
            log.debug("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                    "ShutDown connection:"
                    + "\npeersNodeAddress=" + peersNodeAddress
                    + "\ncloseConnectionReason=" + closeConnectionReason
                    + "\nuid=" + uid
                    + "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n");

            if (closeConnectionReason.sendCloseMessage) {
                new Thread(() -> {
                    try {
                        String reason = closeConnectionReason == CloseConnectionReason.RULE_VIOLATION ?
                                getRuleViolation().name() : closeConnectionReason.name();
                        sendMessage(new CloseConnectionMessage(reason));

                        stopped = true;

                        Uninterruptibles.sleepUninterruptibly(200, TimeUnit.MILLISECONDS);
                    } catch (Throwable t) {
                        log.error(ExceptionUtils.getStackTrace(t));
                    } finally {
                        stopped = true;
                        ThreadUtils.execute(() -> doShutDown(closeConnectionReason, shutDownCompleteHandler), THREAD_ID);
                    }
                }, "Connection:SendCloseConnectionMessage-" + this.uid).start();
            } else {
                stopped = true;
                doShutDown(closeConnectionReason, shutDownCompleteHandler);
            }
        } else {
            //TODO find out why we get called that
            log.debug("stopped was already at shutDown call");
            ThreadUtils.execute(() -> doShutDown(closeConnectionReason, shutDownCompleteHandler), THREAD_ID);
        }
    }

    private void doShutDown(CloseConnectionReason closeConnectionReason, @Nullable Runnable shutDownCompleteHandler) {
        ThreadUtils.execute(() -> connectionListener.onDisconnect(closeConnectionReason, this), THREAD_ID);
        try {
            protoOutputStream.onConnectionShutdown();
            socket.close();
        } catch (SocketException e) {
            log.trace("SocketException at shutdown might be expected {}", e.getMessage());
        } catch (IOException e) {
            log.error("Exception at shutdown. {}\n", e.getMessage(), e);
        } finally {
            capabilitiesListeners.clear();

            try {
                protoInputStream.close();
            } catch (IOException e) {
                log.error(ExceptionUtils.getStackTrace(e));
            }

            Utilities.shutdownAndAwaitTermination(executorService, SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);

            log.debug("Connection shutdown complete {}", this);
            if (shutDownCompleteHandler != null)
                ThreadUtils.execute(shutDownCompleteHandler, THREAD_ID);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Connection)) return false;

        Connection that = (Connection) o;

        return uid.equals(that.uid);

    }

    @Override
    public int hashCode() {
        return uid.hashCode();
    }

    @Override
    public String toString() {
        return "Connection{" +
                "peerAddress=" + peersNodeAddressOptional +
                ", connectionState=" + connectionState +
                ", connectionType=" + (this instanceof InboundConnection ? "InboundConnection" : "OutboundConnection") +
                ", uid='" + uid + '\'' +
                '}';
    }

    public String printDetails() {
        String portInfo;
        if (socket.getLocalPort() == 0)
            portInfo = "port=" + socket.getPort();
        else
            portInfo = "localPort=" + socket.getLocalPort() + "/port=" + socket.getPort();

        return "Connection{" +
                "peerAddress=" + peersNodeAddressOptional +
                ", connectionState=" + connectionState +
                ", portInfo=" + portInfo +
                ", uid='" + uid + '\'' +
                ", ruleViolation=" + ruleViolation +
                ", ruleViolations=" + ruleViolations +
                ", supportedCapabilities=" + capabilities +
                ", stopped=" + stopped +
                '}';
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // SharedSpace
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Holds all shared data between Connection and InputHandler
     * Runs in same thread as Connection
     */

    public boolean reportInvalidRequest(RuleViolation ruleViolation, String errorMessage) {
        return Connection.reportInvalidRequest(this, ruleViolation, errorMessage);
    }

    private static synchronized boolean reportInvalidRequest(Connection connection, RuleViolation ruleViolation, String errorMessage) {

        // determine if report should be logged to avoid spamming the logs
        boolean logReport = System.currentTimeMillis() - lastLoggedInvalidRequestReportTs > LOG_THROTTLE_INTERVAL_MS;

        // count the number of unlogged reports since last log entry
        if (!logReport) numThrottledInvalidRequestReports++;

        // handle report
        if (logReport) log.warn("We got reported the ruleViolation {} at connection with address={}, uid={}, errorMessage={}", ruleViolation, connection.getPeersNodeAddressProperty(), connection.getUid(), errorMessage);
        int numRuleViolations;
        numRuleViolations = connection.ruleViolations.getOrDefault(ruleViolation, 0);
        numRuleViolations++;
        connection.ruleViolations.put(ruleViolation, numRuleViolations);
        if (numRuleViolations >= ruleViolation.maxTolerance) {
            if (logReport) log.warn("We close connection as we received too many corrupt requests. " +
                    "ruleViolations={} " +
                    "connection with address {} and uid {}", connection.ruleViolations, connection.peersNodeAddressProperty, connection.uid);
            connection.ruleViolation = ruleViolation;
            if (ruleViolation == RuleViolation.PEER_BANNED) {
                if (logReport) log.debug("We close connection due RuleViolation.PEER_BANNED. peersNodeAddress={}", connection.getPeersNodeAddressOptional());
                connection.shutDown(CloseConnectionReason.PEER_BANNED);
            } else if (ruleViolation == RuleViolation.INVALID_CLASS) {
                if (logReport) log.warn("We close connection due RuleViolation.INVALID_CLASS");
                connection.shutDown(CloseConnectionReason.INVALID_CLASS_RECEIVED);
            } else {
                if (logReport) log.warn("We close connection due RuleViolation.RULE_VIOLATION");
                connection.shutDown(CloseConnectionReason.RULE_VIOLATION);
            }

            resetReportedInvalidRequestsThrottle(logReport);
            return true;
        } else {
            resetReportedInvalidRequestsThrottle(logReport);
            return false;
        }
    }

    private static synchronized void resetReportedInvalidRequestsThrottle(boolean logReport) {
        if (logReport) {
            if (numThrottledInvalidRequestReports > 0) log.warn("Possible DoS attack detected. We received {} other reports of invalid requests since the last log entry", numThrottledInvalidRequestReports);
            numThrottledInvalidRequestReports = 0;
            lastLoggedInvalidRequestReportTs = System.currentTimeMillis();
        }
    }

    private void handleException(Throwable e) {
        CloseConnectionReason closeConnectionReason;

        // silent fail if we are shutdown
        if (stopped)
            return;

        if (e instanceof SocketException) {
            if (socket.isClosed())
                closeConnectionReason = CloseConnectionReason.SOCKET_CLOSED;
            else
                closeConnectionReason = CloseConnectionReason.RESET;

            throttleWarn("SocketException (expected if connection lost). closeConnectionReason=" + closeConnectionReason + "; connection=" + this);
        } else if (e instanceof SocketTimeoutException || e instanceof TimeoutException) {
            closeConnectionReason = CloseConnectionReason.SOCKET_TIMEOUT;
            throttleInfo("Shut down caused by exception " + e.getMessage() + " on connection=" + this);
        } else if (e instanceof EOFException) {
            closeConnectionReason = CloseConnectionReason.TERMINATED;
            throttleWarn("Shut down caused by exception " + e.getMessage() + " on connection=" + this);
        } else if (e instanceof OptionalDataException || e instanceof StreamCorruptedException) {
            closeConnectionReason = CloseConnectionReason.CORRUPTED_DATA;
            throttleWarn("Shut down caused by exception " + e.getMessage() + " on connection=" + this);
        } else {
            // TODO sometimes we get StreamCorruptedException, OptionalDataException, IllegalStateException
            closeConnectionReason = CloseConnectionReason.UNKNOWN_EXCEPTION;
            throttleWarn("Unknown reason for exception at socket: " + socket.toString() + "\n\t" +
                            "peer=" + this.peersNodeAddressOptional + "\n\t" +
                            "Exception=" + e.toString());
        }
        shutDown(closeConnectionReason);
    }

    private boolean processSendersNodeAddressMessage(SendersNodeAddressMessage sendersNodeAddressMessage) {
        NodeAddress senderNodeAddress = sendersNodeAddressMessage.getSenderNodeAddress();
        checkNotNull(senderNodeAddress,
                "senderNodeAddress must not be null at SendersNodeAddressMessage " +
                        sendersNodeAddressMessage.getClass().getSimpleName());
        Optional<NodeAddress> existingAddressOptional = getPeersNodeAddressOptional();
        if (existingAddressOptional.isPresent()) {
            // If we have already the peers address we check again if it matches our stored one
            checkArgument(existingAddressOptional.get().equals(senderNodeAddress),
                    "senderNodeAddress not matching connections peer address.\n\t" +
                            "message=" + sendersNodeAddressMessage);
        } else {
            setPeersNodeAddress(senderNodeAddress);
        }

        if (banFilter != null && banFilter.isPeerBanned(senderNodeAddress)) {
            String errorMessage = "We got a message from a banned peer. message=" + sendersNodeAddressMessage.getClass().getSimpleName();
            reportInvalidRequest(RuleViolation.PEER_BANNED, errorMessage);
            return false;
        }

        return true;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // InputHandler
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Runs in same thread as Connection, receives a message, performs several checks on it
    // (including throttling limits, validity and statistics)
    // and delivers it to the message listener given in the constructor.
    private InputStream protoInputStream;
    private final NetworkProtoResolver networkProtoResolver;

    private long lastReadTimeStamp;
    private boolean threadNameSet;

    @Override
    public void run() {
        try {
            Thread.currentThread().setName("InputHandler-" + Utilities.toTruncatedString(uid, 15));
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                if (!threadNameSet && getPeersNodeAddressOptional().isPresent()) {
                    Thread.currentThread().setName("InputHandler-" + Utilities.toTruncatedString(getPeersNodeAddressOptional().get().getFullAddress(), 15));
                    threadNameSet = true;
                }
                try {
                    if (socket != null &&
                            socket.isClosed()) {
                        throttleWarn("Socket is null or closed socket=" + socket);
                        shutDown(CloseConnectionReason.SOCKET_CLOSED);
                        return;
                    }

                    // Blocking read from the inputStream
                    protobuf.NetworkEnvelope proto = protobuf.NetworkEnvelope.parseDelimitedFrom(protoInputStream);

                    long ts = System.currentTimeMillis();

                    if (socket != null &&
                            socket.isClosed()) {
                        throttleWarn("Socket is null or closed socket=" + socket);
                        shutDown(CloseConnectionReason.SOCKET_CLOSED);
                        return;
                    }

                    if (proto == null) {
                        if (stopped) {
                            return;
                        }
                        if (protoInputStream.read() == -1) {
                            throttleWarn("proto is null because protoInputStream.read()=-1 (EOF). That is expected if client got stopped without proper shutdown.");
                        } else {
                            throttleWarn("proto is null. protoInputStream.read()=" + protoInputStream.read());
                        }
                        shutDown(CloseConnectionReason.NO_PROTO_BUFFER_ENV);
                        return;
                    }

                    if (banFilter != null &&
                            peersNodeAddressOptional.isPresent() &&
                            banFilter.isPeerBanned(peersNodeAddressOptional.get())) {
                        String errorMessage = "We got a message from a banned peer. proto=" + Utilities.toTruncatedString(proto);
                        reportInvalidRequest(RuleViolation.PEER_BANNED, errorMessage);
                        return;
                    }

                    // Throttle inbound network messages
                    long now = System.currentTimeMillis();
                    long elapsed = now - lastReadTimeStamp;
                    if (elapsed < 10) {
                        log.debug("We got 2 network messages received in less than 10 ms. We set the thread to sleep " +
                                        "for 20 ms to avoid getting flooded by our peer. lastReadTimeStamp={}, now={}, elapsed={}",
                                lastReadTimeStamp, now, elapsed);
                        Thread.sleep(20);
                    }

                    NetworkEnvelope networkEnvelope = networkProtoResolver.fromProto(proto);
                    lastReadTimeStamp = now;
                    log.debug("<< Received networkEnvelope of type: {}", networkEnvelope.getClass().getSimpleName());
                    int size = proto.getSerializedSize();

                    // We want to track the size of each object even if it is invalid data
                    statistic.addReceivedBytes(size);

                    // We want to track the network_messages also before the checks, so do it early...
                    statistic.addReceivedMessage(networkEnvelope);

                    // First we check the size
                    boolean exceeds;
                    if (networkEnvelope instanceof ExtendedDataSizePermission) {
                        exceeds = size > MAX_PERMITTED_MESSAGE_SIZE;
                    } else {
                        exceeds = size > PERMITTED_MESSAGE_SIZE;
                    }

                    if (networkEnvelope instanceof AddPersistableNetworkPayloadMessage &&
                            !((AddPersistableNetworkPayloadMessage) networkEnvelope).getPersistableNetworkPayload().verifyHashSize()) {
                        String errorMessage = "PersistableNetworkPayload.verifyHashSize failed. hashSize=" +
                                ((AddPersistableNetworkPayloadMessage) networkEnvelope).getPersistableNetworkPayload().getHash().length + "; object=" +
                                Utilities.toTruncatedString(proto);
                        if (reportInvalidRequest(RuleViolation.MAX_MSG_SIZE_EXCEEDED, errorMessage))
                            return;
                    }

                    if (exceeds) {
                        String errorMessage = "size > MAX_MSG_SIZE. size=" + size + "; object=" + Utilities.toTruncatedString(proto);
                        if (reportInvalidRequest(RuleViolation.MAX_MSG_SIZE_EXCEEDED, errorMessage))
                            return;
                    }

                    if (violatesThrottleLimit() && reportInvalidRequest(RuleViolation.THROTTLE_LIMIT_EXCEEDED, "Violates throttle limit"))
                        return;

                    // Check P2P network ID
                    String errorMessage = "RuleViolation.WRONG_NETWORK_ID. version of message=" + proto.getMessageVersion() +
                            ", app version=" + Version.getP2PMessageVersion() +
                            ", proto.toTruncatedString=" + Utilities.toTruncatedString(proto.toString());
                    if (!proto.getMessageVersion().equals(Version.getP2PMessageVersion())
                            && reportInvalidRequest(RuleViolation.WRONG_NETWORK_ID, errorMessage)) {
                        return;
                    }

                    boolean causedShutDown = maybeHandleSupportedCapabilitiesMessage(networkEnvelope);
                    if (causedShutDown) {
                        return;
                    }

                    if (networkEnvelope instanceof CloseConnectionMessage) {
                        // If we get a CloseConnectionMessage we shut down
                        log.debug("CloseConnectionMessage received. Reason={}\n\t" +
                                "connection={}", proto.getCloseConnectionMessage().getReason(), this);

                        if (CloseConnectionReason.PEER_BANNED.name().equals(proto.getCloseConnectionMessage().getReason())) {
                            log.warn("We got shut down because we are banned by the other peer. " +
                                    "(InputHandler.run CloseConnectionMessage). Peer: {}", getPeersNodeAddressOptional());
                        }
                        shutDown(CloseConnectionReason.CLOSE_REQUESTED_BY_PEER);
                        return;
                    } else if (!stopped) {
                        // We don't want to get the activity ts updated by ping/pong msg
                        if (!(networkEnvelope instanceof KeepAliveMessage))
                            statistic.updateLastActivityTimestamp();

                        // If SendersNodeAddressMessage we do some verifications and apply if successful,
                        // otherwise we return false.
                        if (networkEnvelope instanceof SendersNodeAddressMessage) {
                            boolean isValid = processSendersNodeAddressMessage((SendersNodeAddressMessage) networkEnvelope);
                            if (!isValid) {
                                return;
                            }
                        }

                        if (!(networkEnvelope instanceof SendersNodeAddressMessage) && peersNodeAddressOptional.isEmpty()) {
                            log.info("We got a {} from a peer with yet unknown address on connection with uid={}", networkEnvelope.getClass().getSimpleName(), uid);
                        }

                        ThreadUtils.execute(() -> onMessage(networkEnvelope, this), THREAD_ID);
                        ThreadUtils.execute(() -> connectionStatistics.addReceivedMsgMetrics(System.currentTimeMillis() - ts, size), THREAD_ID);
                    }
                } catch (InvalidClassException e) {
                    reportInvalidRequest(RuleViolation.INVALID_CLASS, e.getMessage());
                } catch (ProtobufferException | NoClassDefFoundError | InvalidProtocolBufferException e) {
                    reportInvalidRequest(RuleViolation.INVALID_DATA_TYPE, e.getMessage());
                } catch (Throwable t) {
                    handleException(t);
                }
            }
        } catch (Throwable t) {
            handleException(t);
        }
    }

    public boolean maybeHandleSupportedCapabilitiesMessage(NetworkEnvelope networkEnvelope) {
        if (!(networkEnvelope instanceof SupportedCapabilitiesMessage)) {
            return false;
        }

        Capabilities supportedCapabilities = ((SupportedCapabilitiesMessage) networkEnvelope).getSupportedCapabilities();
        if (supportedCapabilities == null || supportedCapabilities.isEmpty()) {
            return false;
        }

        if (this.capabilities.equals(supportedCapabilities)) {
            return false;
        }

        if (!Capabilities.hasMandatoryCapability(supportedCapabilities)) {
            log.info("We close a connection because of " +
                            "CloseConnectionReason.MANDATORY_CAPABILITIES_NOT_SUPPORTED " +
                            "to node {}. Capabilities of old node: {}, " +
                            "networkEnvelope class name={}",
                    getSenderNodeAddressAsString(networkEnvelope),
                    supportedCapabilities.prettyPrint(),
                    networkEnvelope.getClass().getSimpleName());
            shutDown(CloseConnectionReason.MANDATORY_CAPABILITIES_NOT_SUPPORTED);
            return true;
        }

        this.capabilities.set(supportedCapabilities);

        capabilitiesListeners.forEach(weakListener -> {
            SupportedCapabilitiesListener supportedCapabilitiesListener = weakListener.get();
            if (supportedCapabilitiesListener != null) {
                ThreadUtils.execute(() -> supportedCapabilitiesListener.onChanged(supportedCapabilities), THREAD_ID);
            }
        });
        return false;
    }

    @Nullable
    private NodeAddress getSenderNodeAddress(NetworkEnvelope networkEnvelope) {
        return getPeersNodeAddressOptional().orElse(
                networkEnvelope instanceof SendersNodeAddressMessage ?
                        ((SendersNodeAddressMessage) networkEnvelope).getSenderNodeAddress() :
                        null);
    }

    private String getSenderNodeAddressAsString(NetworkEnvelope networkEnvelope) {
        NodeAddress nodeAddress = getSenderNodeAddress(networkEnvelope);
        return nodeAddress == null ? "null" : nodeAddress.getFullAddress();
    }

    private synchronized void throttleWarn(String msg) {
        boolean doLog = System.currentTimeMillis() - lastLoggedWarningTs > LOG_THROTTLE_INTERVAL_MS;
        if (doLog) {
            log.warn(msg);
            if (numThrottledWarnings > 0) log.warn("Possible DoS attack detected. {} warnings were throttled since the last log entry", numThrottledWarnings);
            numThrottledWarnings = 0;
            lastLoggedWarningTs = System.currentTimeMillis();
        } else {
            numThrottledWarnings++;
        }
    }

    private synchronized void throttleInfo(String msg) {
        boolean doLog = System.currentTimeMillis() - lastLoggedInfoTs > LOG_THROTTLE_INTERVAL_MS;
        if (doLog) {
            log.info(msg);
            if (numThrottledInfos > 0) log.info("Possible DoS attack detected. {} info logs were throttled since the last log entry", numThrottledInfos);
            numThrottledInfos = 0;
            lastLoggedInfoTs = System.currentTimeMillis();
        } else {
            numThrottledInfos++;
        }
    }
}
