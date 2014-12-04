package com.hazelcast.client;

import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.EntryAdapter;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.LifecycleEvent;
import com.hazelcast.core.LifecycleListener;
import com.hazelcast.instance.GroupProperties;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.TestUtil;
import com.hazelcast.test.AssertTask;
import com.hazelcast.test.HazelcastSerialClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(HazelcastSerialClassRunner.class)
@Category(QuickTest.class)
public class ClientSplitBrainTest extends HazelcastTestSupport {

    @Before
    @After
    public void cleanup() {
        HazelcastClient.shutdownAll();
        Hazelcast.shutdownAll();
    }


    @Test
    public void testClientListeners_InSplitBrain() throws InterruptedException {
        Config config = new Config();
        config.setProperty(GroupProperties.PROP_MERGE_FIRST_RUN_DELAY_SECONDS, "5");
        config.setProperty(GroupProperties.PROP_MERGE_NEXT_RUN_DELAY_SECONDS, "5");
        HazelcastInstance h1 = Hazelcast.newHazelcastInstance(config);
        HazelcastInstance h2 = Hazelcast.newHazelcastInstance(config);

        final ClientConfig clientConfig = new ClientConfig();
        clientConfig.addAddress("127.0.0.1");
        final HazelcastInstance client = HazelcastClient.newHazelcastClient(clientConfig);

        final String mapName = randomMapName();
        final IMap<Object, Boolean> map1 = h1.getMap(mapName);
        final IMap<Object, Boolean> map2 = h2.getMap(mapName);
        final IMap<Object, Boolean> mapC = client.getMap(mapName);

        final CountDownLatch mergedLatch = new CountDownLatch(1);

        final int ITERATION_COUNT = 20;
        final AtomicInteger[] atomicIntegers = new AtomicInteger[ITERATION_COUNT];
        for (int i = 0; i < ITERATION_COUNT; i++) {
            atomicIntegers[i] = new AtomicInteger(0);
        }

        h1.getLifecycleService().addLifecycleListener(new LifecycleListener() {
            public void stateChanged(LifecycleEvent event) {
                if (event.getState() == LifecycleEvent.LifecycleState.MERGED) {
                    mergedLatch.countDown();
                }
            }
        });

        h2.getLifecycleService().addLifecycleListener(new LifecycleListener() {
            public void stateChanged(LifecycleEvent event) {
                if (event.getState() == LifecycleEvent.LifecycleState.MERGED) {
                    mergedLatch.countDown();
                }
            }
        });

        map1.addEntryListener(new EntryAdapter<Object, Boolean>() {
            @Override
            public void onEntryEvent(EntryEvent<Object, Boolean> event) {
                final Boolean shouldCount = event.getValue();
                if (shouldCount) {
                    atomicIntegers[(Integer) event.getKey()].incrementAndGet();
                }
            }
        }, true);

        map2.addEntryListener(new EntryAdapter<Object, Boolean>() {
            @Override
            public void onEntryEvent(EntryEvent<Object, Boolean> event) {
                final Boolean shouldCount = event.getValue();
                if (shouldCount) {
                    atomicIntegers[(Integer) event.getKey()].incrementAndGet();
                }
            }
        }, true);


        mapC.addEntryListener(new EntryAdapter<Object, Boolean>() {
            @Override
            public void onEntryEvent(EntryEvent<Object, Boolean> event) {
                final Boolean shouldCount = event.getValue();
                if (shouldCount) {
                    atomicIntegers[(Integer) event.getKey()].incrementAndGet();
                }
            }
        }, true);


        closeConnectionBetween(h2, h1);
        final Random random = new Random();

        final Thread clientThread = new Thread() {
            @Override
            public void run() {

                //Just to generate pressure
                while (mergedLatch.getCount() != 0) {
                    mapC.put(random.nextInt() % 1000, false);
                }

                for (int i = 0; i < ITERATION_COUNT; i++) {
                    mapC.put(i, true);
                }
            }
        };

        clientThread.start();
        assertTrue(mergedLatch.await(30, TimeUnit.SECONDS));
        assertEquals(2, h1.getCluster().getMembers().size());
        assertEquals(2, h2.getCluster().getMembers().size());
        clientThread.join();

        for (int i = 0; i < ITERATION_COUNT; i++) {
            final int id = i;
            assertTrueEventually(new AssertTask() {
                @Override
                public void run() throws Exception {
                    assertEquals("id " + id, 3, atomicIntegers[id].get());
                }
            }, 10);
        }


    }

    private void closeConnectionBetween(HazelcastInstance h1, HazelcastInstance h2) {
        if (h1 == null || h2 == null) return;
        final Node n1 = TestUtil.getNode(h1);
        final Node n2 = TestUtil.getNode(h2);
        n1.clusterService.removeAddress(n2.address);
        n2.clusterService.removeAddress(n1.address);
    }
}
