/*
 * Copyright 2004 - 2008 Christian Sprajc. All rights reserved.
 *
 * This file is part of PowerFolder.
 *
 * PowerFolder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation.
 *
 * PowerFolder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PowerFolder. If not, see <http://www.gnu.org/licenses/>.
 *
 * $Id$
 */
package de.dal33t.powerfolder.net;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.dal33t.powerfolder.ConfigurationEntry;
import de.dal33t.powerfolder.Constants;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.Feature;
import de.dal33t.powerfolder.Member;
import de.dal33t.powerfolder.PFComponent;
import de.dal33t.powerfolder.clientserver.ServerClient;
import de.dal33t.powerfolder.event.ListenerSupportFactory;
import de.dal33t.powerfolder.event.NodeManagerEvent;
import de.dal33t.powerfolder.event.NodeManagerListener;
import de.dal33t.powerfolder.light.MemberInfo;
import de.dal33t.powerfolder.message.AddFriendNotification;
import de.dal33t.powerfolder.message.Identity;
import de.dal33t.powerfolder.message.KnownNodes;
import de.dal33t.powerfolder.message.Message;
import de.dal33t.powerfolder.message.MessageListener;
import de.dal33t.powerfolder.message.Problem;
import de.dal33t.powerfolder.message.RequestNodeList;
import de.dal33t.powerfolder.message.TransferStatus;
import de.dal33t.powerfolder.util.Convert;
import de.dal33t.powerfolder.util.Debug;
import de.dal33t.powerfolder.util.IdGenerator;
import de.dal33t.powerfolder.util.MessageListenerSupport;
import de.dal33t.powerfolder.util.NamedThreadFactory;
import de.dal33t.powerfolder.util.Reject;
import de.dal33t.powerfolder.util.net.AddressRange;
import de.dal33t.powerfolder.util.net.NetworkUtil;
import de.dal33t.powerfolder.util.task.SendMessageTask;

/**
 * Managing class which takes care about all old and new nodes. reconnects those
 * who disconnected and connectes to new ones
 * 
 * @author <a href="mailto:totmacher@powerfolder.com">Christian Sprajc </a>
 * @version $Revision: 1.123 $
 */
public class NodeManager extends PFComponent {

    private static final Logger log = Logger.getLogger(NodeManager.class.getName());

    /**
     * Threadpool to handle incoming connections.
     */
    private ExecutorService threadPool;

    /** The list of active acceptors for incoming connections */
    List<AbstractAcceptor> acceptors;

    // Lock which is hold while a acception is pending
    private Object acceptLock = new Object();

    private Map<String, Member> knownNodes;
    private List<Member> friends;
    private List<Member> connectedNodes;
    private List<AddressRange> lanRanges;

    private Member mySelf;
    
    /**
     * Set containing all nodes, that went online in the meanwhile (since last
     * broadcast)
     */
    private Set<MemberInfo> nodesWentOnline;

    // Message listener which are fired for every member
    private MessageListenerSupport valveMessageListenerSupport;

    private boolean started;
    private boolean nodefileLoaded;

    private NodeManagerListener listenerSupport;

    /** Filter for the internal node database */
    private List<NodeFilter> nodeFilters;

    public NodeManager(Controller controller) {
        super(controller);

        started = false;
        nodefileLoaded = false;
        // initzialize myself if available in config
        String nick = ConfigurationEntry.NICK.getValue(getController());

        if (controller.getCommandLine() != null
            && controller.getCommandLine().hasOption("n"))
        {
            // Take nick from command line
            nick = controller.getCommandLine().getOptionValue("n");
        }
        String idKey = "PowerFolder.nodeId";
        // check for manual id
        String id = ConfigurationEntry.NODE_ID.getValue(getController());
        if (id == null) {
            id = getController().getPreferences().get(idKey, null);
            if (id == null) {
                id = IdGenerator.makeId();
                // store ID
                logInfo("Generated new ID for '" + nick + "': " + id);
            }
            ConfigurationEntry.NODE_ID.setValue(getController(), id);
        }
        String networkId = ConfigurationEntry.NETWORK_ID.getValue(getController());
        mySelf = new Member(getController(),
            new MemberInfo(nick, id, networkId));
        logInfo("I am '" + mySelf.getNick() + "'");

        // Use concurrent hashmap
        knownNodes = new ConcurrentHashMap<String, Member>();

        friends = Collections.synchronizedList(new ArrayList<Member>());
        connectedNodes = new CopyOnWriteArrayList<Member>();

        // The nodes, that went online in the meantime
        nodesWentOnline = Collections
            .synchronizedSet(new HashSet<MemberInfo>());

        // Acceptors
        acceptors = new CopyOnWriteArrayList<AbstractAcceptor>();
        
        // Value message/event listner support
        valveMessageListenerSupport = new MessageListenerSupport(this);

        this.listenerSupport = ListenerSupportFactory
            .createListenerSupport(NodeManagerListener.class);

        nodeFilters = new ArrayList<NodeFilter>();
        // Default behaviour:
        // 1) Add all nodes when acting as supernode
        // 2) Add if remote side is supernode or connected.
        nodeFilters.add(new NodeFilter() {
            public boolean shouldAddNode(MemberInfo nodeInfo) {
                boolean supernodeOrConnected = nodeInfo.isSupernode
                    || nodeInfo.isConnected;
                return mySelf.isSupernode() || supernodeOrConnected;
            }
        });

        lanRanges = new LinkedList<AddressRange>();
        String lrs[] = ConfigurationEntry.LANLIST.getValue(controller).split(
            ",");
        for (String ipr : lrs) {
            ipr = ipr.trim();
            if (ipr.length() > 0) {
                try {
                    lanRanges.add(AddressRange.parseRange(ipr));
                } catch (ParseException e) {
                    logWarning("Invalid IP range format: " + ipr);
                }
            }
        }
    }

    /**
     * Starts the node manager thread
     */
    public void start() {
        if (!ConfigurationEntry.NODEMANAGER_ENABLED
            .getValueBoolean(getController()))
        {
            logWarning("Not starting NodeManager. disabled by config");
            return;
        }
        // Starting own threads, which cares about incoming node connections
        threadPool = Executors.newCachedThreadPool(new NamedThreadFactory(
            "NodeManager-Thread-"));

        // Alternative:
        // threadPool = Executors.newFixedThreadPool(
        // Constants.MAX_INCOMING_CONNECTIONS, new NamedThreadFactory(
        // "Incoming-Connection-"));

        // load local nodes
        Thread nodefileLoader = new Thread("Nodefile loader") {
            public void run() {
                loadNodes();
                // Okay nodefile is loaded
                nodefileLoaded = true;
            }
        };
        nodefileLoader.start();

        setupPeridicalTasks();

        started = true;

        listenerSupport.startStop(new NodeManagerEvent(this, null));
        logFine("Started");
    }

    /**
     * Shuts the nodemanager down
     */
    public void shutdown() {
        // Remove listeners, not bothering them by boring shutdown events
        started = false;

        // Store the latest supernodes
        // Note: This call is here to save the nodes before shutting them down.
        storeOnlineSupernodes();

        // Stop threadpool
        if (threadPool != null) {
            logFine("Shutting down incoming connection threadpool");
            threadPool.shutdownNow();
        }

        logFine("Shutting down " + acceptors.size()
            + " incoming connections (Acceptors)");
        for (AbstractAcceptor acceptor : acceptors) {
            acceptor.shutdown();
        }
        acceptors.clear();

        logFine("Shutting down nodes");

        Collection<Member> conNode = new ArrayList<Member>(connectedNodes);
        logFine("Shutting down connected nodes (" + conNode.size() + ")");
        ExecutorService shutdownThreadPool = Executors.newFixedThreadPool(Math.max(1,
            conNode.size() / 5));
        Collection<Future<?>> shutdowns = new ArrayList<Future<?>>();
        for (final Member node : conNode) {
            Runnable killer = new Runnable() {
                public void run() {
                    node.shutdown();
                }
            };
            shutdowns.add(shutdownThreadPool.submit(killer));
        }

        for (Future<?> future : shutdowns) {
            try {
                future.get();
            } catch (InterruptedException e) {
                logFiner("InterruptedException", e);
                break;
            } catch (ExecutionException e) {
            }
        }
        shutdownThreadPool.shutdownNow();

        // "Traditional" shutdown
        logFine("Shutting down " + knownNodes.size() + " nodes");
        for (Member node : getNodesAsCollection()) {
            node.shutdown();
        }

        // first save current members connection state
        if (nodefileLoaded) {
            // Only store if was fully started
            storeNodes();
            // Shutdown, unloaded nodefile
            nodefileLoaded = false;
        }
        listenerSupport.startStop(new NodeManagerEvent(this, null));
        logFine("Stopped");
    }

    /**
     * for debug
     * 
     * @param suspended
     */
    public void setSuspendFireEvents(boolean suspended) {
        ListenerSupportFactory.setSuspended(listenerSupport, suspended);
        logFine("setSuspendFireEvents: " + suspended);
    }

    /**
     * @return true if the nodemanager is started
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * @param node
     *            the node to ask the friend status for
     * @return true if that node is on the friendlist.
     */
    public boolean isFriend(Member node) {
        if (node.isMySelf()) {
            return true;
        }
        return friends.contains(node);
    }

    /**
     * @return the number of nodes, which are online on the network.
     */
    public int countOnlineNodes() {
        int nConnected = 1;
        synchronized (knownNodes) {
            for (Member node : knownNodes.values()) {
                if (node.isConnected() || node.isConnectedToNetwork()) {
                    nConnected++;
                }
            }
        }
        return nConnected;
    }

    /**
     * @return the number of supernodes, which are online on the network.
     */
    public int countOnlineSupernodes() {
        int nConnected = 1;
        for (Member node : knownNodes.values()) {
            if (node.isSupernode()
                && (node.isConnected() || node.isConnectedToNetwork()))
            {
                nConnected++;
            }
        }
        return nConnected;
    }

    /**
     * @return the number of known supernodes.
     */
    public int countSupernodes() {
        int nSupernodes = 0;
        for (Member node : knownNodes.values()) {
            if (node.isSupernode()) {
                nSupernodes++;
            }
        }
        return nSupernodes;
    }

    /**
     * @return the number of connected supernodes.
     */
    public int countConnectedSupernodes() {
        int nSupernodes = 0;
        for (Member node : connectedNodes) {
            if (node.isSupernode()) {
                nSupernodes++;
            }
        }
        return nSupernodes;
    }

    /**
     * @return if we have reached the maximum number of connections allwed
     */
    public boolean maxConnectionsReached() {
        // Assume unlimited upload
        if (getController().getTransferManager().getAllowedUploadCPSForWAN() <= 0)
        {
            // Unlimited upload
            return false;
        }
        // logWarning("Max allowed: " +
        // getController().getTransferManager().getAllowedUploadCPS());
        double uploadKBs = ((double) getController().getTransferManager()
            .getAllowedUploadCPSForWAN()) / 1024;
        int nConnected = countConnectedNodes();
        int maxConnectionsAllowed = (int) (uploadKBs * Constants.MAX_NODES_CONNECTIONS_PER_KBS_UPLOAD);

        if (nConnected > maxConnectionsAllowed) {
            logFiner("Not more connection slots open. Used " + nConnected + "/"
                + maxConnectionsAllowed);
        }

        // Still capable of new connections?
        return nConnected > maxConnectionsAllowed;
    }

    /**
     * @return the own identity, of course with no connection
     */
    public Member getMySelf() {
        return mySelf;
    }
    
    /**
     * @return the network ID this nodemanager belongs to. #1373
     */
    public String getNetworkId() {
        return mySelf.getInfo().networkId;
    }

    /**
     * @param member
     *            the member to check
     * @return if we know this member
     */
    public boolean knowsNode(Member member) {
        if (member == null) {
            return false;
        }
        // do i know him ?
        return knowsNode(member.getId());
    }

    /**
     * Returns true if the IP of the given member is within one of the
     * configured ranges Those are setup in advanced settings "LANlist".
     * 
     * @param adr
     *            the internet addedss
     * @return true if the member's ip is within one of the ranges
     */
    private boolean isNodeOnConfiguredLan(InetAddress adr) {
        for (AddressRange ar : lanRanges) {
            if (ar.contains((Inet4Address) adr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param adr
     *            the internet addedss
     * @return true if this address is on LAN (by IP/subnet mask) OR configured
     *         on LAN
     */
    public boolean isOnLANorConfiguredOnLAN(InetAddress adr) {
        Reject.ifNull(adr, "Address is null");
        if (!(adr instanceof Inet4Address)) {
            return false;
        }
        if (Feature.CORRECT_LAN_DETECTION.isDisabled()) {
            return true;
        }
        if (Feature.CORRECT_INTERNET_DETECTION.isDisabled()) {
            return false;
        }
        return NetworkUtil.isOnLanOrLoopback(adr)
            || isNodeOnConfiguredLan(adr)
            || (getController().getBroadcastManager() != null && getController()
                .getBroadcastManager().receivedBroadcast(adr));
    }

    /**
     * @param member
     * @return if we know this member
     */
    public boolean knowsNode(MemberInfo member) {
        if (member == null) {
            return false;
        }
        return knowsNode(member.id);
    }

    /**
     * @return the number of connected nodes
     */
    public int countConnectedNodes() {
        return connectedNodes.size();
    }

    /**
     * ATTENTION: May change after returned! Make copy if stable state is
     * required.
     * 
     * @return a unmodifiable version of the internal list of connected nodes.
     */
    public Collection<Member> getConnectedNodes() {
        return Collections.unmodifiableCollection(connectedNodes);
    }

    /**
     * @param id
     *            the id of the member
     * @return true if we know this member
     */
    public boolean knowsNode(String id) {
        if (id == null) {
            return false;
        }
        // do i know him ?
        return id.equals(mySelf.getId()) || knownNodes.containsKey(id);
    }

    /**
     * @param mInfo
     *            the memberinfo
     * @return the member for a memberinfo
     */
    public Member getNode(MemberInfo mInfo) {
        if (mInfo == null) {
            return null;
        }
        return getNode(mInfo.id);
    }

    /**
     * @param id
     * @return the member for this id
     */
    public Member getNode(String id) {
        if (id == null) {
            return null;
        }
        if (mySelf.getId().equals(id)) {
            return mySelf;
        }
        return knownNodes.get(id);
    }

    /**
     * @return all known nodes in a collection. The collection is a unmodifiable
     *         referece to the internal know nodes storage. May change after has
     *         been returned!
     */
    public Collection<Member> getNodesAsCollection() {
        return Collections.unmodifiableCollection(knownNodes.values());
    }

    /**
     * Removes a member from the known list
     * 
     * @param node
     */
    public void removeNode(Member node) {
        if (isFiner()) {
            logFiner("Removing " + node.getNick() + " from nodelist");
        }
        // Shut down node
        node.shutdown();

        // removed from folders
        getController().getFolderRepository().removeFromAllFolders(node);
        knownNodes.remove(node.getId());

        // Remove all his listeners
        node.removeAllListeners();

        // Fire event
        fireNodeRemoved(node);
        logFine(node + " removed from from know nodes list");
    }

    /**
     * Counts the number of friends that are online
     * 
     * @return the number of friends that are online
     */
    public int countOnlineFriends() {
        int nOnlineFriends = 0;
        for (Member friend : getFriends()) {
            if (friend.isConnectedToNetwork()) {
                nOnlineFriends++;
            }
        }
        return nOnlineFriends;
    }

    /**
     * @return the number of friends
     */
    public int countFriends() {
        return friends.size();
    }

    /**
     * @return the list of friends
     */
    public Member[] getFriends() {
        synchronized (friends) {
            Member[] friendsArr = new Member[friends.size()];
            friends.toArray(friendsArr);
            return friendsArr;
        }
    }

    /**
     * Called by member. Not getting this event from event handling because we
     * have to handle things definitivily before other elements work on that
     * 
     * @param node
     * @param friend
     * @param personalMessage 
     */
    public void friendStateChanged(Member node, boolean friend,
        String personalMessage)
    {
        if (node.isMySelf()) {
            // Ignore change on myself
            return;
        }

        boolean wasFriend = node.isFriend();
        boolean nodesChanged = false;

        if (friend) {
            friends.add(node);
            nodesChanged = true;
            fireFriendAdded(node);
            // Mark node for immediate connection
            node.markForImmediateConnect();
            // Send a "you were added"
            if (getController().getTaskManager().isStarted()) {
                getController().getTaskManager().scheduleTask(
                    new SendMessageTask(new AddFriendNotification(mySelf
                        .getInfo(), personalMessage), node.getId()));
            }
        } else if (wasFriend) {
            friends.remove(node);
            nodesChanged = true;
            fireFriendRemoved(node);
        }

        if (nodefileLoaded && nodesChanged) {
            // Only store after start
            // Store nodes
            storeNodes();
        }
    }

    /**
     * Callback method from node to inform nodemanager about an online state
     * change
     * 
     * @param node
     */
    public void onlineStateChanged(Member node) {
        boolean nodeConnected = node.isCompleteyConnected();
        if (nodeConnected) {
            // Add to online nodes
            connectedNodes.add(node);
            // add to broadcastlist
            nodesWentOnline.add(node.getInfo());

            if (!mySelf.isSupernode()
                && countConnectedSupernodes() >= Constants.N_SUPERNODES_TO_CONNECT)
            {
                // # of necessary connections probably reached, avoid more
                // reconnection tries.
                logFine("Max # of connections reached. "
                    + "Rebuilding reconnection queue");
                getController().getReconnectManager().buildReconnectionQueue();
            }
            if (getController().getIOProvider().getRelayedConnectionManager()
                .isRelay(node.getInfo()))
            {
                logFine("Connect to relay detected. Rebuilding reconnection queue");
                getController().getReconnectManager().buildReconnectionQueue();
            }
        } else {
            // Remove from list
            connectedNodes.remove(node);
            nodesWentOnline.remove(node.getInfo());

            getController().getTransferManager().breakTransfers(node);
        }

        // Event handling
        if (nodeConnected) {
            fireNodeConnected(node);
        } else {
            fireNodeDisconnected(node);
        }
    }

    /**
     * Callback method from Member.
     * 
     * @param from
     * @param message
     */
    public void messageReceived(Member from, Message message) {
        valveMessageListenerSupport.fireMessage(from, message);
    }

    /**
     * Processes a request for nodelist.
     * 
     * @param request
     *            the request.
     * @param from
     *            the origin of the request
     */
    public void receivedRequestNodeList(RequestNodeList request, Member from) {
        List<MemberInfo> list;
        list = request.filter(knownNodes.values());
        from.sendMessagesAsynchron(KnownNodes.createKnownNodesList(list));
    }

    /**
     * Creates the default request for nodelist according to our own status. In
     * supernode mode we might want to request more node information that in
     * normal peer mode.
     * <p>
     * Attention: This method synchronizes on the internal friendlist. Avoid
     * holding a lock while calling this method.
     * 
     * @return the message.
     */
    public RequestNodeList createDefaultNodeListRequestMessage() {
        if (mySelf.isSupernode()) {
            return RequestNodeList.createRequestAllNodes();
        }
        synchronized (friends) {
            // TODO Change second paramter to RequestNodeList.NodesCriteria.NONE
            // when network folder list also covers private folders.
            return RequestNodeList.createRequest(friends,
                RequestNodeList.NodesCriteria.ONLINE,
                RequestNodeList.NodesCriteria.ONLINE);
        }
    }

    /**
     * Processes new node informations. Reconnects if required.
     * 
     * @param newNodes
     *            the new nodes to queue.
     */
    public void queueNewNodes(MemberInfo[] newNodes) {
        if (newNodes == null || newNodes.length == 0) {
            return;
        }

        // queue new members
        if (isFiner()) {
            logFiner("Received new list of " + newNodes.length + " nodes");
        }

        int nNewNodes = 0;
        int nQueuedNodes = 0;
        ReconnectManager reconnectManger = getController()
            .getReconnectManager();

        for (int i = 0; i < newNodes.length; i++) {
            MemberInfo newNode = newNodes[i];

            // just check, for a faster shutdown
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (newNode == null || newNode.isInvalid(getController())) {
                // Member is too old, ignore
                if (isFiner()) {
                    logFiner("Not adding new node: " + newNode);
                }
                continue;
            }

            if (!newNode.isOnSameNetwork(getController())) {
                // Never add nodes from other networks
                continue;
            }

            // Ask filters if this node is valueable to us
            boolean ignoreNode = true;
            for (NodeFilter filter : nodeFilters) {
                if (filter.shouldAddNode(newNode)) {
                    ignoreNode = false;
                    break;
                }
            }

            if (!ignoreNode) {
                // Ignore temporary nodes
                if (ServerClient.isTempServerNode(newNode)) {
                    logWarning("Ignoring temporary server node: " + newNode);
                    ignoreNode = true;
                }
            }
            // Disabled: This causes problems when executing a search for users
            // blocks that normal nodes get added to the database.
            // boolean supernodeOrConnected = newNode.isSupernode
            // || newNode.isConnected;
            // if (!mySelf.isSupernode() && !supernodeOrConnected) {
            // // Skip unuselful nodes
            // continue;
            // }

            if (ignoreNode) {
                // Skil unuseful nodes
                continue;
            }

            Member thisNode = getNode(newNode);
            if (newNode.matches(mySelf)) {
                // ignore myself
                continue;
            } else if (getController().isLanOnly()
                && (newNode.getConnectAddress() != null)
                && (newNode.getConnectAddress().getAddress() != null)
                && !getController().getNodeManager().isOnLANorConfiguredOnLAN(
                    newNode.getConnectAddress().getAddress()))
            {
                // ignore if lan only mode && newNode not is onlan
                continue;
            } else if (thisNode == null) {
                // add node
                thisNode = addNode(newNode);
                nNewNodes++;
            } else {
                // update own information if more valueable
                thisNode.updateInfo(newNode);
            }

            if (newNode.isConnected) {
                // Node is connected to the network
                thisNode.setConnectedToNetwork(newNode.isConnected);
                boolean queued = reconnectManger
                    .considerReconnectionTo(thisNode);
                if (queued) {
                    nQueuedNodes++;
                }
            }

        }

        if (nQueuedNodes > 0 || nNewNodes > 0) {
            if (isFiner()) {
                logFiner("Queued " + nQueuedNodes
                    + " new nodes for reconnection, " + nNewNodes + " added");
            }
        }
    }

    /**
     * Accept a node, method does not block.
     * 
     * @param acceptor
     */
    public void acceptConnectionAsynchron(AbstractAcceptor acceptor) {
        Reject.ifNull(acceptor, "Acceptor is null");
        // Create acceptor on socket

        if (!started) {
            logWarning("Not accepting connection " + acceptor
                + ". NodeManager is not started");
            acceptor.shutdown();
            return;
        }

        if (isFiner()) {
            logFiner("Connection queued for acception: " + acceptor + "");
        }

        // Enqueue for later processing
        acceptors.add(acceptor);
        threadPool.submit(acceptor);

        // Throttle acception a bit depending on how much incoming connections
        // we are currently processing.
        long waitTime = (acceptors.size() * Controller.getWaitTime()) / 400;
        if (isFiner()) {
            logFiner(
                "Currently processing incoming connections ("
                + acceptors.size() + "), throttled (" + waitTime + "ms wait)");
        }
        if (acceptors.size() > Constants.MAX_INCOMING_CONNECTIONS) {
            // Show warning
            logWarning("Processing too many incoming connections ("
                + acceptors.size() + "), throttled (" + waitTime + "ms wait)");
        }
        try {
            Thread.sleep(waitTime);
        } catch (InterruptedException e) {
            logFiner(e);
        }
    }

    /**
     * Internal method for accepting nodes on a connection handler
     * 
     * @param handler
     * @throws ConnectionException
     * @return the connected node or null if problem occoured
     */
    public Member acceptConnection(ConnectionHandler handler)
        throws ConnectionException
    {
        if (!started) {
            logWarning("Not accepting node from " + handler
                + ". NodeManager is not started");
            handler.shutdown();
            throw new ConnectionException("Not accepting node from " + handler
                + ". NodeManager is not started").with(handler);
        }

        // Accepts a node from a connection handler
        Identity remoteIdentity = handler.getIdentity();

        // check for valid identity
        if (remoteIdentity == null || !remoteIdentity.isValid()) {
            logWarning("Received an illegal identity from " + handler
                + ". disconnecting. " + remoteIdentity);
            handler.shutdown();
            throw new ConnectionException("Received an illegal identity from "
                + handler + ". disconnecting. " + remoteIdentity).with(handler);
        }

        if (getMySelf().getInfo().equals(remoteIdentity.getMemberInfo())) {
            logWarning("Loopback connection detected to " + handler
                + ", disconnecting");
            handler.shutdown();
            throw new ConnectionException("Loopback connection detected to "
                + handler + ", disconnecting").with(handler);
        }

        Member member;
        // Accept node ?
        boolean acceptHandler;
        String rejectCause = null;

        // Accept only one node at a time
        synchronized (acceptLock) {
            if (isFiner()) {
                logFiner("Accept lock taken. Member: "
                    + remoteIdentity.getMemberInfo() + ", Handler: " + handler);
            }
            // Is this member already known to us ?
            member = getNode(remoteIdentity.getMemberInfo());

            if (member == null) {
                // Create new node
                member = new Member(getController(), handler.getIdentity()
                    .getMemberInfo());

                // add node
                addNode(member);

                // Accept handler
                acceptHandler = true;
            } else {
                // Old member, check its connection state
                if (!member.isOnLAN() && handler.isOnLAN()) {
                    // Only accept hanlder, if our one is disco! or our is not
                    // on LAN
                    acceptHandler = true;
                    // #841 NOT isCompletelyConnected()
                } else if (member.isConnected()) {
                    rejectCause = "Duplicate connection detected to "
                        + member.getNick() + " ("
                        + member.getReconnectAddress() + ")";
                    // Not accept node
                    acceptHandler = false;
                    // SKIP: Causes problems in LAN environment
                    // } else if (member.isReconnecting()) {
                    // rejectCause = "Already reconnecting";
                    // logWarning("Not accepting, already connecting to :"
                    // + member);
                    // acceptHandler = false;
                } else {
                    // Otherwise accept. (our member = disco)
                    acceptHandler = true;
                }
            }
            if (isFiner()) {
                logFiner("Accept lock released. Member: "
                    + remoteIdentity.getMemberInfo() + ", Handler: " + handler);
            }
        }

        if (acceptHandler) {
            if (member.isConnected()) {
                logWarning("Taking a better conHandler for " + member.getNick());
            }
            // Complete handshake
            if (!member.setPeer(handler)) {
                throw new ConnectionException("Unable to connect to node "
                    + member);
            }
        } else {
            logWarning(rejectCause + ", connected? " + handler.isConnected());
            // Tell remote side, fatal problem
            try {
                handler.sendMessage(new Problem(rejectCause, true,
                    Problem.DUPLICATE_CONNECTION));
            } finally {
                handler.shutdown();
            }
            throw new ConnectionException(rejectCause + ", connected? "
                + handler.isConnected());
        }
        return member;
    }

    /**
     * Adds a new node to the nodemanager. Initalize from memberinfo
     * 
     * @param newNode
     * @return the new node
     */
    public Member addNode(MemberInfo newNode) {
        Member member = new Member(getController(), newNode);
        addNode(member);
        return member;
    }

    /**
     * Adds a new node to the nodemanger
     * 
     * @param node
     *            the new node
     */
    private void addNode(Member node) {
        if (node == null) {
            throw new NullPointerException("Node is null");
        }
        // logFiner("Adding new node: " + node);

        Member oldNode = knownNodes.get(node.getId());
        if (oldNode != null) {
            logWarning("Overwriting old node: " + node);
            removeNode(oldNode);
        }

        knownNodes.put(node.getId(), node);
        
        if (!node.isOnSameNetwork()) {
            logWarning(
                "Added node with diffrent network id. Our netID: " + getNetworkId()
                    + ", node netID: " + node.getInfo().networkId + ". "
                    + node, new RuntimeException("here"));
        }

        // Fire new node event
        fireNodeAdded(node);
    }

    /**
     * Broadcasts a message to all nodes, does not block. Message enqueued to be
     * sent asynchron
     * 
     * @param message
     */
    public void broadcastMessage(final Message message) {
        if (!started) {
            logWarning("Not started. Not broadcasting message: " + message);
            return;
        }
        if (isFiner()) {
            logFiner("Broadcasting message: " + message);
        }
        Runnable broadcaster = new Runnable() {
            public void run() {
                for (Member node : knownNodes.values()) {
                    if (node.isCompleteyConnected()) {
                        // Only broadcast after completely connected
                        node.sendMessageAsynchron(message, null);
                        try {
                            // Slight delay to prevent abnormal threadpool
                            // growth of Sender threads.
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            logFiner("InterruptedException", e);
                            break;
                        }
                    }
                }
            }
        };
        threadPool.submit(broadcaster);
    }

    /**
     * Broadcasts a message along a number of supernodes
     * 
     * @param message
     *            the message to broadcast
     * @param nSupernodes
     *            the maximum numbers to supernodes to send the message to. 0 or
     *            lower means to all supernodes
     * @return the number of nodes where the message has been broadcasted
     */
    public int broadcastMessageToSupernodes(Message message, int nSupernodes) {
        if (!started) {
            logWarning("Not started. Not broadcasting message: " + message);
            return 0;
        }
        if (isFiner()) {
            logFiner("Broadcasting message to supernodes: " + message);
        }
        int nNodes = 0;
        List<Member> supernodes = new LinkedList<Member>();
        for (Member node : knownNodes.values()) {
            if (node.isCompleteyConnected() && node.isSupernode()) {
                // Only broadcast after completely connected
                supernodes.add(node);
            }
        }

        if (nSupernodes <= 0) {
            // Broadcast to all supernodes
            nSupernodes = supernodes.size();
        }

        nSupernodes = Math.min(supernodes.size(), nSupernodes);
        for (int i = 0; i < nSupernodes; i++) {
            // Take a random supernode
            int index = (int) (Math.random() * supernodes.size());
            Member supernode = supernodes.get(index);
            supernodes.remove(index);
            logFine("Sending message to supernode: " + supernode.getNick()
                + ". " + message);
            supernode.sendMessageAsynchron(message, null);
            nNodes++;
        }

        return nNodes;
    }

    /**
     * Broadcasts a message along a number of nodes on lan
     * 
     * @param message
     *            the message to broadcast
     * @param nBroadcasted
     *            the maximum numbers of lan nodes to send the message to. 0 or
     *            lower means to all nodes on lan
     * @return the number of nodes where the message has been broadcasted
     */
    public int broadcastMessageLANNodes(Message message, int nBroadcasted) {
        if (!started) {
            logWarning("Not started. Not broadcasting message: " + message);
            return 0;
        }
        if (isFiner()) {
            logFiner("Broadcasting message to LAN nodes: " + message);
        }
        int nNodes = 0;
        List<Member> lanNodes = new LinkedList<Member>();
        for (Member node : knownNodes.values()) {
            if (node.isCompleteyConnected() && node.isOnLAN()) {
                // Only broadcast after completely connected
                lanNodes.add(node);
            }
        }
        if (nBroadcasted <= 0) {
            // Broadcast to all supernodes
            nBroadcasted = lanNodes.size();
        }

        nBroadcasted = Math.min(lanNodes.size(), nBroadcasted);
        for (int i = 0; i < nBroadcasted; i++) {
            // Take a random supernode
            int index = (int) (Math.random() * lanNodes.size());
            Member node = lanNodes.get(index);
            lanNodes.remove(index);
            logFine("Sending message to lan node: " + node.getNick() + ". "
                + message);
            node.sendMessageAsynchron(message, null);
            nNodes++;
        }

        return nNodes;
    }

    /**
     * Loads members from disk and adds them
     * 
     * @param nodeList
     */
    private boolean loadNodesFrom(String filename) {
        File nodesFile = new File(Controller.getMiscFilesLocation(), filename);
        if (!nodesFile.exists()) {
            // Try harder in local base
            nodesFile = new File(filename);
        }

        if (!nodesFile.exists()) {
            logFine("Unable to load nodes, file not found "
                + nodesFile.getAbsolutePath());
            return false;
        }

        try {
            NodeList nodeList = new NodeList();
            nodeList.load(nodesFile);

            logInfo("Loaded " + nodeList.getNodeList().size() + " nodes from "
                + nodesFile.getAbsolutePath());
            queueNewNodes(nodeList.getNodeList().toArray(
                new MemberInfo[nodeList.getNodeList().size()]));

            for (MemberInfo friend : nodeList.getFriendsSet()) {
                Member node = friend.getNode(getController(), true);
                if (!this.friends.contains(node) && !node.isMySelf()) {
                    this.friends.add(node);
                }
            }
            return !nodeList.getNodeList().isEmpty();
        } catch (IOException e) {
            logWarning("Unable to load nodes from file '" + filename + "'. "
                + e.getMessage());
            logFiner("IOException", e);
        } catch (ClassCastException e) {
            logWarning("Illegal format of supernodes files '" + filename
                + "', deleted");
            logFiner("ClassCastException", e);
            if (!nodesFile.delete()) {
                logSevere("Failed to delete supernodes file: "
                    + nodesFile.getAbsolutePath());
            }
        } catch (ClassNotFoundException e) {
            logWarning("Illegal format of supernodes files '" + filename
                + "', deleted");
            logFiner("ClassNotFoundException", e);
            nodesFile.delete();
        }
        return false;
    }

    /**
     * Loads members from disk and connects to them
     */
    private void loadNodes() {
        String filename = getController().getConfigName() + ".nodes";
        if (!loadNodesFrom(filename)) {
            filename += ".backup";
            logFine("Failed to load nodes, trying backup nodefile '" + filename
                + "'");
            if (!loadNodesFrom(filename)) {
                return;
            }
        }
    }

    /**
     * Saves the state of current members/connections. members will get
     * reconnected at start
     */
    private void storeNodes() {
        Collection<MemberInfo> allNodesInfos = Convert.asMemberInfos(knownNodes
            .values());
        allNodesInfos.add(getMySelf().getInfo());
        // Add myself to know nodes
        Collection<MemberInfo> friendInfos = Convert.asMemberInfos(friends);
        NodeList nodeList = new NodeList(allNodesInfos, friendInfos);

        if (storeNodes0(getController().getConfigName() + ".nodes", nodeList)) {
            // Store backup if file could be written.
            storeNodes0(getController().getConfigName() + ".nodes.backup",
                nodeList);
        }
    }

    /**
     * Stores the supernodes that are currently online in a separate file.
     */
    private void storeOnlineSupernodes() {
        Collection<MemberInfo> latestSupernodesInfos = new ArrayList<MemberInfo>();
        Collection<Member> latestSupernodes = new ArrayList<Member>();
        for (Member node : getNodesAsCollection()) {
            if (!node.isSupernode()) {
                // Skip non-supernode
                continue;
            }
            if (!node.isConnectedToNetwork()) {
                continue;
            }
            latestSupernodesInfos.add(node.getInfo());
            latestSupernodes.add(node);
        }
        if (getMySelf().isSupernode()) {
            latestSupernodesInfos.add(getMySelf().getInfo());
            latestSupernodes.add(getMySelf());
        }
        if (getController().isVerbose()) {
            Debug.writeNodeListCSV(latestSupernodes, "SupernodesOnline.csv");
        }
        NodeList nodeList = new NodeList(latestSupernodesInfos, null);
        storeNodes0(getController().getConfigName() + "-Supernodes.nodes",
            nodeList);
    }

    /**
     * Internal method for storing nodes into a files
     * <p>
     */
    private boolean storeNodes0(String filename, NodeList nodeList) {
        File nodesFile = new File(Controller.getMiscFilesLocation(), filename);
        if (!nodesFile.getParentFile().exists()) {
            // for testing this directory needs to be created because we have
            // subs in the config name
            if (!nodesFile.getParentFile().mkdirs()) {
                logSevere("Failed to create directory: "
                    + nodesFile.getAbsolutePath());
            }
        }

        if (nodeList.getNodeList().isEmpty()) {
            logFine("Not storing list of nodes, none known");
            return false;
        }

        logFine("Saving known nodes/friends with "
            + nodeList.getNodeList().size() + " nodes to " + filename);
        try {
            nodeList.save(nodesFile);
            return true;
        } catch (IOException e) {
            logWarning("Unable to write supernodes to file '" + filename
                + "'. " + e.getMessage());
            logFiner("IOException", e);
            return false;
        }
    }

    // Message listener code **************************************************

    public void addMessageListenerToAllNodes(MessageListener listener) {
        valveMessageListenerSupport.addMessageListener(listener);
    }

    public void addMessageListenerToAllNodes(Class<? extends Message> messageType,
        MessageListener listener)
    {
        valveMessageListenerSupport.addMessageListener(messageType, listener);
    }

    public void removeMessageListener(MessageListener listener) {
        valveMessageListenerSupport.removeMessageListener(listener);
    }

    // Internal classes *******************************************************
    
    /**
     * Sets up all tasks, that needs to be periodically executed.
     */
    private void setupPeridicalTasks() {
        // Broadcast transfer status
        getController().scheduleAndRepeat(new TransferStatusBroadcaster(),
            Constants.TRANSFER_STATUS_BROADCAST_INTERVAL * 1000 / 2,
            Constants.TRANSFER_STATUS_BROADCAST_INTERVAL * 1000);
        // Request network folder list
        // timer.schedule(new NetworkFolderListRequestor(),
        // Constants.NETWORK_FOLDER_LIST_REQUEST_INTERVAL * 1000 / 2,
        // Constants.NETWORK_FOLDER_LIST_REQUEST_INTERVAL * 1000);
        // Request new node list from time to time
        getController().scheduleAndRepeat(new NodeListRequestor(),
            Constants.NODE_LIST_REQUEST_INTERVAL * 1000 / 2,
            Constants.NODE_LIST_REQUEST_INTERVAL * 1000);
        // Broadcast the nodes that went online
        getController().scheduleAndRepeat(
            new NodesThatWentOnlineListBroadcaster(),
            Constants.NODES_THAN_WENT_ONLINE_BROADCAST_TIME * 1000 / 2,
            Constants.NODES_THAN_WENT_ONLINE_BROADCAST_TIME * 1000);
        // Check incoming connection tries
        getController().scheduleAndRepeat(new AcceptorsChecker(), 0,
            Constants.INCOMING_CONNECTION_CHECK_TIME * 1000);

        // Write statistics and other infos.
        if (getController().isVerbose()) {
            getController().scheduleAndRepeat(new StatisticsWriter(),
                59 * 1000, 60 * 1000);
        }
    }

    // Workers ****************************************************************

    /**
     * Broadcasts the transferstatus
     */
    private class TransferStatusBroadcaster extends TimerTask {
        @Override
        public void run() {
            // Broadcast new transfer status
            TransferStatus status = getController().getTransferManager()
                .getStatus();
            if (isFiner()) {
                logFiner("Broadcasting transfer status: " + status);
            }
            broadcastMessage(status);
        }
    }

    /**
     * Broadcasts all nodes, that went online since the last execution.
     */
    private class NodesThatWentOnlineListBroadcaster extends TimerTask {
        @Override
        public void run() {
            if (nodesWentOnline.isEmpty()) {
                return;
            }
            logFine("Broadcasting " + nodesWentOnline.size()
                + " nodes that went online");
            KnownNodes nodesWentOnlineMessage;
            synchronized (nodesWentOnline) {
                MemberInfo[] nodes = new MemberInfo[nodesWentOnline.size()];
                nodesWentOnline.toArray(nodes);
                nodesWentOnlineMessage = new KnownNodes(nodes);
                nodesWentOnline.clear();
            }
            broadcastMessage(nodesWentOnlineMessage);
        }
    }

    /**
     * Checks the currently attempted connection tries for timeouts.
     */
    private class AcceptorsChecker extends TimerTask {
        @Override
        public void run() {
            ThreadPoolExecutor es = (ThreadPoolExecutor) threadPool;
            int size = acceptors.size();
            logFine(
                "Checking incoming connection queue (" + size + ", "
                    + es.getActiveCount() + "/" + es.getCorePoolSize()
                    + " threads)");
            if (size > Constants.MAX_INCOMING_CONNECTIONS) {
                logWarning(
                    "Processing too many incoming connections (" + size + ", "
                        + es.getActiveCount() + "/" + es.getCorePoolSize()
                        + " threads)");
            }
            List<AbstractAcceptor> timeouted = new ArrayList<AbstractAcceptor>();
            for (AbstractAcceptor acceptor : acceptors) {
                if (acceptor.hasTimeout()) {
                    timeouted.add(acceptor);
                }
            }
            for (AbstractAcceptor acceptor : timeouted) {
                logFine("Acceptor has timeout: " + acceptor);
                acceptor.shutdown();
                acceptors.remove(acceptor);
            }
        }
    }

    /**
     * Requests the required nodelist.
     */
    private class NodeListRequestor extends TimerTask {
        @Override
        public void run() {
            // Request new nodelist from supernodes
            RequestNodeList request = createDefaultNodeListRequestMessage();
            if (log.isLoggable(Level.FINE)) {
                logFine("Requesting nodelist: " + request);
            }
            broadcastMessageToSupernodes(request,
                Constants.N_SUPERNODES_TO_CONTACT_FOR_NODE_LIST);
        }
    }

    /**
     * Writes the statistic to disk.
     */
    private class StatisticsWriter extends TimerTask {
        @Override
        public void run() {
            Debug.writeStatistics(getController());
            Debug.writeNodeListCSV(getController().getReconnectManager()
                .getReconnectionQueue(), "ReconnectionQueue.csv");
        }
    }

    // Listener support *******************************************************

    public void addNodeManagerListener(NodeManagerListener listener) {
        ListenerSupportFactory.addListener(listenerSupport, listener);
    }

    public void removeNodeManagerListener(NodeManagerListener listener) {
        ListenerSupportFactory.removeListener(listenerSupport, listener);
    }

    public void addNodeFilter(NodeFilter filter) {
        Reject.ifNull(filter, "Filter is null");
        nodeFilters.add(filter);
    }

    public void removeNodeFilter(NodeFilter filter) {
        Reject.ifNull(filter, "Filter is null");
        nodeFilters.remove(filter);
    }

    // Helper *****************************************************************

    private void fireNodeRemoved(Member node) {
        listenerSupport.nodeRemoved(new NodeManagerEvent(this, node));

    }

    private void fireNodeAdded(final Member node) {
        listenerSupport.nodeAdded(new NodeManagerEvent(this, node));
    }

    private void fireNodeConnected(final Member node) {
        listenerSupport.nodeConnected(new NodeManagerEvent(this, node));
    }

    private void fireNodeDisconnected(final Member node) {
        listenerSupport.nodeDisconnected(new NodeManagerEvent(this, node));
    }

    private void fireFriendAdded(final Member node) {
        listenerSupport.friendAdded(new NodeManagerEvent(this, node));
    }

    private void fireFriendRemoved(final Member node) {
        listenerSupport.friendRemoved(new NodeManagerEvent(this, node));
    }

    public void fireNodeSettingsChanged(final Member node) {
        listenerSupport.settingsChanged(new NodeManagerEvent(this, node));
    }
}