/* $Id$
 */
package de.dal33t.powerfolder.net;

import java.net.Socket;

import de.dal33t.powerfolder.Controller;

/**
 * The default factory which creates <code>ConnectionHandler</code>s.
 * 
 * @see ConnectionHandler
 * @author <a href="mailto:sprajc@riege.com">Christian Sprajc</a>
 * @version $Revision: 1.5 $
 */
public class ConnectionHandlerFactory {
    /**
     * Creats a initalized connection handler for a socket based TCP/IP
     * connection.
     * 
     * @param controller
     *            the controller.
     * @param socket
     *            the tcp/ip socket
     * @return the connection handler for basic IO connection.
     * @throws ConnectionException
     */
    public ConnectionHandler createSocketConnectionHandler(
        Controller controller, Socket socket) throws ConnectionException
    {
        ConnectionHandler conHan = new ConnectionHandler(controller, socket);
        conHan.init();
        return conHan;
    }
}
