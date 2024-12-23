package Project.Client.Views;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;

public class UserControl 
{
    private final Map<Long, String> connectedUsers = new HashMap<>();
    private final Set<String> mutedUsers = new HashSet<>();

    public Set<String> getUsernames() 
    {
        return new HashSet<>(connectedUsers.values());
    }

    public void muteUser(String username) 
    {
        mutedUsers.add(username);
    }

    public void unmuteUser(String username) 
    {
        mutedUsers.remove(username);
    }

    public boolean isMuted(String username) 
    {
        return mutedUsers.contains(username);
    }

    public void addUser(long clientId, String username) 
    {
        connectedUsers.put(clientId, username);
    }

    public void removeUser(long clientId) 
    {
        connectedUsers.remove(clientId);
    }
}


