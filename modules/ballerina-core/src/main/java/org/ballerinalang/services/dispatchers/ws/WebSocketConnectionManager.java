/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.ballerinalang.services.dispatchers.ws;

import org.ballerinalang.model.values.BConnector;
import org.ballerinalang.natives.connectors.BallerinaConnectorManager;
import org.ballerinalang.util.codegen.ServiceInfo;
import org.ballerinalang.util.exceptions.BallerinaException;
import org.wso2.carbon.messaging.ClientConnector;
import org.wso2.carbon.messaging.ControlCarbonMessage;
import org.wso2.carbon.messaging.exceptions.ClientConnectorException;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.websocket.Session;

/**
 * This contains all the sessions which are received via a {@link org.wso2.carbon.messaging.CarbonMessage}.
 */
public class WebSocketConnectionManager {

    // Map<serviceInfo, Map<sessionId, session>>
    private final Map<ServiceInfo, Map<String, Session>> broadcastSessions = new ConcurrentHashMap<>();
    // Map<groupName, Map<sessionId, session>>
    private final Map<String, Map<String, Session>> connectionGroups = new ConcurrentHashMap<>();
    // Map<NameToStoreConnection, Session>
    private final Map<String, Session> connectionStore = new ConcurrentHashMap<>();
    // Map <clientSession, clientSessionInfo>
    private final Map<Session, ClientSessionInfo> clientSessions = new ConcurrentHashMap<>();

    // Map <parentServiceName, Connector>
    private final Map<ServiceInfo, List<BConnector>> clientConnectors = new ConcurrentHashMap<>();
    // Map <connector, connectorInfo>
    private final Map<BConnector, ClientConnectorInfo> clientConnectorInfos = new ConcurrentHashMap<>();

    private static final WebSocketConnectionManager sessionManager = new WebSocketConnectionManager();

    private WebSocketConnectionManager() {
    }

    public static WebSocketConnectionManager getInstance() {
        return sessionManager;
    }

    public void addConnection(ServiceInfo service, Session session) {
        if (clientConnectors.containsKey(service)) {
            for (BConnector bConnector : clientConnectors.get(service)) {
                Session clientSession = initializeClientConnection(bConnector);
                String clientServiceName = bConnector.getStringField(1);
                addClientSession(clientSession, session, clientServiceName, false);
                clientConnectorInfos.get(bConnector).addClientSession(session, clientSession);
            }
        }
        addConnectionToBroadcast(service, session);
    }

    /**
     * Add {@link Session} to session the broadcast group of a given service.
     *
     * @param service {@link ServiceInfo} of the service.
     * @param session {@link Session} to add to the broadcast group.
     */
    public void addConnectionToBroadcast(ServiceInfo service, Session session) {
        if (broadcastSessions.containsKey(service)) {
            broadcastSessions.get(service).put(session.getId(), session);
        } else {
            Map<String, Session> sessionMap = new ConcurrentHashMap<>();
            sessionMap.put(session.getId(), session);
            broadcastSessions.put(service, sessionMap);
        }
    }

    /**
     * Remove {@link Session} from a broadcast group. This should be mostly called when a WebSocket connection is
     * Closed.
     *
     * @param service {@link ServiceInfo} of the service.
     * @param session {@link Session} to remove from the broadcast group.
     */
    public void removeConnectionFromBroadcast(ServiceInfo service, Session session) {
        if (broadcastSessions.containsKey(service)) {
            broadcastSessions.get(service).remove(session.getId());
        }
    }

    /**
     * Get a list of Sessions for broadcasting for a given service.
     *
     * @param service name of the service.
     * @return the list of sessions which are connected to a given service.
     */
    public List<Session> getBroadcastConnectionList(ServiceInfo service) {
        if (broadcastSessions.containsKey(service)) {
            return broadcastSessions.get(service).entrySet().stream()
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());
        } else {
            return null;
        }
    }

    /**
     * Add {@link Session} to session the session group.
     *
     * @param groupName name of the group.
     * @param session {@link Session} to remove from the broadcast group.
     */
    public void addConnectionToGroup(String groupName, Session session) {
        if (connectionGroups.containsKey(groupName)) {
            connectionGroups.get(groupName).put(session.getId(), session);
        } else {
            Map<String, Session> sessionMap = new ConcurrentHashMap<>();
            sessionMap.put(session.getId(), session);
            connectionGroups.put(groupName, sessionMap);
        }
    }

    /**
     * Remove {@link Session} from a session group. This should be mostly called when a WebSocket connection is
     * Closed.
     *
     * @param groupName name of the broadcast.
     * @param session {@link Session} to remove from the group.
     * @return true if the connection name was found and connection is removed.
     */
    public boolean removeConnectionFromGroup(String groupName, Session session) {
        if (connectionGroups.containsKey(groupName)) {
            connectionGroups.get(groupName).remove(session.getId());
            return true;
        }
        return false;
    }

    /**
     * Remove the whole group from the groups map.
     *
     * @param groupName name of the group.
     * @return true if connection group name exists and connection group is removed successfully.
     */
    public boolean removeConnectionGroup(String groupName) {
        if (connectionGroups.containsKey(groupName)) {
            connectionGroups.remove(groupName);
            return true;
        }
        return false;
    }

    /**
     * Get the list of the connections which belongs to a specific group.
     *
     * @param groupName name of the connection group.
     * @return a list of connections belongs to the mentioned group name.
     */
    public List<Session> getConnectionGroup(String groupName) {
        if (connectionGroups.containsKey(groupName)) {
            return connectionGroups.get(groupName).entrySet().stream()
                    .map(Map.Entry::getValue)
                    .collect(Collectors.toList());
        } else {
            return null;
        }
    }

    /**
     * Store connection with the name given by the user for future usages.
     *
     * @param connectionName name of the connection given by the user.
     * @param session {@link Session} to store.
     */
    public void storeConnection(String connectionName, Session session) {
        connectionStore.put(connectionName, session);
    }

    /**
     * Remove connection from the connection store.
     *
     * @param connectionName connection name which should be removed from the store.
     * @return true if connection name was found in connection store and removed successfully.
     */
    public boolean removeConnectionFromStore(String connectionName) {
        if (connectionStore.containsKey(connectionName)) {
            connectionStore.remove(connectionName);
            return true;
        }
        return false;
    }

    /**
     * Get the stored connection from the connection store.
     *
     * @param connectionName name of the connection which should be removed.
     * @return Session of the stored connection if connections is found else return null.
     */
    public Session getStoredConnection(String connectionName) {
        return connectionStore.get(connectionName);
    }

    public void addClientSession(Session clientSession, Session parentSession, String clientServiceName,
                                 boolean isInitialConnection) {
        clientSessions.put(clientSession, new ClientSessionInfo(clientSession, parentSession,
                                                                clientServiceName, isInitialConnection));
    }

    public Session getParentSessionOfClientSession(Session clientSession) {
        return clientSessions.get(clientSession).getParentSession();
    }

    public String getClientServiceNameOfClientSession(Session clientSession) {
        return clientSessions.get(clientSession).getClientServiceName();
    }

    public void addClientConnector(ServiceInfo parentService, BConnector connector) {
        if (clientConnectors.containsKey(parentService)) {
            clientConnectors.get(parentService).add(connector);
        } else {
            List<BConnector> connectors = new LinkedList<>();
            connectors.add(connector);
            clientConnectors.put(parentService, connectors);
        }
        clientConnectorInfos.put(connector, new ClientConnectorInfo(connector));
    }

    public List<Session> getSessionsOfClientConnector(BConnector bConnector) {
        return clientConnectorInfos.get(bConnector).getClientSessions();
    }

    public Session getClientSessionOfParentSession(BConnector bConnector, Session parentSession) {
        return clientConnectorInfos.get(bConnector).getClientSessionOfParentSession(parentSession);
    }

    public List<BConnector> getClientConnectors(ServiceInfo parentService) {
        return clientConnectors.get(parentService);
    }

    /**
     * Connections can be saved in multiple places according to there need of use.
     * Once the connection is closed or because of the reason if it has to be removed from all the places
     * that it is referred to use this method.
     *
     * @param session {@link Session} which should be removed from all the places.
     */
    public void removeConnectionFromAll(Session session) {
        // Removing session from broadcast sessions map
        broadcastSessions.entrySet().forEach(
                entry -> entry.getValue().entrySet().removeIf(
                        innerEntry -> innerEntry.getKey().equals(session.getId())
                )
        );
        //Removing session from groups map
        connectionGroups.entrySet().forEach(
                entry -> entry.getValue().entrySet().removeIf(
                        innerEntry -> innerEntry.getKey().equals(session.getId())
                )
        );
        // Removing session from connection store
        connectionStore.entrySet().removeIf(
                entry -> entry.getValue().equals(session)
        );
    }

    private Session initializeClientConnection(BConnector bConnector) {
        String remoteUrl = bConnector.getStringField(0);

        // Initializing a client connection.
        ClientConnector clientConnector =
                BallerinaConnectorManager.getInstance().getClientConnector(Constants.PROTOCOL_WEBSOCKET);
        ControlCarbonMessage controlCarbonMessage = new ControlCarbonMessage(
                org.wso2.carbon.messaging.Constants.CONTROL_SIGNAL_OPEN, null, true);
        controlCarbonMessage.setProperty(Constants.TO, remoteUrl);
        Session clientSession;
        try {
            clientSession = (Session) clientConnector.init(controlCarbonMessage, null, null);
        } catch (ClientConnectorException e) {
            throw new BallerinaException("Error occurred during initializing the connection to " + remoteUrl);
        }

        return clientSession;
    }

    private class ClientSessionInfo {
        private final Session clientSession;
        private final Session parentSession;
        private final String clientServiceName;
        private final boolean initialConnection;

        public ClientSessionInfo(Session clientSession, Session parentSession,
                                 String clientServiceName, boolean initialConnection) {
            this.clientSession = clientSession;
            this.parentSession = parentSession;
            this.clientServiceName = clientServiceName;
            this.initialConnection = initialConnection;
        }

        public Session getParentSession() {
            return parentSession;
        }

        public String getClientServiceName() {
            return clientServiceName;
        }

        public boolean isInitialConnection() {
            return isInitialConnection();
        }

        public Session getClientSession() {
            return clientSession;
        }
    }

    private class ClientConnectorInfo {
        private final BConnector bConnector;
        private final Map<Session, Session> clientSessions = new HashMap<>();

        public ClientConnectorInfo(BConnector bConnector) {
            this.bConnector = bConnector;
        }

        public BConnector getbConnector() {
            return bConnector;
        }

        public List<Session> getClientSessions() {
            return clientSessions.entrySet().stream().
                    map(Map.Entry::getValue).
                    collect(Collectors.toList());
        }

        public Session getClientSessionOfParentSession(Session session) {
            return clientSessions.get(session);
        }

        public void addClientSession(Session parentSession, Session clientSession) {
            clientSessions.put(parentSession, clientSession);
        }
    }
}
