package io.github.djylb.smux4j;

import io.github.djylb.smux4j.internal.Allocator;
import io.github.djylb.smux4j.internal.BufferRing;
import io.github.djylb.smux4j.internal.ShaperQueue;
import io.github.djylb.smux4j.internal.WriteClass;
import io.github.djylb.smux4j.internal.WriteRequest;
import io.github.djylb.smux4j.internal.WriteResult;
import io.github.djylb.smux4j.frame.Command;
import io.github.djylb.smux4j.frame.Frame;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class InternalStructureTest {
    @Test
    public void allocatorUsesPowerOfTwoBuckets() {
        Allocator allocator = new Allocator();

        byte[] one = allocator.acquire(1);
        byte[] three = allocator.acquire(3);
        byte[] max = allocator.acquire(65_536);

        assertNotNull(one);
        assertEquals(1, one.length);
        assertNotNull(three);
        assertEquals(4, three.length);
        assertNotNull(max);
        assertEquals(65_536, max.length);
        assertNull(allocator.acquire(0));
        assertNull(allocator.acquire(65_537));

        allocator.release(three);
        assertSame(three, allocator.acquire(3));
    }

    @Test(expected = IllegalArgumentException.class)
    public void allocatorRejectsNonPowerOfTwoRelease() {
        new Allocator().release(new byte[3]);
    }

    @Test
    public void bufferRingPreservesOrderAndGrows() {
        BufferRing ring = new BufferRing(2);
        byte[] one = new byte[] {1};
        byte[] two = new byte[] {2};
        byte[] three = new byte[] {3};

        ring.push(one, 1);
        ring.push(two, 1);
        assertEquals(2, ring.size());

        BufferRing.Entry first = ring.pop();
        assertNotNull(first);
        assertSame(one, first.getOwnerBuffer());
        assertEquals(1, first.getLength());

        ring.push(three, 1);

        BufferRing.Entry second = ring.pop();
        BufferRing.Entry third = ring.pop();
        assertNotNull(second);
        assertNotNull(third);
        assertSame(two, second.getOwnerBuffer());
        assertSame(three, third.getOwnerBuffer());
        assertTrue(ring.isEmpty());
    }

    @Test
    public void bufferRingConsumeFrontReturnsRecycledBufferWhenDepleted() {
        BufferRing ring = new BufferRing(1);
        byte[] data = new byte[] {1, 2, 3, 4};
        byte[] target = new byte[3];

        ring.push(data, data.length);
        BufferRing.ConsumeResult first = ring.consumeFront(target, 0, target.length);
        assertEquals(3, first.getBytesCopied());
        assertNull(first.getRecycledBuffer());

        BufferRing.ConsumeResult second = ring.consumeFront(new byte[2], 0, 2);
        assertEquals(1, second.getBytesCopied());
        assertSame(data, second.getRecycledBuffer());
        assertTrue(ring.isEmpty());
    }

    @Test
    public void shaperQueuePrioritizesControlAndRoundsRobinAcrossStreams() {
        ShaperQueue queue = new ShaperQueue();

        WriteRequest data1 = new WriteRequest(
                WriteClass.DATA,
                Frame.create(1, Command.PSH, 1L, new byte[] {1}),
                2L,
                new CompletableFuture<WriteResult>()
        );
        WriteRequest control2 = new WriteRequest(
                WriteClass.CONTROL,
                Frame.create(1, Command.SYN, 2L),
                3L,
                new CompletableFuture<WriteResult>()
        );
        WriteRequest data2 = new WriteRequest(
                WriteClass.DATA,
                Frame.create(1, Command.PSH, 2L, new byte[] {2}),
                4L,
                new CompletableFuture<WriteResult>()
        );

        queue.push(data1);
        queue.push(control2);
        queue.push(data2);

        assertSame(data1, queue.pop());
        assertSame(control2, queue.pop());
        assertSame(data2, queue.pop());
        assertTrue(queue.isEmpty());
        assertNull(queue.pop());
        assertTrue(queue.isEmpty());
    }

    @Test
    public void configValidationMatchesOriginalConstraints() {
        Config.defaultConfig().validate();

        try {
            Config.builder().version(3).build().validate();
            fail("unsupported version should fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unsupported"));
        }

        try {
            Config.builder().maxFrameSize(0).build().validate();
            fail("invalid frame size should fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("max frame size"));
        }

        try {
            Config.builder().maxReceiveBuffer(32).maxStreamBuffer(64).build().validate();
            fail("stream buffer larger than receive buffer should fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("max stream buffer"));
        }
    }
}
