package com.zimbra.cs.mailbox.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.zimbra.cs.mailbox.MailItem;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.mailbox.Metadata;
import com.zimbra.cs.mailbox.TransactionCacheTracker;
import com.zimbra.common.localconfig.LC;

public class LocalItemCache extends MapItemCache<MailItem> {

    private Metadata folderTagMeta = null;

    public LocalItemCache(Mailbox mbox, Map<Integer, MailItem> itemMap, Map<String, Integer> uuidMap) {
        super(mbox, itemMap, uuidMap);
    }

    @Override
    protected MailItem toCacheValue(MailItem item) {
        return item;
    }

    @Override
    protected MailItem fromCacheValue(MailItem value) {
        return value;
    }

    @Override
    protected Metadata getCachedTagsAndFolders() {
        return folderTagMeta;
    }

    @Override
    protected void cacheFoldersTagsMeta(Metadata folderTagMeta) {
        this.folderTagMeta = folderTagMeta;
    }

    public static class Factory implements ItemCache.Factory {

        @Override
        public ItemCache getItemCache(Mailbox mbox, TransactionCacheTracker cacheTracker) {
            int cacheCapacity = LC.zimbra_mailbox_active_cache.intValue();
            Map<Integer, MailItem> itemMap = new ConcurrentLinkedHashMap.Builder<Integer, MailItem>().maximumWeightedCapacity(cacheCapacity).build();
            Map<String, Integer> uuidMap = new ConcurrentHashMap<>(cacheCapacity);
            return new LocalItemCache(mbox, itemMap, uuidMap);
        }

        @Override
        public FolderCache getFolderCache(Mailbox mbox, TransactionCacheTracker cacheTracker) {
            return new LocalFolderCache();
        }

        @Override
        public TagCache getTagCache(Mailbox mbox, TransactionCacheTracker cacheTracker) {
            return new LocalTagCache();
        }

        @Override
        public TransactionCacheTracker getTransactionCacheTracker(Mailbox mbox) {
            //no need for transaction tracker for local case
            return null;
        }
    }

    @Override
    public void trim(int numItems) {}
}