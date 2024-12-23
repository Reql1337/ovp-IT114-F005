package Project.Client.Interfaces;

/**
 * Interface for handling message events.
 */
public interface IMessageEvents extends IClientEvents { 
    /**
     * Triggered when a message is received.
     *
     * @param id      The client ID.
     * @param message The message.
     */
    void onMessageSend(String message);        // OVP 12/22/24
    void onMessageReceive(long clientId, String message);  
}   
