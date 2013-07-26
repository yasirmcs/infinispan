package org.infinispan.loaders.jdbc.mixed;

import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheLoaderConfiguration;
import org.infinispan.loaders.CacheLoaderManager;
import org.infinispan.loaders.CacheStore;
import org.infinispan.loaders.jdbc.ManagedConnectionFactoryTest;
import org.infinispan.loaders.jdbc.TableManipulation;
import org.infinispan.loaders.jdbc.configuration.JdbcMixedCacheStoreConfiguration;
import org.infinispan.loaders.jdbc.connectionfactory.ConnectionFactoryConfig;
import org.infinispan.loaders.jdbc.connectionfactory.ManagedConnectionFactory;
import org.infinispan.manager.CacheContainer;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.infinispan.test.fwk.UnitTestDatabaseManager;
import org.testng.annotations.Test;

/**
 * @author Mircea.Markus@jboss.com
 */
@Test(groups = "functional", testName = "loaders.jdbc.mixed.MixedStoreWithManagedConnectionTest")
public class MixedStoreWithManagedConnectionTest extends ManagedConnectionFactoryTest {

   @Override
   protected CacheStore createCacheStore() throws Exception {
      ConnectionFactoryConfig connectionFactoryConfig = new ConnectionFactoryConfig();
      connectionFactoryConfig.setConnectionFactoryClass(ManagedConnectionFactory.class.getName());
      connectionFactoryConfig.setDatasourceJndiLocation(getDatasourceLocation());
      TableManipulation stringsTm = UnitTestDatabaseManager.buildStringTableManipulation();
      stringsTm.setTableNamePrefix("STRINGS_TABLE");
      TableManipulation binaryTm = UnitTestDatabaseManager.buildBinaryTableManipulation();
      binaryTm.setTableNamePrefix("BINARY_TABLE");
      JdbcMixedCacheStoreConfig cacheStoreConfig = new JdbcMixedCacheStoreConfig(connectionFactoryConfig, binaryTm, stringsTm);
      cacheStoreConfig.setPurgeSynchronously(true);
      JdbcMixedCacheStore store = new JdbcMixedCacheStore();
      store.init(cacheStoreConfig, getCache(), getMarshaller());
      store.start();
      assert store.getConnectionFactory() instanceof ManagedConnectionFactory;
      return store;
   }


   public void testLoadFromFile() throws Exception {
      CacheContainer cm = null;
      try {
         cm = TestCacheManagerFactory.fromXml("configs/managed/mixed-managed-connection-factory.xml");
         Cache<String, String> first = cm.getCache("first");
         Cache<String, String> second = cm.getCache("second");

         CacheLoaderConfiguration firstCacheLoaderConfig = first.getCacheConfiguration().loaders().cacheLoaders().get(0);
         assert firstCacheLoaderConfig != null;
         CacheLoaderConfiguration secondCacheLoaderConfig = second.getCacheConfiguration().loaders().cacheLoaders().get(0);
         assert secondCacheLoaderConfig != null;
         assert firstCacheLoaderConfig instanceof JdbcMixedCacheStoreConfiguration;
         assert secondCacheLoaderConfig instanceof JdbcMixedCacheStoreConfiguration;
         CacheLoaderManager cacheLoaderManager = first.getAdvancedCache().getComponentRegistry().getComponent(CacheLoaderManager.class);
         JdbcMixedCacheStore loader = (JdbcMixedCacheStore) cacheLoaderManager.getCacheLoader();
         assert loader.getConnectionFactory() instanceof ManagedConnectionFactory;
      } finally {
         TestingUtil.killCacheManagers(cm);
      }
   }

   @Override
   public String getDatasourceLocation() {
      return "java:/MixedStoreWithManagedConnectionTest/DS";
   }
}
