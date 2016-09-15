package com.github.agadar.nstelegram;

import com.github.agadar.nsapi.NSAPI;
import com.github.agadar.nsapi.NationStatesAPIException;
import com.github.agadar.nsapi.event.TelegramSentEvent;
import com.github.agadar.nsapi.event.TelegramSentListener;
import com.github.agadar.nsapi.query.TelegramQuery;
import com.github.agadar.nstelegram.filter.abstractfilter.Filter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the addressees list and sending telegrams to the former.
 * 
 * @author Agadar <https://github.com/Agadar/>
 */
public final class TelegramManager implements TelegramSentListener
{
    // User agent string for formatting.
    private final static String USER_AGENT = "Agadar's Telegrammer using Client "
            + "Key '%s' (https://github.com/Agadar/NationStates-Telegrammer)";
    
    private final List<Filter> Filters = new ArrayList<>(); // The filters to apply in chronological order.
    private final Set<String> Addressees = new HashSet<>(); // Presumably most up-to-date addressees list, based on Steps.
    private final Map<String, Set<String>> History = new ConcurrentHashMap<>();   // History of recipients, mapped to telegram id's.
    private Thread telegramThread; // The thread on which the TelegramQuery is running.
    
    // Variables that will be used for sending the telegrams. Should be manually
    // updated by a form or where-ever these values are defined.
    public String ClientKey;
    public String TelegramId;
    public String SecretKey;
    public boolean SendAsRecruitment;
    public boolean IsLooping;
    
    public TelegramManager() 
    {
        // Set user agent for the first time.
        NSAPI.setUserAgent("Agadar's Telegrammer (https://github.com/Agadar/NationStates-Telegrammer)");
    }
    
    /**
     * Refreshes the filters.
     * 
     * @param localCacheOnly if true, explicitly uses the local caches for returning
     * the filters' nations lists instead of allowing the possibility for using 
     * the global cache, daily dump file, or calls to the server.
     */
    public void refreshFilters(boolean localCacheOnly)
    {
        Addressees.clear();
        Filters.forEach((filter) -> { filter.applyFilter(Addressees, localCacheOnly); });
        removeOldRecipients();
    }
    
    /**
     * Adds new filter.
     * 
     * @param filter
     */
    public void addFilter(Filter filter)
    {
        Filters.add(filter);
        filter.applyFilter(Addressees, false);
        refreshFilters(true);
    }
    
    /**
     * Gives the number of addressees.
     * 
     * @return 
     */
    public int numberOfAddressees()
    {
        return Addressees.size();
    }
    
    /**
     * Removes the filter with the given index.
     * 
     * @param index 
     */
    public void removeFilterAt(int index)
    {
        Filters.remove(index);
        refreshFilters(true);
    }
    
    /**
     * Starts sending the telegram to the addressees in a new thread.
     * Throws IllegalArgumentException if the variables are not properly set.
     * 
     * @param listeners
     */
    public void startSending(TelegramSentListener... listeners)
    {
        // Make sure all inputs are valid.
        if (ClientKey == null || ClientKey.isEmpty())
            throw new IllegalArgumentException("Please supply a Client Key!");      
        if (TelegramId == null || TelegramId.isEmpty())
            throw new IllegalArgumentException("Please supply a Telegram Id!");      
        if (SecretKey == null || SecretKey.isEmpty())
            throw new IllegalArgumentException("Please supply a Secret Key!"); 
        if (numberOfAddressees() == 0)
            throw new IllegalArgumentException("Please supply at least one recipient!"); 

        NSAPI.setUserAgent(String.format(USER_AGENT, ClientKey)); // Update user agent.
        
        // Prepare thread, then run it.
        telegramThread = new Thread(() ->
        {
            try
            {
                do
                {
                    // Prepare query.
                    final TelegramQuery q = NSAPI.telegram(ClientKey, TelegramId, SecretKey, 
                        Addressees.toArray(new String[Addressees.size()]))
                            .addListeners(listeners).addListeners(this);

                    if (SendAsRecruitment)
                        q.isRecruitment();

                    q.execute(null);    // send the telegrams
                    refreshFilters(false);  // Update addressees until there's addressees available.
                        System.out.println(IsLooping);
                    while (IsLooping && Addressees.isEmpty())
                    {
                        System.out.println("No addressees found, sleeping for 60 seconds...");
                        Thread.sleep(1000 * 60);    // sleep 60 seconds
                        refreshFilters(false);
                    }
                } 
                while (IsLooping);
            }
            catch (Exception ex)
            {
                // We've broken from the loop which is what we want, so we're
                // cool with not handling this exception.
                System.out.println(ex);
            }
            finally
            {
                refreshFilters(false); // refresh filters one last time for printing info
            }
        });
        
        telegramThread.start();
    }
    
    /**
     * Stops sending the telegram to the addressees.
     */
    public void stopSending()
    {
        if (telegramThread != null)
        {
            telegramThread.interrupt();
            refreshFilters(false);
        }
    }
    
    /**
     * Removes old recipients from Addressees. Called right before executing
     * the Telegram Query.
     */
    private void removeOldRecipients()
    {
        Set<String> oldRecipients = History.get(TelegramId);
        
        if (oldRecipients == null)
        {
            oldRecipients = new HashSet<>();
            History.put(TelegramId, oldRecipients);
        }
        
        for (String oldRecipient : oldRecipients)
            Addressees.remove(oldRecipient);
    }
    
    @Override
    public void handleTelegramSent(TelegramSentEvent event)
    {
        // Update the History. We're assuming removeOldRecipients is always
        // called before this and the Telegram Id didn't change in the meantime,
        // so there is no need to make sure the entry for the current Telegram Id
        // changed.
        
        if (event.Queued)
            History.get(TelegramId).add(event.Addressee);
        
        // If this was the last telegram, then refresh the filters.
        //if (event.PositionInQuery + 1 >= numberOfAddressees())
        //    refreshFilters(false);
        
        // TODO: publish event that is handled by GUI.
    }
}
