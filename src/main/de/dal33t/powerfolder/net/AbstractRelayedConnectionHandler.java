package de.dal33t.powerfolder.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.Feature;
import de.dal33t.powerfolder.Member;
import de.dal33t.powerfolder.PFComponent;
import de.dal33t.powerfolder.light.MemberInfo;
import de.dal33t.powerfolder.message.Identity;
import de.dal33t.powerfolder.message.IdentityReply;
import de.dal33t.powerfolder.message.Message;
import de.dal33t.powerfolder.message.Ping;
import de.dal33t.powerfolder.message.Pong;
import de.dal33t.powerfolder.message.Problem;
import de.dal33t.powerfolder.message.RelayedMessage;
import de.dal33t.powerfolder.message.RelayedMessage.Type;
import de.dal33t.powerfolder.util.ByteSerializer;
import de.dal33t.powerfolder.util.Format;
import de.dal33t.powerfolder.util.IdGenerator;
import de.dal33t.powerfolder.util.Reject;
import de.dal33t.powerfolder.util.net.NetworkUtil;

/**
 * The base super class for connection which get relayed through a third node.
 * <p>
 * TRAC #597
 * 
 * @author <a href="mailto:sprajc@riege.com">Christian Sprajc</a>
 * @version $Revision: 1.5 $
 */
public abstract class AbstractRelayedConnectionHandler extends PFComponent
    implements ConnectionHandler
{
    /** The relay to use */
    private Member relay;

    /**
     * The connection id
     */
    private long connectionId;

    /**
     * The aimed remote for this connection.
     */
    private MemberInfo remote;

    /** The assigned member */
    private Member member;

    // Our identity
    private Identity myIdentity;

    // Identity of remote peer
    private Identity identity;
    private IdentityReply identityReply;
    // The magic id, which has been send to the remote peer
    private String myMagicId;

    private ByteSerializer serializer;

    // The send buffer
    private Queue<Message> messagesToSendQueue;

    private boolean started;
    // Flag if client is on lan
    private boolean onLAN;

    // Locks
    private final Object identityWaiter = new Object();
    private final Object identityAcceptWaiter = new Object();
    // Lock for sending message
    private final Object sendLock = new Object();

    /**
     * The current active sender.
     */
    private Runnable sender;

    /**
     * Lock to ensure that modifications to senders are performed by one thread
     * only.
     */
    private Lock senderSpawnLock;

    // Keepalive stuff
    private Date lastKeepaliveMessage;

    /**
     * Flag that indicates a received ACK. relyed connection is ready for
     * traffic.
     */
    private boolean ackReceived;

    /**
     * Flag that indicates a received NACK. relyed connection is cannot be
     * established
     */
    private boolean nackReceived;

    /**
     * Builds a new anonymous connection manager using the given relay.
     * <p>
     * Should be called from <code>ConnectionHandlerFactory</code> only.
     * 
     * @see ConnectionHandlerFactory
     * @param controller
     *            the controller.
     * @param remote
     *            the aimed remote side to connect.
     * @param relay
     *            the relay to use.
     * @throws ConnectionException
     */
    protected AbstractRelayedConnectionHandler(Controller controller,
        MemberInfo remote, long connectionId, Member relay)
    {
        super(controller);
        Reject.ifNull(remote, "Remote is null");
        Reject.ifNull(relay, "Relay is null");
        Reject.ifFalse(relay.isCompleteyConnected(), "Relay is not connected: "
            + relay);
        this.remote = remote;
        this.relay = relay;
        this.serializer = new ByteSerializer();
        this.connectionId = connectionId;
    }

    // Abstract behaviour *****************************************************

    /**
     * Called before the message gets actally written into the
     * <code>RelayedMessage</code>
     * 
     * @param message
     *            the message to serialize
     * @return the serialized message
     */
    protected abstract byte[] serialize(Message message)
        throws ConnectionException;

    /**
     * Called when the data got read from the <code>RelayedMessage</code>.
     * Should re-construct the serialized object from the data.
     * 
     * @param data
     *            the serialized data
     * @param len
     *            the actual size of the data in data buffer
     * @return the deserialized object
     */
    protected abstract Object deserialize(byte[] data, int len)
        throws ConnectionException, ClassNotFoundException;

    /**
     * (Optional) Handles the received object.
     * 
     * @param obj
     *            the obj that was received
     * @return true if this object/message was handled.
     * @throws ConnectionException
     *             if something is broken.
     */
    @SuppressWarnings("unused")
    protected boolean receivedObject(Object obj) throws ConnectionException {
        return false;
    }

    /**
     * @return an identity that gets send to the remote side.
     */
    protected abstract Identity createOwnIdentity();

    /**
     * @return the internal used serializer
     */
    protected ByteSerializer getSerializer() {
        return serializer;
    }

    /**
     * @return the relay
     */
    protected Member getRelay() {
        return relay;
    }

    /**
     * Initalizes the connection handler.
     * 
     * @param controller
     * @param aSocket
     *            the tctp/ip socket.
     * @throws ConnectionException
     */
    public void init() throws ConnectionException {
        if (!relay.isCompleteyConnected()) {
            throw new ConnectionException("Connection to peer is closed")
                .with(this);
        }
        this.started = true;
        // Don't clear, might have been already received!
        // this.identity = null;
        // this.identityReply = null;
        this.messagesToSendQueue = new ConcurrentLinkedQueue<Message>();
        this.senderSpawnLock = new ReentrantLock();
        long startTime = System.currentTimeMillis();

        // Generate magic id, 16 byte * 8 * 8 bit = 1024 bit key
        myMagicId = IdGenerator.makeId() + IdGenerator.makeId()
            + IdGenerator.makeId() + IdGenerator.makeId()
            + IdGenerator.makeId() + IdGenerator.makeId()
            + IdGenerator.makeId() + IdGenerator.makeId();

        // Create identity
        myIdentity = createOwnIdentity();
        if (logVerbose) {
            log().verbose(
                "Sending my identity, nick: '"
                    + myIdentity.getMemberInfo().nick + "', ID: "
                    + myIdentity.getMemberInfo().id);
        }

        // Send identity
        sendMessagesAsynchron(myIdentity);

        waitForRemoteIdentity();

        if (!isConnected()) {
            shutdown();
            throw new ConnectionException(
                "Remote peer disconnected while waiting for his identity")
                .with(this);
        }
        if (identity == null || identity.getMemberInfo() == null) {
            throw new ConnectionException(
                "Did not receive a valid identity from peer after 60s: "
                    + getRemote()).with(this);
        }

        // Check if IP is on LAN
        // onLAN = getController().getBroadcastManager().receivedBroadcastFrom(
        // socket.getInetAddress());
        // log().warn("Received broadcast from ? " + onLAN);

        long took = System.currentTimeMillis() - startTime;
        if (logVerbose) {
            log().verbose(
                "Connect took " + took + "ms, time differ: "
                    + ((getTimeDeltaMS() / 1000) / 60) + " min, remote ident: "
                    + getIdentity());
        }

        // Analyse connection
        analyseConnection();

        // Check this connection for keep-alive
        getController().getIOProvider().startKeepAliveCheck(this);
    }

    /**
     * Shuts down this connection handler by calling shutdown of member. If no
     * associated member is found, the con handler gets directly shut down.
     * <p>
     */
    public void shutdownWithMember() {
        if (getMember() != null) {
            // Shutdown member. This means this connection handler gets shut
            // down by member
            getMember().shutdown();
        }

        if (started) {
            // Not shutdown yet, just shut down
            shutdown();
        }
    }

    /**
     * Shuts down the connection handler. The member is shut down optionally
     */
    public void shutdown() {
        if (!started) {
            return;
        }
        if (logWarn) {
            log().warn("Shutting down");
        }
        // if (isConnected() && started) {
        // // Send "EOF" if possible, the last thing you see
        // sendMessagesAsynchron(new Problem("Closing connection, EOF", true,
        // Problem.DISCONNECTED));
        // // Give him some time to receive the message
        // waitForEmptySendQueue(1000);
        // }
        started = false;

        // Inform remote host via relay about EOF.
        if (getMember() != null) {
            RelayedMessage eofMsg = new RelayedMessage(Type.EOF,
                getController().getMySelf().getInfo(), getMember().getInfo(),
                connectionId, null);
            relay.sendMessagesAsynchron(eofMsg);
        }

        getController().getIOProvider().getRelayedConnectionManager()
            .removePedingRelayedConnectionHandler(this);

        // Clear magic ids
        // myMagicId = null;
        // identity = null;
        // Remove link to member
        setMember(null);
        // Clear send queue
        messagesToSendQueue.clear();

        getController().getIOProvider().removeKeepAliveCheck(this);

        // Trigger all waiting treads
        synchronized (identityWaiter) {
            identityWaiter.notifyAll();
        }
        synchronized (identityAcceptWaiter) {
            identityAcceptWaiter.notifyAll();
        }
        synchronized (messagesToSendQueue) {
            messagesToSendQueue.notifyAll();
        }

        // make sure the garbage collector gets this
        serializer = null;
    }

    /**
     * @return true if the connection is active
     */
    public boolean isConnected() {
        return started && relay.isConnected();
    }

    public boolean isEncrypted() {
        return false;
    }

    public boolean isOnLAN() {
        return onLAN;
    }

    public void setOnLAN(boolean onlan) {
        onLAN = onlan;
    }

    public void setMember(Member member) {
        this.member = member;
        // Logic moved into central place <code>Member.isOnLAN()</code>
        // if (!isOnLAN()
        // && member != null
        // && getController().getNodeManager().isNodeOnConfiguredLan(
        // member.getInfo()))
        // {
        // setOnLAN(true);
        // }
    }

    public Member getMember() {
        return member;
    }

    public Date getLastKeepaliveMessageTime() {
        return lastKeepaliveMessage;
    }

    /**
     * @return the aimed remote destination for this connection.
     */
    public MemberInfo getRemote() {
        return remote;
    }

    /**
     * @return the unique connection id.
     */
    public long getConnectionId() {
        return connectionId;
    }

    public boolean isAckReceived() {
        return ackReceived;
    }

    public void setAckReceived(boolean ackReceived) {
        this.ackReceived = ackReceived;
    }
    
    public boolean isNackReceived() {
        return nackReceived;
    }

    public void setNackReceived(boolean nackReceived) {
        this.nackReceived = nackReceived;
    }

    public void sendMessage(Message message) throws ConnectionException {
        if (message == null) {
            throw new NullPointerException("Message is null");
        }

        if (!isConnected()) {
            throw new ConnectionException("Connection to remote peer closed")
                .with(this);
        }

        // break if remote peer did no identitfy
        if (identity == null && (!(message instanceof Identity))) {
            throw new ConnectionException(
                "Unable to send message, peer did not identify yet").with(this);
        }

        try {
            synchronized (sendLock) {
                if (logWarn) {
                    log().warn("-- (sending) -> " + message);
                }
                if (!isConnected() || !started) {
                    throw new ConnectionException(
                        "Connection to remote peer closed").with(this);
                }
                byte[] data = serialize(message);
                RelayedMessage dataMsg = new RelayedMessage(Type.DATA_ZIPPED,
                    getController().getMySelf().getInfo(), remote,
                    connectionId, data);
                relay.sendMessage(dataMsg);

                getController().getTransferManager()
                    .getTotalUploadTrafficCounter().bytesTransferred(
                        data.length + 4);
            }
        } catch (ConnectionException e) {
            // Ensure shutdown
            shutdownWithMember();
            throw e;
        }
    }

    public void sendMessagesAsynchron(Message... messages) {
        for (Message message : messages) {
            sendMessageAsynchron(message, null);
        }
    }

    /**
     * A message to be send later. code execution does not wait util message was
     * sent successfully
     * 
     * @param message
     *            the message to be sent
     * @param errorMessage
     *            the error message to be logged on connection problem
     */
    private void sendMessageAsynchron(Message message, String errorMessage) {
        Reject.ifNull(message, "Message is null");

        senderSpawnLock.lock();
        messagesToSendQueue.offer(message);
        if (sender == null) {
            sender = new Sender();
            getController().getIOProvider().startIO(sender);
        }
        senderSpawnLock.unlock();
    }

    public long getTimeDeltaMS() {
        if (identity.getTimeGMT() == null)
            return 0;
        return myIdentity.getTimeGMT().getTimeInMillis()
            - identity.getTimeGMT().getTimeInMillis();
    }

    public boolean canMeasureTimeDifference() {
        return identity.getTimeGMT() != null;
    }

    public Identity getIdentity() {
        return identity;
    }

    public String getMyMagicId() {
        return myMagicId;
    }

    public String getRemoteMagicId() {
        return identity != null ? identity.getMagicId() : null;
    }

    /**
     * Waits until we received the remote identity
     */
    private void waitForRemoteIdentity() {
        synchronized (identityWaiter) {
            if (identity == null) {
                // wait for remote identity
                try {
                    identityWaiter.wait(60000);
                } catch (InterruptedException e) {
                    // Ignore
                    log().verbose(e);
                }
            }
        }
    }

    public boolean acceptIdentity(Member node) {
        Reject.ifNull(node, "node is null");
        // Connect member with this node
        member = node;

        // now handshake
        if (logVerbose) {
            log().verbose("Sending accept of identity to " + this);
        }
        sendMessagesAsynchron(IdentityReply.accept());

        // wait for accept of our identity
        long start = System.currentTimeMillis();
        synchronized (identityAcceptWaiter) {
            if (identityReply == null) {
                try {
                    identityAcceptWaiter.wait(20000);
                } catch (InterruptedException e) {
                    log().verbose(e);
                }
            }
        }

        long took = (System.currentTimeMillis() - start) / 1000;
        if (identityReply != null && !identityReply.accepted) {
            log()
                .warn(
                    "Remote peer rejected our connection: "
                        + identityReply.message);
            member = null;
            return false;
        }

        if (!isConnected()) {
            log().warn(
                "Remote member disconnected while waiting for identity reply. "
                    + identity);
            member = null;
            return false;
        }

        if (identityReply == null) {
            log().warn(
                "Did not receive a identity reply after " + took
                    + "s. Connected? " + isConnected() + ". remote id: "
                    + identity);
            member = null;
            return false;
        }

        if (identityReply.accepted) {
            if (logVerbose) {
                log().verbose("Identity accepted by remote peer. " + this);
            }
        } else {
            member = null;
            log().warn("Identity rejected by remote peer. " + this);
        }

        getController().getIOProvider().getRelayedConnectionManager()
            .removePedingRelayedConnectionHandler(this);

        return identityReply.accepted;
    }

    public boolean waitForEmptySendQueue(long ms) {
        long waited = 0;
        while (!messagesToSendQueue.isEmpty() && isConnected()) {
            try {
                // log().warn("Waiting for empty send buffer to " +
                // getMember());
                waited += 50;
                // Wait a bit the let the send queue get empty
                Thread.sleep(50);

                if (ms >= 0 && waited >= ms) {
                    // Stop waiting
                    break;
                }
            } catch (InterruptedException e) {
                log().verbose(e);
                break;
            }
        }
        if (waited > 0) {
            if (logVerbose) {
                log().verbose(
                    "Waited " + waited
                        + "ms for empty sendbuffer, clear now, proceeding to "
                        + getMember());
            }
        }
        return messagesToSendQueue.isEmpty();
    }

    /**
     * Analysese the connection of the user
     */
    private void analyseConnection() {
        if (Feature.CORRECT_LAN_DETECTION.isDisabled()) {
            log().warn("ON LAN because of correct connection analyse disabled");
            setOnLAN(true);
            return;
        }
        if (identity != null && identity.isTunneled()) {
            setOnLAN(false);
            return;
        }
        if (getRemoteAddress() != null
            && getRemoteAddress().getAddress() != null)
        {
            InetAddress adr = getRemoteAddress().getAddress();
            setOnLAN(NetworkUtil.isOnLanOrLoopback(adr)
                || getController().getNodeManager().isNodeOnConfiguredLan(adr));
        }

        if (logVerbose) {
            log().verbose("analyse connection: lan: " + onLAN);
        }
    }

    public boolean acceptHandshake() {
        return true;
    }

    public InetSocketAddress getRemoteAddress() {
        return getMember() != null ? getMember().getReconnectAddress() : null;
    }

    public int getRemoteListenerPort() {
        if (identity == null || identity.getMemberInfo() == null
            || identity.getMemberInfo().getConnectAddress() == null)
        {
            return -1;
        }
        if (identity.isTunneled()) {
            // No reconnection available to a tunneled connection.
            return -1;
        }

        return identity.getMemberInfo().getConnectAddress().getPort();
    }

    // Receiving **************************************************************

    /**
     * Receives and processes the relayed message.
     * 
     * @param message
     *            the message received from a relay.
     */
    public void receiveRelayedMessage(RelayedMessage message) {
        try {
            // if (!started) {
            // // Do not process this message
            // return;
            // }

            byte[] data = message.getPayload();
            Object obj = deserialize(data, data.length);

            lastKeepaliveMessage = new Date();
            getController().getTransferManager()
                .getTotalDownloadTrafficCounter().bytesTransferred(data.length);

            // Consistency check:
            // if (getMember() != null
            // && getMember().isCompleteyConnected()
            // && getMember().getPeer() !=
            // AbstractSocketConnectionHandler.this)
            // {
            // log().error(
            // "DEAD connection handler found for member: "
            // + getMember());
            // shutdown();
            // return;
            // }
            if (logWarn) {
                log().warn(
                    "<- (received, " + Format.formatBytes(data.length) + ") - "
                        + obj);
            }

            if (!getController().isStarted()) {
                log()
                    .verbose("Peer still active, shutting down " + getMember());
                shutdownWithMember();
                return;
            }

            if (obj instanceof Identity) {
                if (logVerbose) {
                    log().verbose("Received remote identity: " + obj);
                }
                // the remote identity
                identity = (Identity) obj;

                // Get magic id
                if (logVerbose) {
                    log().verbose("Received magicId: " + identity.getMagicId());
                }

                // Trigger identitywaiter
                synchronized (identityWaiter) {
                    identityWaiter.notifyAll();
                }

            } else if (obj instanceof IdentityReply) {
                if (logVerbose) {
                    log().verbose("Received identity reply: " + obj);
                }
                // remote side accpeted our identity
                identityReply = (IdentityReply) obj;

                // Trigger identity accept waiter
                synchronized (identityAcceptWaiter) {
                    identityAcceptWaiter.notifyAll();
                }
            } else if (obj instanceof Ping) {
                // Answer the ping
                Pong pong = new Pong((Ping) obj);
                sendMessagesAsynchron(pong);

            } else if (obj instanceof Pong) {
                // Do nothing.

            } else if (obj instanceof Problem) {
                Problem problem = (Problem) obj;
                if (member != null) {
                    member.handleMessage(problem);
                } else {
                    log().warn(
                        "("
                            + (identity != null
                                ? identity.getMemberInfo().nick
                                : "-") + ") Problem received: "
                            + problem.message);
                    if (problem.fatal) {
                        // Fatal problem, disconnecting
                        shutdown();
                    }
                }

            } else if (receivedObject(obj)) {
                // The object was handled by the subclass.
                // OK pass through
            } else if (obj instanceof Message) {

                if (member != null) {
                    member.handleMessage((Message) obj);
                } else {
                    log().error(
                        "Connection closed, message received, before peer identified itself: "
                            + obj);
                    // connection closed
                    shutdownWithMember();
                }
            } else {
                log().error("Received unknown message from peer: " + obj);
            }

        } catch (ConnectionException e) {
            log().verbose(e);
            logConnectionClose(e);
        } catch (ClassNotFoundException e) {
            log().verbose(e);
            log().warn(
                "Received unknown packet/class: " + e.getMessage() + " from "
                    + AbstractRelayedConnectionHandler.this);
            // do not break connection
        } catch (RuntimeException e) {
            log().error(e);
            shutdownWithMember();
            throw e;
        }

    }

    /**
     * Logs a connection closed event
     * 
     * @param e
     */
    private void logConnectionClose(Exception e) {
        String msg = "Connection closed to "
            + ((member == null) ? this.toString() : member.toString());

        if (e != null) {
            msg += ". Cause: " + e.toString();
        }
        log().debug(msg);
        log().verbose(e);
    }

    // General ****************************************************************

    public String toString() {
        return "RelayedConHan '" + remote.nick + "'";
    }

    // Inner classes **********************************************************

    /**
     * The sender class, handles all asynchron messages
     * 
     * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
     * @version $Revision: 1.72 $
     */
    class Sender implements Runnable {
        public void run() {
            if (logVerbose) {
                log().verbose(
                    "Asynchron message send triggered, sending "
                        + messagesToSendQueue.size() + " message(s)");
            }

            if (!isConnected()) {
                // Client disconnected, stop
                log().debug(
                    "Peer disconnected while sender got active. Msgs in queue: "
                        + messagesToSendQueue.size() + ": "
                        + messagesToSendQueue);
                return;
            }

            // log().warn(
            // "Sender started with " + messagesToSendQueue.size()
            // + " messages in queue");

            int i = 0;
            Message msg;
            // long start = System.currentTimeMillis();
            while (true) {
                senderSpawnLock.lock();
                msg = messagesToSendQueue.poll();
                if (msg == null) {
                    sender = null;
                    senderSpawnLock.unlock();
                    break;
                }
                senderSpawnLock.unlock();

                i++;
                if (!started) {
                    log().warn("Peer shutdown while sending: " + msg);
                    senderSpawnLock.lock();
                    sender = null;
                    senderSpawnLock.unlock();
                    shutdownWithMember();
                    break;
                }
                try {
                    // log().warn(
                    // "Sending async (" + messagesToSendQueue.size()
                    // + "): " + asyncMsg.getMessage());
                    sendMessage(msg);
                    // log().warn("Send complete: " +
                    // asyncMsg.getMessage());
                } catch (ConnectionException e) {
                    log().warn("Unable to send message asynchronly. " + e);
                    log().verbose(e);
                    senderSpawnLock.lock();
                    sender = null;
                    senderSpawnLock.unlock();
                    shutdownWithMember();
                    // Stop thread execution
                    break;
                } catch (Throwable t) {
                    log().error("Unable to send message asynchronly. " + t, t);
                    senderSpawnLock.lock();
                    sender = null;
                    senderSpawnLock.unlock();
                    shutdownWithMember();
                    // Stop thread execution
                    break;
                }
            }
            // log().warn("Sender finished after sending " + i + " messages");
        }
    }

}
