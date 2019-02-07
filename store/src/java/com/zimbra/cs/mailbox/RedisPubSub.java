package com.zimbra.cs.mailbox;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import com.google.common.collect.Iterators;
import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.mailbox.cache.CachedObjectRegistry;
import com.zimbra.cs.mailbox.cache.CachedObjectRegistry.CachedObjectKey;
import com.zimbra.cs.mailbox.cache.CachedObjectRegistry.CachedObjectKeyType;
import com.zimbra.cs.session.PendingLocalModifications;
import com.zimbra.cs.session.PendingLocalModifications.PendingModificationSnapshot;
import com.zimbra.cs.session.PendingModifications.Change;
import com.zimbra.cs.session.PendingModifications.ModificationKey;
import com.zimbra.cs.session.Session.SourceSessionInfo;
import com.zimbra.cs.session.Session.Type;

public class RedisPubSub extends NotificationPubSub {

    private String accountId;
    private RedissonClient client;
    private final int numChannels;

    private static Map<String, NotificationChannel> channelMap = new HashMap<>();

    public RedisPubSub(Mailbox mbox, RedissonClient client) {
        super(mbox);
        this.accountId = mbox.getAccountId();
        this.client = client;
        this.numChannels = LC.redis_num_pubsub_channels.intValue();
    }

    private String getChannelName() {
        int channelNum = mbox.getId() % numChannels;
        return String.format("NOTIFICATION-CHANNEL-%d", channelNum);
    }

    @Override
    protected Publisher initPubisher() {
        return new RedisPublisher();
    }

    @Override
    protected Subscriber initSubscriber() {
        return new RedisSubscriber();
    }

    private synchronized NotificationChannel getChannel() {
        String channelName = getChannelName();
        NotificationChannel channel = channelMap.get(channelName);
        if (channel == null) {
            channel = new NotificationChannel(client, channelName);
            channelMap.put(channelName, channel);
        }
        return channel;
    }

    public class RedisPublisher extends Publisher {

        @Override
        public void publish(PendingLocalModifications pns, int changeId, SourceSessionInfo source, int sourceMailboxHash) {
            super.publish(pns, changeId, source, sourceMailboxHash); //notify local first
            try {
                //don't publish mods with no changes remotely to minimize chatter
                if (!pns.hasNotifications()) {
                    return;
                }
                PendingModificationSnapshot snapshot = pns.toSnapshot();
                long received = getChannel().publish(new NotificationMsg(accountId, snapshot, changeId, source, sourceMailboxHash));
                ZimbraLog.mailbox.debug("published notifications for changeId=%d, received by %d", changeId, received);
            } catch (IOException | ServiceException e) {
                ZimbraLog.mailbox.error("unable to serialize notifications for changeId=%d, accountId=%s", changeId, mbox.getAccountId(), e);
            }
        }

        @Override
        public int getNumListeners(Type type) {
            //TODO: this only returns the number of local listeners!
            return RedisPubSub.this.getSubscriber().getListeners(type).size();
        }
    }

    public class RedisSubscriber extends Subscriber {

        public RedisSubscriber() {
            getChannel().addSubscriber(this);
        }

        @Override
        public void purgeListeners() {
            super.purgeListeners();
            getChannel().removeSubscriber(accountId);
        }
    }

    /**
     * Helper class acting as both the listener and publisher to a Redis notification pubsub channel.
     * This channel handles notifications for multiple accounts to avoid having a separate connection for each
     * mailbox; incoming notifications are routed to the appropriate Subscriber instance.
     */
    public static class NotificationChannel implements MessageListener<NotificationMsg> {

        private int listenerId;
        private RTopic channel;
        private Map<String, Subscriber> subscriberMap;
        private String name;
        private boolean active = false;

        public NotificationChannel(RedissonClient client, String channelName) {
            this.name = channelName;
            this.subscriberMap = new HashMap<>();
            this.channel = client.getTopic(channelName);
            beginListening();
        }

        public void beginListening() {
            if (active) {
                return;
            }
            this.listenerId = channel.addListener(NotificationMsg.class, this);
            ZimbraLog.mailbox.info("beginning listening on Redis notification channel %s", name);
            active = true;
        }

        public void addSubscriber(RedisSubscriber subscriber) {
            beginListening();
            String acctId = subscriber.getMailbox().getAccountId();
            subscriberMap.put(acctId, subscriber);
            ZimbraLog.mailbox.info("added account %s to Redis notification channel %s", acctId, name);
        }

        public void removeSubscriber(String accountId) {
            subscriberMap.remove(accountId);
            if (subscriberMap.isEmpty()) {
                ZimbraLog.mailbox.info("%s has no subscribers, removing listener", name);
                channel.removeListener(listenerId);
                active = false;
            }
        }

        public long publish(NotificationMsg msg) {
            return channel.publish(msg);
        }

        @Override
        public void onMessage(CharSequence channel, NotificationMsg msg) {
            String notificationAcctId = msg.accountId;
            Subscriber subscriber = subscriberMap.get(notificationAcctId);
            if (subscriber == null) {
                /* Not a listener for this account, not unusual, there should be another thread listening though. */
                return;
            }
            ZimbraLog.mailbox.debug("handling notification.  accountId=%s, changeId=%d, channel=%s",
                    notificationAcctId, msg.changeId, channel);
            PendingLocalModifications mods;
            try {
                mods = PendingLocalModifications.fromSnapshot(subscriber.getMailbox(), msg.modification);
                clearRedisCachedValues(subscriber.getMailbox(), mods, msg.changeId);
                subscriber.notifyListeners(mods, msg.changeId, msg.source, msg.sourceMailboxHash, true);
            } catch (ServiceException e) {
                ZimbraLog.mailbox.error(
                                "unable to deserialize notifications for accountId=%s, changeId=%s, channel=%s",
                        notificationAcctId, msg.changeId, channel, e);
            }
        }

        private void clearRedisCachedValues(Mailbox mbox, PendingLocalModifications mods, int changeId) {
            CachedObjectRegistry cachedObjects = mbox.getCachedObjects();
            Set<Integer> changedFolders = mods.getAllChangedFolders();
            int numCleared = 0;
            if (!changedFolders.isEmpty()) {
                for (Integer folderId: changedFolders) {
                    cachedObjects.invalidate(new CachedObjectKey(CachedObjectKeyType.MAILITEM, folderId));
                    numCleared++;
                }
            }
            Iterator<Map.Entry<ModificationKey, Change>> changeIterator;

            boolean hasMods = mods.modified != null;
            boolean hasDeletes = mods.deleted != null;

            if (!hasMods && !hasDeletes) {
                return;
            }
            if (hasMods && hasDeletes) {
                changeIterator = Iterators.concat(mods.modified.entrySet().iterator(), mods.deleted.entrySet().iterator());
            } else if (hasMods) {
                changeIterator = mods.modified.entrySet().iterator();
            } else {
                changeIterator = mods.deleted.entrySet().iterator();
            }
            while (changeIterator.hasNext()) {
                Map.Entry<ModificationKey, Change> mod = changeIterator.next();
                Integer itemId = mod.getKey().getItemId();
                if (itemId != 0) {
                    cachedObjects.invalidate(new CachedObjectKey(CachedObjectKeyType.MAILITEM, itemId));
                    numCleared++;
                }
            }
            if (ZimbraLog.cache.isTraceEnabled()) {
                ZimbraLog.cache.trace("invalidated %s cached redis objects for account %s (changeId=%s)", numCleared, mbox.getAccountId(), changeId);
            }
        }

        @Override
        public String toString() {
            return String.format("[Channel %s (%s mailboxes)]", name, subscriberMap.size());
        }
    }

    private static class NotificationMsg {
        private String accountId;
        private PendingModificationSnapshot modification;
        private int changeId;
        private SourceSessionInfo source;
        private int sourceMailboxHash;

        public NotificationMsg() {}

        public NotificationMsg(String accountId, PendingModificationSnapshot modification, int changeId,
                SourceSessionInfo source, int sourceMailboxHash) throws IOException, ServiceException {
            this.accountId = accountId;
            this.modification = modification;
            this.changeId = changeId;
            this.source = source;
            this.sourceMailboxHash = sourceMailboxHash;
        }
    }

    public static class Factory extends NotificationPubSub.Factory {

        @Override
        protected NotificationPubSub initPubSub(Mailbox mbox) {
            RedissonClient client = RedissonClientHolder.getInstance().getRedissonClient();
            return new RedisPubSub(mbox, client);
        }
    }
}