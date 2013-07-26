package org.infinispan.loaders;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.infinispan.context.Flag.*;
import static org.infinispan.factories.KnownComponentNames.CACHE_MARSHALLER;
import static org.infinispan.loaders.decorator.AbstractDelegatingCacheLoader.undelegateCacheLoader;

import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.infinispan.AdvancedCache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.CacheLoaderConfiguration;
import org.infinispan.configuration.cache.LoadersConfiguration;
import org.infinispan.configuration.cache.CacheStoreConfiguration;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContextContainer;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.interceptors.CacheLoaderInterceptor;
import org.infinispan.interceptors.CacheStoreInterceptor;
import org.infinispan.loaders.decorator.ChainingCacheLoader;
import org.infinispan.loaders.decorators.AsyncStore;
import org.infinispan.loaders.decorators.ChainingCacheStore;
import org.infinispan.loaders.decorators.ReadOnlyStore;
import org.infinispan.loaders.decorators.SingletonStore;
import org.infinispan.loaders.decorators.SingletonStoreConfig;
import org.infinispan.commons.CacheConfigurationException;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.marshall.StreamingMarshaller;
import org.infinispan.commons.util.Util;
import org.infinispan.loaders.spi.BasicCacheLoader;
import org.infinispan.loaders.spi.BulkCacheLoader;
import org.infinispan.util.TimeService;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

public class CacheLoaderManagerImpl implements CacheLoaderManager {

   Configuration configuration;
   LoadersConfiguration clmConfig;
   AdvancedCache<Object, Object> cache;
   StreamingMarshaller m;
   ChainingCacheLoader loader;
   InvocationContextContainer icc;
   TransactionManager transactionManager;
   private TimeService timeService;
   private static final Log log = LogFactory.getLog(CacheLoaderManagerImpl.class);

   @Inject
   public void inject(AdvancedCache<Object, Object> cache,
                      @ComponentName(CACHE_MARSHALLER) StreamingMarshaller marshaller,
                      Configuration configuration, InvocationContextContainer icc, TransactionManager transactionManager,
                      TimeService timeService) {
      this.cache = cache;
      this.m = marshaller;
      this.configuration = configuration;
      this.icc = icc;
      this.transactionManager = transactionManager;
      this.timeService = timeService;
   }

   @Override
   public CacheStore getCacheStore() {
      return null;  // TODO: Customise this generated block
   }

   @Override
   public ChainingCacheLoader getCacheLoader() {
      return loader;
   }

   @Override
   public CacheLoader getChainingCacheLoader() {
      return null;  // TODO: Customise this generated block
   }

   @Override
   public void purge() {
      CacheStore cs = getCacheStore();
      if (cs != null) try {
         cs.clear();
      } catch (CacheLoaderException e) {
         throw new CacheException("Unable to purge cache store", e);
      }
   }

   @Override
   public boolean isUsingPassivation() {
      return isEnabled() ? clmConfig.passivation() : false;
   }

   @Override
   public boolean isShared() {
      return isEnabled() ? clmConfig.shared() : false;
   }

   @Override
   public boolean isFetchPersistentState() {
      return isEnabled() ? clmConfig.fetchPersistentState() : false;
   }

   @Override
   @Start(priority = 10)
   public void start() {
      clmConfig = configuration.loaders();
      if (clmConfig != null) {
         try {
            loader = createCacheLoader();
            if (loader == null) return;
            Transaction xaTx = null;
            if (transactionManager != null) {
               xaTx = transactionManager.suspend();
            }
            try {
               loader.start();
               loader.purgeIfNecessary();
            } finally {
               if (transactionManager != null && xaTx != null) {
                  transactionManager.resume(xaTx);
               }
            }
         } catch (Exception e) {
            throw new CacheException("Unable to start cache loaders", e);
         }
      }
   }

   @Override
   public boolean isEnabled() {
      return clmConfig != null;
   }

   @Override
   public void disableCacheLoader(String loaderType) {
      if (isEnabled()) {
         ComponentRegistry cr = cache.getComponentRegistry();
         CacheLoaderInterceptor cli = cr.getComponent(CacheLoaderInterceptor.class);
         CacheStoreInterceptor csi = cr.getComponent(CacheStoreInterceptor.class);

         loader.removeCacheLoader(loaderType);
         if (loader.isEmpty()) {
            cli.disableInterceptor();
            csi.disableInterceptor();
            cache.removeInterceptor(cli.getClass());
            cache.removeInterceptor(csi.getClass());
            clmConfig = null;
         }
      }
   }

   @Override
   public <T extends BasicCacheLoader> List<T> getCacheLoaders(Class<T> loaderClass) {
      if (loader == null)
         return Collections.emptyList();
      return loader.getCacheLoaders(loaderClass);
   }

   @Override
   public Collection<String> getCacheLoadersAsString() {
      List<BasicCacheLoader> loaders = loader.getLoaders();
      Set<String> loaderTypes = new HashSet<String>(loaders.size());
      for (BasicCacheLoader loader : loaders)
         loaderTypes.add(undelegateCacheLoader(loader).getClass().getName());
      return loaderTypes;
   }

   @Override
   public void purgeExpired() {
      if (!isEnabled())
         return;

      long start;
      try {
         if (log.isTraceEnabled()) {
            log.trace("Purging cache store of expired entries");
            start = timeService.time();
         }
         loader.purgeExpired();
         if (log.isTraceEnabled()) {
            log.tracef("Purging cache store completed in %s",
                       Util.prettyPrintTime(timeService.timeDuration(start, TimeUnit.MILLISECONDS)));
         }
      } catch (Exception e) {
         log.exceptionPurgingDataContainer(e);
      }
   }

   /**
    * Performs a preload on the cache based on the cache loader preload configs used when configuring the cache.
    */
   @Override
   @Start(priority = 56)
   public void preload() {
      BulkCacheLoader preloadCl;
      if (loader != null && ((preloadCl = loader.getBulkCacheLoader()) != null)) {
         if (clmConfig.preload()) {
            long start = 0;
            boolean debugTiming = log.isDebugEnabled();
            if (debugTiming) {
               start = timeService.time();
               log.debugf("Preloading transient state from cache loader %s", loader);
            }


            int loaded = 0;
            BulkCacheLoader.EntriesIterator it = null;
            try {
               it = preloadCl.bulkLoad();
               int maxEntries = getMaxEntries();
               AdvancedCache<Object, Object> flaggedCache = getCacheForStateInsertion();
               while (it.hasNext() && loaded <= maxEntries) {
                  Object key = it.nextKey();
                  InternalCacheValue value = it.value();
                  flaggedCache.put(key, value.getValue(), value.getLifespan(), TimeUnit.MILLISECONDS,
                                   value.getMaxIdle(), TimeUnit.MILLISECONDS);
               }
            } finally {
               if (it != null)
                  it.close();
            }

            if (debugTiming) {
               log.debugf("Preloaded %s keys in %s", loaded, Util.prettyPrintTime(timeService.timeDuration(start, MILLISECONDS)));
            }
         }
      }
   }

   private boolean localIndexingEnabled() {
      return configuration.indexing().enabled() && configuration.indexing().indexLocalOnly();
   }

   private int getMaxEntries() {
      int ne = Integer.MAX_VALUE;
      if (configuration.eviction().strategy().isEnabled()) ne = configuration.eviction().maxEntries();
      return ne;
   }

   @Override
   @Stop
   public void stop() {
      if (loader != null)
         loader.stop();
      loader = null;
   }

   ChainingCacheLoader createCacheLoader() throws Exception {

      if (clmConfig.cacheLoaders().isEmpty()) return null;
      ChainingCacheLoader ccl = new ChainingCacheLoader();

      // only one cache loader may have fetchPersistentState to true.
      int numLoadersWithFetchPersistentState = 0;
      for (CacheLoaderConfiguration cfg : clmConfig.cacheLoaders()) {
         boolean fetchPersistentState = false;
         if (cfg instanceof CacheStoreConfiguration) {
            CacheStoreConfiguration scfg = (CacheStoreConfiguration) cfg;
            assertNotSingletonAndShared(scfg);
            fetchPersistentState = scfg.fetchPersistentState();
            if (fetchPersistentState) numLoadersWithFetchPersistentState++;
         }
         if (numLoadersWithFetchPersistentState > 1)
            throw new Exception("Invalid cache loader configuration!!  Only ONE cache loader may have fetchPersistentState set to true.  Cache will not start!");

         BasicCacheLoader l = null; // todo implement this createCacheLoader(LegacyConfigurationAdaptor.adapt(cfg), cache);
         ccl.addCacheLoader(l, fetchPersistentState);
      }

      return ccl;
   }

   CacheLoader createCacheLoader(CacheLoaderConfig cfg, AdvancedCache<Object, Object> cache) throws Exception {
      CacheLoader tmpLoader = (CacheLoader) Util.getInstance(cfg.getCacheLoaderClassName(), cache.getClassLoader());

      if (tmpLoader != null) {
         if (cfg instanceof CacheStoreConfig) {
            CacheStore tmpStore = (CacheStore) tmpLoader;
            // async?
            CacheStoreConfig cfg2 = (CacheStoreConfig) cfg;
            if (cfg2.getAsyncStoreConfig().isEnabled()) {
               tmpStore = createAsyncStore(tmpStore, cfg2);
               tmpLoader = tmpStore;
            }

            // read only?
            if (cfg2.isIgnoreModifications()) {
               tmpStore = new ReadOnlyStore(tmpStore);
               tmpLoader = tmpStore;
            }

            // singleton?
            SingletonStoreConfig ssc = cfg2.getSingletonStoreConfig();
            if (ssc != null && ssc.isSingletonStoreEnabled()) {
               tmpStore = new SingletonStore(tmpStore, cache, ssc);
               tmpLoader = tmpStore;
            }
         }

         // load props
         tmpLoader.init(cfg, cache, m);
      }
      return tmpLoader;
   }

   protected AsyncStore createAsyncStore(CacheStore tmpStore, CacheStoreConfig cfg2) {
      return new AsyncStore(tmpStore, cfg2.getAsyncStoreConfig());
   }

   void assertNotSingletonAndShared(CacheStoreConfiguration cfg) {
      if (cfg.singletonStore().enabled() && clmConfig.shared())
         throw new CacheConfigurationException("Invalid cache loader configuration!!  If a cache loader is configured as a singleton, the cache loader cannot be shared in a cluster!");
   }

   private AdvancedCache<Object, Object> getCacheForStateInsertion() {
      List<Flag> flags = new ArrayList<Flag>(Arrays.asList(
            CACHE_MODE_LOCAL, SKIP_OWNERSHIP_CHECK, IGNORE_RETURN_VALUES, SKIP_CACHE_STORE, SKIP_LOCKING));

      if (clmConfig.shared()) {
         if (!localIndexingEnabled())
            flags.add(SKIP_INDEXING);
      } else {
         flags.add(SKIP_INDEXING);
      }

      return cache.getAdvancedCache()
            .withFlags(flags.toArray(new Flag[flags.size()]));
   }
}
