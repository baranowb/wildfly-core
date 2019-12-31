/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.domain.management.security;

import static org.jboss.as.domain.management.logging.DomainManagementLogger.SECURITY_LOGGER;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.naming.NamingException;

import org.jboss.as.domain.management.security.LdapSearcherCache.AttachmentKey;
import org.jboss.as.domain.management.security.LdapSearcherCache.SearchResult;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;

/**
 * The {@link Service} that handles caching results of LDAP searches.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
class LdapCacheService<R, K> implements Service<LdapSearcherCache<R, K>> {

    private static volatile int THREAD_COUNT = 1;

    private final LdapSearcher<R, K> searcher;
    private final Consumer<LdapSearcherCache<R, K>> ldapSearchCacheConsumer;
    private volatile CacheMode mode;
    private volatile int evictionTime;
    private volatile boolean cacheFailures;
    private volatile int maxCacheSize;

    /*
     * Controlled by the service lifecycle.
     */
    volatile ExtendedLdapSearcherCache<R, K> cacheImplementation;
    private ScheduledExecutorService executorService;

    private LdapCacheService(final Consumer<LdapSearcherCache<R, K>> ldapSearchCacheConsumer, final LdapSearcher<R, K> searcher, final CacheMode mode, final int evictionTime, final boolean cacheFailures, final int maxCacheSize) {
        this.ldapSearchCacheConsumer = ldapSearchCacheConsumer;
        this.searcher = searcher;
        this.mode = mode;
        this.evictionTime = evictionTime;
        this.cacheFailures = cacheFailures;
        this.maxCacheSize = maxCacheSize;
    }

    /*
     * Factory Methods
     */

    static <R, K> LdapCacheService<R, K> createNoCacheService(final Consumer<LdapSearcherCache<R, K>> ldapSearchCacheConsumer,
                                                              final LdapSearcher<R, K> searcher) {
        return new LdapCacheService<R, K>(ldapSearchCacheConsumer, searcher, CacheMode.OFF, 0, false, 0);
    }

    static <R, K> LdapCacheService<R, K> createBySearchCacheService(final Consumer<LdapSearcherCache<R, K>> ldapSearchCacheConsumer,
                                                                    final LdapSearcher<R, K> searcher, final int evictionTime, final boolean cacheFailure, final int maxSize) {
        return new LdapCacheService<R, K>(ldapSearchCacheConsumer, searcher, CacheMode.BY_SEARCH, evictionTime, cacheFailure, maxSize);
    }

    static <R, K> LdapCacheService<R, K> createByAccessCacheService(final Consumer<LdapSearcherCache<R, K>> ldapSearchCacheConsumer,
                                                                    final LdapSearcher<R, K> searcher, final int evictionTime, final boolean cacheFailure, final int maxSize) {
        return new LdapCacheService<R, K>(ldapSearchCacheConsumer, searcher, CacheMode.BY_ACCESS, evictionTime, cacheFailure, maxSize);
    }

    /*
     * MSC Service and Lifecycle Methods
     */
    @Override
    public LdapSearcherCache<R, K> getValue() throws IllegalStateException, IllegalArgumentException {
        return cacheImplementation;
    }

    @Override
    public void start(final StartContext context) {
        switch (mode) {
            case OFF:
                cacheImplementation = new NoCacheCache();
                break;
            case BY_ACCESS:
                cacheImplementation = new ByAccessCache(evictionTime, cacheFailures, maxCacheSize);
                break;
            case BY_SEARCH:
                cacheImplementation = new BySearchCache(evictionTime, cacheFailures, maxCacheSize);
                break;
            default:
                // Should not actually hit this.
                throw new IllegalStateException(String.format("Unknown cache mode '%s'", mode));
        }
        /*
         * This is only used to trigger evictions, if one eviction is stuck waiting for the lock on the table there is no point
         * having many threads concurrently waiting on the same lock.
         */
        if (evictionTime > 0) {
            executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, String.format("LDAP Cache Eviction Thread (%d)", THREAD_COUNT++));
                }
            });
        }
        ldapSearchCacheConsumer.accept(cacheImplementation);
    }

    @Override
    public void stop(final StopContext context) {
        try {
            context.execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        ldapSearchCacheConsumer.accept(null);
                        cacheImplementation.clearAll();
                        cacheImplementation = null;
                        if (executorService != null) {
                            // FIXME context.execute() should not be used for blocking tasks. Inject a scheduled executor
                            // and get rid of this
                            executorService.shutdown();
                            executorService = null;
                        }
                    } finally {
                        context.complete();
                    }
                }
            });
        } finally {
            context.asynchronous();
        }
    }

    /*
     * Accessor Methods
     */

    public CacheMode getMode() {
        return mode;
    }

    public void setMode(CacheMode mode) {
        this.mode = mode;
    }

    public int getEvictionTime() {
        return evictionTime;
    }

    public void setEvictionTime(int evictionTime) {
        this.evictionTime = evictionTime;
    }

    public boolean isCacheFailures() {
        return cacheFailures;
    }

    public void setCacheFailures(boolean cacheFailures) {
        this.cacheFailures = cacheFailures;
    }

    public int getMaxCacheSize() {
        return maxCacheSize;
    }

    public void setMaxCacheSize(int maxCacheSize) {
        this.maxCacheSize = maxCacheSize;
    }

    private enum CacheMode {
        OFF, BY_SEARCH, BY_ACCESS
    }

    private R internalSearch(LdapConnectionHandler connectionHandler, K key) throws IOException, NamingException {
        return searcher.search(connectionHandler, key);
    }

    private interface ExtendedLdapSearcherCache<R, K> extends LdapSearcherCache<R, K> {
        int getCurrentSize();

        void clearAll();

        void clear(K key);

        void clear(Predicate<K> predicate);

        boolean contains(K key);

        int count(Predicate<K> predicate);

        Set<K> currentKeys();
    }

    private class NoCacheCache implements ExtendedLdapSearcherCache<R, K> {

        @Override
        public SearchResult<R> search(LdapConnectionHandler connectionHandler, K key) throws IOException, NamingException {
            SECURITY_LOGGER.tracef("Non caching search for '%s'", key);
            R result = searcher.search(connectionHandler, key);

            return new SearchResultImpl<R>(result);
        }

        @Override
        public int getCurrentSize() {
            return 0;
        }

        @Override
        public void clearAll() {
        }

        @Override
        public void clear(K key) {
        }

        @Override
        public boolean contains(K key) {
            return false;
        }

        @Override
        public Set<K> currentKeys() {
            return Collections.emptySet();
        }

        @Override
        public void clear(Predicate<K> predicate) {
        }

        @Override
        public int count(Predicate<K> predicate) {
            return 0;
        }

    }

    private abstract class BaseSearchCache implements ExtendedLdapSearcherCache<R, K> {

        protected final int evictionTime;
        protected final boolean cacheFailures;
        protected final int maxSize;

        protected final LinkedHashMap<K, CacheEntry> theCache = new LinkedHashMap<K, CacheEntry>();
        //the list of all current executions, there shouldn't be any size limitations,because it cotains only entries, which are currently in memory (are being executed)
        private final LinkedHashMap<K, CacheEntry> executions = new LinkedHashMap<K, CacheEntry>();

        private BaseSearchCache(final int evictionTime, final boolean cacheFailures, final int maxSize) {
            this.evictionTime = evictionTime;
            this.cacheFailures = cacheFailures;
            this.maxSize = maxSize;
        }

        /**
         * Each type of cache has different way how to read from cache.
         * Method is called in synchronized block for theCache
         *
         * @param key Key of entry in cache.
         * @return Returns founded entry or null.
         * @throws IOException In case of search fails.
         * @throws NamingException In case of search fails.
         */
        protected abstract CacheEntry readFromCache(final K key) throws IOException, NamingException;

        @Override
        public final SearchResult<R> search(LdapConnectionHandler connectionHandler, final K key) throws IOException, NamingException {
            boolean currentlyExecuted = false;
            CacheEntry entry = null;
            synchronized (theCache) {
                entry = readFromCache(key);
                if (entry == null) {
                    entry = executions.get(key);

                    if(entry == null) {
                        entry = new CacheEntry();
                        executions.put(key, entry);
                        SECURITY_LOGGER.tracef("Entry for '%s' not found in cache.", key);
                    } else {
                        SECURITY_LOGGER.tracef("Cached entry for '%s' is being retrieved into cache.", key);
                    }

                    currentlyExecuted = true;

                } else {
                    SECURITY_LOGGER.tracef("Cached entry for '%s' found in cache.", key);
                }

            }

            SearchResult<R> res = null;
            try {
                // The individual entry will handle it's own synchronization now.
                res = entry.getSearchResult(connectionHandler, key);
            } finally {
                if (currentlyExecuted) {
                    synchronized (theCache) {
                        boolean removed = executions.remove(key, entry);

                        if(!removed) {
                            //probably removed/updated by other thread, which transferred entry into cache (or is about to do,
                            // (If this thread was sleeping so long, that another threads emptied executions and inserted different entry with the same key)
                            return res;
                        }

                        if (res != null || (entry.failure != null && cacheFailures)) {
                            if (maxSize > 0 && theCache.size() + 1 > maxSize) {
                                boolean trace = SECURITY_LOGGER.isTraceEnabled();
                                Iterator<Entry<K, CacheEntry>> it = theCache.entrySet().iterator();
                                while (theCache.size() + 1 > maxSize) {
                                    Entry<K, CacheEntry> current = it.next();
                                    current.getValue().cancelFuture();
                                    it.remove();
                                    if (trace) {
                                        SECURITY_LOGGER.tracef(
                                                "Entry with key '%s' evicted from cache due to cache being above maximum size.",
                                                current.getKey());
                                    }
                                }
                            }
                            theCache.put(key, entry);
                            prepareEviction(key, entry);
                        }
                    }
                }
            }

            return res;
        }

        final void prepareEviction(final K key, CacheEntry entry) {
            if (evictionTime > 0) {
                entry.cancelFuture();
                entry.setFuture(executorService.schedule(new Runnable() {

                    @Override
                    public void run() {
                        synchronized (theCache) {
                            CacheEntry entry = theCache.remove(key);
                            if (entry == null) {
                                SECURITY_LOGGER.tracef("Entry with key '%s' not in cache at time of timeout.", key);
                            } else {
                                SECURITY_LOGGER.tracef("Evicted entry with key '%s' due to eviction timeout.", key);
                            }
                        }

                    }
                }, evictionTime, TimeUnit.SECONDS));
            }
        }

        @Override
        public int getCurrentSize() {
            synchronized (theCache) {
                return theCache.size();
            }
        }

        @Override
        public void clearAll() {
            synchronized (theCache) {
                Iterator<CacheEntry> it = theCache.values().iterator();
                while (it.hasNext()) {
                    CacheEntry current = it.next();
                    current.cancelFuture();
                    it.remove();
                }
            }
            SECURITY_LOGGER.trace("Cleared whole cache.");
        }

        @Override
        public void clear(K key) {
            synchronized (theCache) {
                CacheEntry entry = theCache.remove(key);
                if (entry != null) {
                    entry.cancelFuture();
                }
            }
            SECURITY_LOGGER.tracef("Cleared entry from cache with key '%s'", key);
        }

        @Override
        public void clear(Predicate<K> predicate) {
            synchronized (theCache) {
                Iterator<Entry<K, CacheEntry>> it = theCache.entrySet().iterator();
                while (it.hasNext()) {
                    Entry<K, CacheEntry> current = it.next();
                    K key = current.getKey();
                    if (predicate.matches(key)) {
                        it.remove();
                        current.getValue().cancelFuture();
                        SECURITY_LOGGER.tracef("Cleared entry from cache with key '%s' based on predicate match.", key);
                    }
                }
            }
        }

        @Override
        public boolean contains(K key) {
            synchronized (theCache) {
                return theCache.containsKey(key);
            }
        }

        @Override
        public int count(Predicate<K> predicate) {
            int count = 0;
            synchronized (theCache) {
                Iterator<K> it = theCache.keySet().iterator();
                while (it.hasNext()) {
                    K current = it.next();
                    if (predicate.matches(current)) {
                        count++;
                    }
                }
            }
            return count;
        }

        @Override
        public Set<K> currentKeys() {
            return Collections.unmodifiableSet(theCache.keySet());
        }

        protected class CacheEntry {

            private volatile NamingException failure;
            private volatile SearchResult<R> result;
            private ScheduledFuture<?> future;

            public SearchResult<R> getSearchResult(LdapConnectionHandler connectionHandler, K key) throws IOException, NamingException {
                if (failure != null) {
                    SECURITY_LOGGER.tracef("Using cached failure for search with key '%s'", key);
                    throw failure;
                } else if (result != null) {
                    SECURITY_LOGGER.tracef("Using cached result for search with key '%s'", key);
                    return result;
                }

                synchronized (this) {
                    if (failure != null) {
                        SECURITY_LOGGER.tracef("Using cached failure for search with key '%s'", key);
                        throw failure;
                    } else if (result != null) {
                        SECURITY_LOGGER.tracef("Using cached result for search with key '%s'", key);
                        return result;
                    }

                    try {
                        R result = internalSearch(connectionHandler, key);
                        SECURITY_LOGGER.tracef("New search for entry with key '%s'", key);
                        SearchResult<R> searchResult = new SearchResultImpl<R>(result);
                        return this.result = searchResult;
                    } catch (NamingException e) {
                        if (cacheFailures) {
                            failure = e;
                        }
                        throw e;
                    }
                }
            }

            /**
             * Set the {@link ScheduledFuture} for the eviction of this entry.
             *
             * Note: This method should only be called by a {@link Thread} that has already obtained a lock to the cache.
             *
             * @param future - The {@link ScheduledFuture} for the eviction of this entry.
             */
            public void setFuture(ScheduledFuture<?> future) {
                this.future = future;
            }

            /**
             * Cancel the {@link ScheduledFuture} for the eviction of this entry.
             *
             * This could be called either because the entry is being manually evicted from the cache or because eviction is
             * being rescheduled.
             *
             * Note: This method should only be called by a {@link Thread} that has already obtained a lock to the cache.
             */
            public void cancelFuture() {
                if (future != null) {
                    future.cancel(true);
                }
            }
        }

    }

    private class BySearchCache extends BaseSearchCache {

        private BySearchCache(final int evictionTime, final boolean cacheFailures, final int maxSize) {
            super(evictionTime, cacheFailures, maxSize);
        }

        @Override
        protected CacheEntry readFromCache(final K key) throws IOException, NamingException {
            return theCache.get(key);
        }
    }

    /**
     * Cache byAccessTime. Always remove the cached entry and re-add and move it to the end of the list.
     */
    private class ByAccessCache extends BaseSearchCache {

        private ByAccessCache(final int evictionTime, final boolean cacheFailures, final int maxSize) {
            super(evictionTime, cacheFailures, maxSize);
        }

        @Override
        protected CacheEntry readFromCache(final K key) throws IOException, NamingException {
            CacheEntry entry = theCache.remove(key);
            if(entry != null) {
                theCache.put(key, entry);
                prepareEviction(key,entry);
            }
            return entry;
        }

    }

    private class SearchResultImpl<R> implements SearchResult<R> {

        private final ConcurrentMap<AttachmentKey<?>, Object> valueAttachments = new ConcurrentHashMap<AttachmentKey<?>, Object>();
        private final R result;

        private SearchResultImpl(R result) {
            this.result = result;
        }

        @Override
        public R getResult() {
            return result;
        }

        @Override
        public <T> T getAttachment(AttachmentKey<T> key) {
            return key.cast(valueAttachments.get(key));
        }

        @Override
        public <T> T attach(AttachmentKey<T> key, T value) {
            return key.cast(valueAttachments.put(key, value));
        }

        @Override
        public <T> T detach(AttachmentKey<T> key) {
            return key.cast(valueAttachments.remove(key));
        }

    }

}
