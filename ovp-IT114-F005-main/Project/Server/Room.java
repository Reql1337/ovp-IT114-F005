package Project.Server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import Project.Common.LoggerUtil;

public class Room implements AutoCloseable{
    private String name;// unique name of the Room
    protected volatile boolean isRunning = false;
    private ConcurrentHashMap<Long, ServerThread> clientsInRoom = new ConcurrentHashMap<Long, ServerThread>();
    private List<ServerThread> clients = new ArrayList<>();

    public final static String LOBBY = "lobby";

    private void info(String message) {
        LoggerUtil.INSTANCE.info(String.format("Room[%s]: %s", name, message));
    }

    public Room(String name) {
        this.name = name;
        isRunning = true;
        info("created");
    }

    public String getName() {
        return this.name;
    }

    protected synchronized void addClient(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        if (clientsInRoom.containsKey(client.getClientId())) {
            info("Attempting to add a client that already exists in the room");
            return;
        }
        clientsInRoom.put(client.getClientId(), client);
        client.setCurrentRoom(this);

        // notify clients of someone joining
        sendRoomStatus(client.getClientId(), client.getClientName(), true);
        // sync room state to joiner
        syncRoomList(client);

        info(String.format("%s[%s] joined the Room[%s]", client.getClientName(), client.getClientId(), getName()));

    }

    protected synchronized void removedClient(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        // notify remaining clients of someone leaving
        // happen before removal so leaving client gets the data
        sendRoomStatus(client.getClientId(), client.getClientName(), false);
        clientsInRoom.remove(client.getClientId());
        LoggerUtil.INSTANCE.fine("Clients remaining in Room: " + clientsInRoom.size());

        info(String.format("%s[%s] left the room", client.getClientName(), client.getClientId(), getName()));

        autoCleanup();

    }

    /**
     * Takes a ServerThread and removes them from the Server
     * Adding the synchronized keyword ensures that only one thread can execute
     * these methods at a time,
     * preventing concurrent modification issues and ensuring thread safety
     * 
     * @param client
     */
    protected synchronized void disconnect(ServerThread client) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }
        long id = client.getClientId();
        sendDisconnect(client);
        client.disconnect();
        // removedClient(client); // <-- use this just for normal room leaving
        clientsInRoom.remove(client.getClientId());
        LoggerUtil.INSTANCE.fine("Clients remaining in Room: " + clientsInRoom.size());
        
        // Improved logging with user data
        info(String.format("%s[%s] disconnected", client.getClientName(), id));
        autoCleanup();
    }

    protected synchronized void disconnectAll() {
        info("Disconnect All triggered");
        if (!isRunning) {
            return;
        }
        clientsInRoom.values().removeIf(client -> {
            disconnect(client);
            return true;
        });
        info("Disconnect All finished");
        autoCleanup();
    }

    /**
     * Attempts to close the room to free up resources if it's empty
     */
    private void autoCleanup() {
        if (!Room.LOBBY.equalsIgnoreCase(name) && clientsInRoom.isEmpty()) {
            close();
        }
    }

    public void close() {
        // attempt to gracefully close and migrate clients
        if (!clientsInRoom.isEmpty()) {
            sendMessage(null, "Room is shutting down, migrating to lobby");
            info(String.format("migrating %s clients", name, clientsInRoom.size()));
            clientsInRoom.values().removeIf(client -> {
                Server.INSTANCE.joinRoom(Room.LOBBY, client);
                return true;
            });
        }
        Server.INSTANCE.removeRoom(this);
        isRunning = false;
        clientsInRoom.clear();
        info(String.format("closed", name));
    }

    // send/sync data to client(s)

    /**
     * Sends to all clients details of a disconnect client
     * @param client
     */
    protected synchronized void sendDisconnect(ServerThread client) {
        info(String.format("sending disconnect status to %s recipients", clientsInRoom.size()));
        clientsInRoom.values().removeIf(clientInRoom -> {
            boolean failedToSend = !clientInRoom.sendDisconnect(client.getClientId(), client.getClientName());
            if (failedToSend) {
                info(String.format("Removing disconnected client[%s] from list", client.getClientId()));
                disconnect(client);
            }
            return failedToSend;
        });
    }

    /**
     * Syncs info of existing users in room with the client
     * 
     * @param client
     */
    protected synchronized void syncRoomList(ServerThread client) {

        clientsInRoom.values().forEach(clientInRoom -> {
            if (clientInRoom.getClientId() != client.getClientId()) {
                client.sendClientSync(clientInRoom.getClientId(), clientInRoom.getClientName());
            }
        });
    }

    /**
     * Syncs room status of one client to all connected clients
     * 
     * @param clientId
     * @param clientName
     * @param isConnect
     */
    protected synchronized void sendRoomStatus(long clientId, String clientName, boolean isConnect) {
        info(String.format("sending room status to %s recipients", clientsInRoom.size()));
        clientsInRoom.values().removeIf(client -> {
            boolean failedToSend = !client.sendRoomAction(clientId, clientName, getName(), isConnect);
            if (failedToSend) {
                info(String.format("Removing disconnected client[%s] from list", client.getClientId()));
                disconnect(client);
            }
            return failedToSend;
        });
    }

    /**
     * Sends a basic String message from the sender to all connectedClients
     * Internally calls processCommand and evaluates as necessary.
     * Note: Clients that fail to receive a message get removed from
     * connectedClients.
     * Adding the synchronized keyword ensures that only one thread can execute
     * these methods at a time,
     * preventing concurrent modification issues and ensuring thread safety
     * 
     * @param message
     * @param sender  ServerThread (client) sending the message or null if it's a
     *                server-generated message
     */
    protected synchronized void sendMessage(ServerThread sender, String message) {
        if (!isRunning) { // block action if Room isn't running
            return;
        }

	

        // Used: https://www.w3schools.com/java/java_regex.asp + https://regex101.com/r/2zOMid/1 (I went to Toegel's office hours for help with this)
        String formatting = message.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>")    
                            .replaceAll("\\*(.*?)\\*", "<i>$1</i>")        
                            .replaceAll("_(.*?)_", "<u>$1</u>")              
                            .replaceAll("#r(.*?)r#", "<font color='#FF0000'>$1</font>")        
                            .replaceAll("#b(.*?)b#", "<font color='#0000FF'>$1</font>")           // ovp 11/14 (and some other time on the last day of classes I forgot the date)
                            .replaceAll("#g(.*?)g#", "<font color='#008000'>$1</font>"); 


        // Note: any desired changes to the message must be done before this section
        long senderId = sender == null ? ServerThread.DEFAULT_CLIENT_ID : sender.getClientId();

        // loop over clients and send out the message; remove client if message failed
        // to be sent
        // Note: this uses a lambda expression for each item in the values() collection,
        // it's one way we can safely remove items during iteration
        info(String.format("sending message to %s recipients: %s", clientsInRoom.size(), formatting));
        clientsInRoom.values().removeIf(client -> {
            boolean failedToSend = !client.sendMessage(senderId, formatting);
            if (failedToSend) {
                info(String.format("Removing disconnected client[%s] from list", client.getClientId()));
                disconnect(client);
            }
            return failedToSend;
        });
    
    // end send data to client(s)

    // Implemented skipping users messages if they are muted
    for (ServerThread client : clients)
     {
        if (client.isMuted(sender.getClientName()))                             // ovp 12/22/24
        {

            LoggerUtil.INSTANCE.info("SKIPPING MESSAGE OF: " + client.getClientName() + " (MUTED " + sender.getClientName() + ").");
            continue;
        }
        client.sendMessage(sender.getClientName() + ": " + message);
    }
}


    // receive data from ServerThread
    
    protected void handleCreateRoom(ServerThread sender, String room) {
        if (Server.INSTANCE.createRoom(room)) {
            Server.INSTANCE.joinRoom(room, sender);
        } else {
            sender.sendMessage(String.format("Room %s already exists", room));
        }
    }

    protected void handleJoinRoom(ServerThread sender, String room) {
        if (!Server.INSTANCE.joinRoom(room, sender)) {
            sender.sendMessage(String.format("Room %s doesn't exist", room));
        }
    }

    protected void handleListRooms(ServerThread sender, String roomQuery){
        sender.sendRooms(Server.INSTANCE.listRooms(roomQuery));
    }

    protected void clientDisconnect(ServerThread sender) {
        disconnect(sender);
    }


    public void flipCommand(ServerThread sender) 
    {

        int result = (int) (Math.random() * 2);
        String message = result == 0 ? "FLIP: Heads" : "FLIP: Tails";
        System.out.println(sender + " flipped a coin and got " + message);
        sendMessage(null, message);
    }

    public void rollCommand(ServerThread sender, int numDice, int numSides)
    {
        StringBuilder rollResults = new StringBuilder();
        int total = 0;
    
        if (numDice == 0 && numSides > 0) 
        {
            int num = (int) (Math.random() * numSides) + 1;
            rollResults.append(String.format("Rolled a %d and got %d", numSides, num));
            sendMessage(null, String.format("%s rolled a %d and got %d", sender.getClientName(), numSides, num));
        }
        else if (numDice > 0)
        {                                               
            for (int i = 0; i < numDice; i++) 
            {
                int roll = (int) (Math.random() * numSides) + 1;
                rollResults.append(roll);
                total += roll;
    
                if (i < numDice - 1) {
                    rollResults.append(", ");
                }
            }
    
            sendMessage(null, String.format("%s rolled %dd%d and got: %s (Total: %d)", 
            sender.getClientName(), numDice, numSides, rollResults.toString(), total));
        }
    }
       
    
    ServerThread getClientByName(String name) {
        for (ServerThread client : clients) {
            if (client.getClientName().equalsIgnoreCase(name)) {
                return client;
            }
        }
        return null;
    }

    public ServerThread getClientById(String clientId) {
        for (ServerThread client : clients) {
            if (String.valueOf(client.getClientId()).equals(clientId)) {
                return client;
            }
        }
        return null;
    }
    
    
    public void handlePrivateMessage(ServerThread sender, String targetId, String message) 
    {
        ServerThread receiver = getClientById(targetId);
        if (receiver == null) 
        {
            sender.sendMessage("Error: User with ID " + targetId + " does not exist.");
            return;
        }                                                   // ovp 12/22

        if (receiver.isMuted(sender.getClientName())) 
        {
            sender.sendMessage("Message to " + receiver.getClientName() + " was not sent because user has you muted.");
            return;
        }
    
    }
    
    public void handleMute(ServerThread sender, String targetName) 
    {
        ServerThread target = getClientByName(targetName.trim().toLowerCase());
        if (target == null) 
        {
            sender.sendMessage("ERROR: User " + targetName + " does not exist.");
            return;
        }
        sender.mute(targetName);
        sender.sendMessage("You have muted " + targetName + ".");
        sendMessage(target, "You have been muted by " + sender);
    }
    
    public void handleUnmute(ServerThread sender, String targetName) 
    {
        ServerThread target = getClientByName(targetName.trim().toLowerCase());             // ovp 12/22
        if (target == null) 
        {
            sender.sendMessage("ERROR: User " + targetName + " does not exist.");
            return;
        }
        sender.unmute(targetName);
        sender.sendMessage("You have unmuted " + targetName + ".");
        sendMessage(target, "You have been unmuted by " + sender);
    }
    
    // end receive data from ServerThread
    public String formatMessage(String var1) 
    {
        if (var1 != null && !var1.isEmpty()) 
        {
            var1 = this.applyFormatting(var1, "*", "<b>", "</b>");
            var1 = this.applyFormatting(var1, "_", "<i>", "</i>");
            var1 = this.applyFormatting(var1, "~", "<u>", "</u>");                  // OVP 12/22
            
            if (var1.contains("#")) 
            {
                var1 = this.applyColorFormatting(var1);
            }
    
            return var1;
        } 
        else 
        {
            return var1;
        }
    }
    
    // Apply different text formatting
    private String applyFormatting(String var1, String var2, String var3, String var4) 
    {
        if (!var1.contains(var2)) 
        {
            return var1;
        } 
        else 
        {
            StringBuilder var5 = new StringBuilder();
            boolean var6 = false;
            char[] var7 = var1.toCharArray();
            int var8 = var7.length;
    
            for (int var9 = 0; var9 < var8; ++var9) 
            {
                char var10 = var7[var9];
                if (String.valueOf(var10).equals(var2))                     // OVP 12/22
                {
                    var5.append(var6 ? var4 : var3);
                    var6 = !var6;
                } 
                else 
                {
                    var5.append(var10);
                }
            }
    
            if (var6) 
            {
                var5.append(var4);
            }
    
            return var5.toString();
        }
    }
    

    private String applyColorFormatting(String var1) 
    {
        StringBuilder var2 = new StringBuilder();
        String[] var3 = var1.split("#");
        String var4 = "black"; 
    
        for (int var5 = 0; var5 < var3.length; ++var5) 
        {
            if (var5 % 2 == 1)
             {

                String var6 = var3[var5].split(" ")[0].trim();                  // OVP 12/22/24
                var4 = var6; 
                
                var3[var5] = var3[var5].substring(var6.length()).trim(); 
            }
            
            var2.append("<font color=\"").append(var4).append("\">").append(var3[var5]).append("</font>");
            var4 = "black"; 
        }
    
        return var2.toString();
    }
    
        
}