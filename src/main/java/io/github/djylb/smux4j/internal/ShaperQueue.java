package io.github.djylb.smux4j.internal;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Fair writer queue shaped by stream and write class.
 */
public final class ShaperQueue {
    private static final Comparator<WriteRequest> REQUEST_ORDER = new Comparator<WriteRequest>() {
        @Override
        public int compare(WriteRequest left, WriteRequest right) {
            int classCompare = left.getWriteClass().ordinal() - right.getWriteClass().ordinal();
            if (classCompare != 0) {
                return classCompare;
            }
            return left.getSequence() < right.getSequence() ? -1 : (left.getSequence() == right.getSequence() ? 0 : 1);
        }
    };

    private final Map<Long, PriorityQueue<WriteRequest>> streams = new HashMap<Long, PriorityQueue<WriteRequest>>();
    private final ArrayDeque<Long> roundRobin = new ArrayDeque<Long>();
    private int size;

    public synchronized void push(WriteRequest request) {
        long streamId = request.getFrame().getStreamId();
        PriorityQueue<WriteRequest> queue = streams.get(streamId);
        if (queue == null) {
            queue = new PriorityQueue<WriteRequest>(16, REQUEST_ORDER);
            streams.put(streamId, queue);
            roundRobin.addLast(streamId);
        }
        queue.offer(request);
        size++;
    }

    public synchronized WriteRequest pop() {
        int rounds = roundRobin.size();
        for (int i = 0; i < rounds; i++) {
            Long streamId = roundRobin.pollFirst();
            if (streamId == null) {
                break;
            }

            PriorityQueue<WriteRequest> queue = streams.get(streamId);
            if (queue == null || queue.isEmpty()) {
                streams.remove(streamId);
                continue;
            }

            WriteRequest request = queue.poll();
            size--;
            if (!queue.isEmpty()) {
                roundRobin.addLast(streamId);
            } else {
                streams.remove(streamId);
            }
            return request;
        }
        return null;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized int size() {
        return size;
    }
}
