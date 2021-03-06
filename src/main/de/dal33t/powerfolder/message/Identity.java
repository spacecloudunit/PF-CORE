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
package de.dal33t.powerfolder.message;

import com.google.protobuf.AbstractMessage;
import de.dal33t.powerfolder.ConfigurationEntry;
import de.dal33t.powerfolder.Controller;
import de.dal33t.powerfolder.d2d.D2DObject;
import de.dal33t.powerfolder.light.MemberInfo;
import de.dal33t.powerfolder.net.ConnectionHandler;
import de.dal33t.powerfolder.protocol.IdentityProto;
import de.dal33t.powerfolder.protocol.NodeInfoProto;
import de.dal33t.powerfolder.util.Reject;

import java.io.Externalizable;
import java.util.Calendar;

/**
 * Message which contains information about me.
 *
 * @author Christian Sprajc
 *
 * @version $Revision: 1.6 $
 */
public class Identity extends Message
  implements D2DObject
{
    private static final long serialVersionUID = 101L;

    private MemberInfo member;

    // A random magic id, value for the connection
    private String magicId;

    /** Flag which indicates that encryption is supported. */
    private boolean supportsEncryption;

    /**
     * #2366: For server only. Since v3.5.13
     */
    private boolean supportsQuickLogin = true;

    /**
     * flag to indicate a tunneled connection.
     */
    private boolean tunneled;

    /**
     * If to wait for handshake ack from remote side. Always true. Field
     * included for backward compatibility.
     *
     * @see HandshakeCompleted
     */
    @SuppressWarnings("unused")
    private boolean acknowledgesHandshakeCompletion = true;

    /**
     * Supports Request/Response pattern with serialized arguments. To avoid
     * problems when class model differs between client and server.
     */
    private boolean supportsSerializedRequest = true;

    // uses program version. ATTENTION: NEVER MARK THESE FINAL; FINAL FIELDS ARE NOT SERIALIZED!
    private String programVersion = Controller.PROGRAM_VERSION;

    private Calendar timeGMT = Calendar.getInstance();

    // Supports requests for single parts and filepartsrecords.
    // Earlier this was based on a user setting, but that's wrong since we
    // shouldn't deny the
    // remote side to decide how it wants to download.
    // Leftover for semi-old clients
    private boolean supportingPartTransfers = true;

    private Boolean useCompressedStream;
    /**
     * #2072: {@link Externalizable} protocol history:
     * <p>
     * 100: Initial version. Added: {@link RequestPartExt}
     * <p>
     * 101: Added: {@link StopUploadExt}
     * <p>
     * 102: Added: {@link StartUploadExt}
     * <p>
     * 103: Added: {@link RequestDownloadExt}
     * <p>
     * 104: Added: {@link FileChunkExt}
     * <p>
     * 105: Added: {@link FileListExt} {@link FolderFilesChangedExt}
     * <p>
     * 106: Added: {@link FolderListExt}
     * <p>
     * 107: Added: {@link KnownNodesExt}
     * <p>
     * 108: Added: {@link RelayedMessageExt}
     * <p>
     * 109: PFC-2352: Changed all messages containing FileInfo
     * {@link StopUploadExt} {@link StartUploadExt} {@link RequestPartExt}
     * {@link RequestDownloadExt} {@link FileChunkExt} {@link FileListExt}
     * {@link FolderFilesChanged}
     * <p>
     * 110: PFC-2571: Changed all messages containing FileInfo
     * {@link StopUploadExt} {@link StartUploadExt} {@link RequestPartExt}
     * {@link RequestDownloadExt} {@link FileChunkExt} {@link FileListExt}
     * {@link FolderFilesChanged}
     * <p>
     * 111: PFC-2455: Include federated folders when returning Account to node.
     */
    public static final int PROTOCOL_VERSION_106 = 106;
    public static final int PROTOCOL_VERSION_107 = 107;
    public static final int PROTOCOL_VERSION_108 = 108;
    public static final int PROTOCOL_VERSION_109 = 109;
    public static final int PROTOCOL_VERSION_110 = 110;
    public static final int PROTOCOL_VERSION_111 = 111;
    public static final int PROTOCOL_VERSION_112 = 112;

    // Never make this static
    private int protocolVersion = PROTOCOL_VERSION_112;

    private boolean requestFullFolderlist;

    /**
     * If I got interesting pending messages for you. Better keep the
     * connection!
     * <p>
     * TRAC #1124
     */
    private boolean pendingMessages = false;

    private String configurationURL;

    public Identity() {
        // Serialisation constructor
    }

    public Identity(Controller controller, MemberInfo myself, String magicId,
        boolean supportsEncryption, boolean tunneled, ConnectionHandler handler)
    {
        Reject.ifNull(controller, "Controller is null");
        Reject.ifNull(myself, "Member is null");
        this.member = myself;
        this.magicId = magicId;
        this.supportsEncryption = supportsEncryption;
        this.tunneled = tunneled;

        // #1124: HACK ALERT. This should only be true, if we have messages for
        // the remote side! Currently true if we have ANY pending messages to be
        // sent. Problem: The remote side cannot be known at the time the
        // identity is created, so we have to use this workaround.
        this.pendingMessages = controller.getTaskManager().hasSendMessageTask();

        boolean useZIPonLAN = ConfigurationEntry.USE_ZIP_ON_LAN
            .getValueBoolean(controller);
        this.useCompressedStream = !handler.isOnLAN()
            || (handler.isOnLAN() && useZIPonLAN);
        // #2569
        this.requestFullFolderlist = controller.getMySelf().isServer();

        this.configurationURL = controller.getConfig().getProperty("config.url");
    }

    /**
     * @return true if this identity is a valid one
     */
    public boolean isValid() {
        return member != null && member.id != null && member.nick != null;
    }

    /**
     * Convenience method.
     * 
     * @return the nick/name of the remote node.
     */
    public String getNick() {
        return member != null ? member.nick : null;
    }

    /**
     * @return the magic id.
     */
    public String getMagicId() {
        return magicId;
    }

    /**
     * @return the remote member info.
     */
    public MemberInfo getMemberInfo() {
        return member;
    }

    /**
     * @return the program version of the remote side.
     */
    public String getProgramVersion() {
        return programVersion;
    }

    /**
     * @return true if encrypted transfer are supported
     */
    public boolean isSupportsEncryption() {
        return supportsEncryption;
    }

    public boolean isSupportingPartTransfers() {
        return supportingPartTransfers;
    }

    public boolean isSupportsQuickLogin() {
        return supportsQuickLogin;
    }

    /**
     * @return true, if the stream is compressed; false, if the stream is not
     *         compressed; null, if we don't know
     */
    public Boolean isUseCompressedStream() {
        return useCompressedStream;
    }

    /**
     * #2569: Connection improvement: Don't send full folderlist from server to
     * client.
     *
     * @return
     */
    public boolean isRequestFullFolderlist() {
        return requestFullFolderlist;
    }

    /**
     * @return true if this is a tunneled connection.
     */
    public boolean isTunneled() {
        return tunneled;
    }

    public boolean isSupportsSerializedRequest() {
        return supportsSerializedRequest;
    }

    /**
     * @return if this node has interesting messages for you! Keep the
     *         connection.
     */
    public boolean isPendingMessages() {
        return pendingMessages;
    }

    /**
     * @return the current time of an client when it sent this message.
     */
    public Calendar getTimeGMT() {
        return timeGMT;
    }

    /**
     * @return the protocol version for {@link Externalizable}
     */
    public int getProtocolVersion() {
        return protocolVersion;
    }

    public String getConfigURL() {
        return configurationURL;
    }

    @Override
    public String toString() {
        return "Identity: " + member;
    }

    /**
     * Init from D2D message
     *
     * @param message Message to use data from
     * @author Christoph Kappel <kappel@powerfolder.com>
     **/
    @Override
    public void initFromD2D(AbstractMessage message) {
        if (message instanceof IdentityProto.Identity) {
            IdentityProto.Identity proto = (IdentityProto.Identity) message;
            this.member = new MemberInfo(proto.getNodeInfo());
            this.protocolVersion = proto.getProtocolVersion();
            this.requestFullFolderlist = proto.getRequestFullFolderlist();
            this.configurationURL = proto.getConfigurationUrl();
        }
    }

    /**
     * Convert to D2D message
     *
     * @return Converted D2D message
     * @author Christoph Kappel <kappel@powerfolder.com>
     **/
    @Override
    public AbstractMessage toD2D() {
        IdentityProto.Identity.Builder builder = IdentityProto.Identity.newBuilder();
        builder.setClazzName(this.getClass().getSimpleName());
        if (this.member != null) builder.setNodeInfo((NodeInfoProto.NodeInfo) this.member.toD2D());
        builder.setProtocolVersion(this.protocolVersion);
        builder.setRequestFullFolderlist(this.requestFullFolderlist);
        if (this.configurationURL != null) builder.setConfigurationUrl(this.configurationURL);
        return builder.build();
    }

}
