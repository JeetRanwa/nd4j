package org.nd4j.parameterserver.client;

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import lombok.extern.slf4j.Slf4j;
import org.agrona.CloseHelper;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.junit.*;
import org.nd4j.aeron.ipc.AeronUtil;
import org.nd4j.aeron.ipc.NDArrayMessage;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.parameterserver.ParameterServerListener;
import org.nd4j.parameterserver.ParameterServerSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by agibsonccc on 10/3/16.
 */
@Slf4j
public class ParameterServerClientPartialTest {
    private static MediaDriver mediaDriver;
    private static Aeron.Context ctx;
    private static ParameterServerSubscriber masterNode,slaveNode;
    private int[] shape = {2,2};
    private static Aeron aeron;

    @BeforeClass
    public static void before() throws Exception {
        final MediaDriver.Context ctx = new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirsDeleteOnStart(true)
                .termBufferSparseFile(false)
                .conductorIdleStrategy(new BusySpinIdleStrategy())
                .receiverIdleStrategy(new BusySpinIdleStrategy())
                .senderIdleStrategy(new BusySpinIdleStrategy());

        mediaDriver = MediaDriver.launchEmbedded(ctx);
        aeron = Aeron.connect(getContext());
        masterNode = new ParameterServerSubscriber(mediaDriver);
        masterNode.setAeron(aeron);
        masterNode.run(new String[] {
                "-m","true",
                "-p","40223",
                "-h","localhost",
                "-id","11",
                "-md", mediaDriver.aeronDirectoryName(),
                "-sp", "20000",
                "-s","2,2"
        });

        assertTrue(masterNode.isMaster());
        assertEquals(40223,masterNode.getPort());
        assertEquals("localhost",masterNode.getHost());
        assertEquals(11,masterNode.getStreamId());
        assertEquals(12,masterNode.getResponder().getStreamId());
        assertEquals(masterNode.getMasterArray(),Nd4j.create(new int[]{2,2}));

        slaveNode = new ParameterServerSubscriber(mediaDriver);
        slaveNode.setAeron(aeron);
        slaveNode.run(new String[] {
                "-p","40226",
                "-h","localhost",
                "-id","10",
                "-pm",masterNode.getSubscriber().connectionUrl(),
                "-md", mediaDriver.aeronDirectoryName(),
                "-sp", "21000"
        });

        assertFalse(slaveNode.isMaster());
        assertEquals(40226,slaveNode.getPort());
        assertEquals("localhost",slaveNode.getHost());
        assertEquals(10,slaveNode.getStreamId());

        int tries = 10;
        while(!masterNode.subscriberLaunched() && !slaveNode.subscriberLaunched() && tries < 10) {
            Thread.sleep(10000);
            tries++;
        }

        if(!masterNode.subscriberLaunched() && !slaveNode.subscriberLaunched()) {
            throw new IllegalStateException("Failed to start master and slave node");
        }

        log.info("Using media driver directory " + mediaDriver.aeronDirectoryName());
        log.info("Launched media driver");
    }


    @AfterClass
    public static void after() {
        CloseHelper.quietClose(aeron);
        CloseHelper.quietClose(mediaDriver);
    }

    @Test
    public void testServer() throws Exception {
        ParameterServerClient client = ParameterServerClient
                .builder()
                .aeron(aeron)
                .ndarrayRetrieveUrl(masterNode.getResponder().connectionUrl())
                .ndarraySendUrl(slaveNode.getSubscriber().connectionUrl())
                .subscriberHost("localhost")
                .subscriberPort(40325)
                .subscriberStream(12).build();
        assertEquals("localhost:40325:12",client.connectionUrl());
        //flow 1:
        /**
         * Client (40125:12): sends array to listener on slave(40126:10)
         * which publishes to master (40123:11)
         * which adds the array for parameter averaging.
         * In this case totalN should be 1.
         */
        client.pushNDArrayMessage(NDArrayMessage.of(Nd4j.ones(2),new int[]{0},0));
        log.info("Pushed ndarray");
        Thread.sleep(30000);
        ParameterServerListener listener = (ParameterServerListener) masterNode.getCallback();
        assertEquals(1,listener.getUpdater().numUpdates());
        INDArray assertion = Nd4j.create(new int[]{2,2});
        assertion.getColumn(0).addi(1.0);
        assertEquals(assertion,listener.getUpdater().ndArrayHolder().get());
        INDArray arr = client.getArray();
        assertEquals(assertion,arr);
    }






    private static Aeron.Context getContext() {
        if(ctx == null)
            ctx = new Aeron.Context().publicationConnectionTimeout(-1)
                    .availableImageHandler(AeronUtil::printAvailableImage)
                    .unavailableImageHandler(AeronUtil::printUnavailableImage)
                    .aeronDirectoryName(mediaDriver.aeronDirectoryName()).keepAliveInterval(10000)
                    .errorHandler(e -> log.error(e.toString(), e));
        return ctx;
    }


}
