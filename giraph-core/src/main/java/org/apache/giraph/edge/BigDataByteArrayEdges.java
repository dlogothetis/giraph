/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.giraph.edge;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.UnmodifiableIterator;
import org.apache.giraph.utils.ExtendedDataInput;
import org.apache.giraph.utils.ExtendedDataOutput;
import org.apache.giraph.utils.Trimmable;
import org.apache.giraph.utils.WritableUtils;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * {@link OutEdges} implementation backed by a list of {@link ExtendedDataOutput}.
 * Parallel edges are allowed.
 * This implementation is similar to {@link ByteArrayEdges} except it is not limited by size of underlying array
 * and will allow adding new edges until machine runs out of memory
 * Note: this implementation is optimized for space usage,
 * but edge removals are expensive.
 *
 * @param <I> Vertex id
 * @param <E> Edge value
 */
public class BigDataByteArrayEdges <I extends WritableComparable, E extends Writable>
        extends ConfigurableOutEdges<I, E>
        implements ReuseObjectsOutEdges<I, E>, Trimmable
{
    private static final int DEFAULT_MAX_BUFFER_SIZE = Integer.MAX_VALUE;
    /** maximum buffer size. Sometimes it will be exceeded by the last edge, if last edge is bigger in size than anything seen before **/
    private final int maxBufferSize;
    /** expected initial buffer capacity. Use implementation default is empty **/
    private Optional<Integer> capacity = Optional.empty();
    /** Current serialized edges. */
    private ExtendedDataOutput currentDataOutput = null;
    /** All serialized edges. */
    private List<ExtendedDataOutput> dataOutputs;
    /** Number of edges. */
    private int edgeCount = 0;
    /** Temporary buffer used for one edge serialization */
    private ExtendedDataOutput tempDataOutput;
    @VisibleForTesting
    BigDataByteArrayEdges(int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
    }

    public BigDataByteArrayEdges()
    {
        this(DEFAULT_MAX_BUFFER_SIZE);
    }

    @Override
    public void initialize(Iterable<Edge<I, E>> edges) {
        reset(Optional.empty());
        for (Edge<I, E> edge : edges) {
            add(edge);
        }
    }

    @Override
    public void initialize(int capacity) {
        reset(Optional.of(Math.min(capacity, maxBufferSize)));
    }

    @Override
    public void initialize() {
        this.reset(this.capacity); //don't reset capacity
    }

    private void reset(Optional<Integer> capacity) {
        this.capacity = capacity;
        this.edgeCount = 0;
        this.currentDataOutput = null;
        this.dataOutputs = new ArrayList<>();
        this.tempDataOutput = getConf().createExtendedDataOutput();
        createNextDataOutput();
    }

    /**
     * Return whether maximum buffer size of current data output be exceeded
     * if byteCount bytes are added to it
     */
    private boolean isCurrentDataOutputFull(int byteCount) {
        return currentDataOutput.getPos() + byteCount > maxBufferSize;
    }

    /**
     * Create and return ExtendedDataOutput with specified capacity settings
     */
    private ExtendedDataOutput createExtendedDataOutput() {
        return capacity.isPresent() ?
                getConf().createExtendedDataOutput(capacity.get()) :
                getConf().createExtendedDataOutput();
    }

    /**
     * Create new current data output
     */
    private void createNextDataOutput() {
        currentDataOutput = createExtendedDataOutput();
        dataOutputs.add(currentDataOutput);
    }

    @Override
    public void add(Edge<I, E> edge) {
        try {
            tempDataOutput.reset();
            WritableUtils.writeEdge(tempDataOutput, edge);
            int edgeByteSize = tempDataOutput.getPos();
            if (isCurrentDataOutputFull(edgeByteSize)) {
                createNextDataOutput();
            }
            currentDataOutput.write(tempDataOutput.getByteArray(), 0, edgeByteSize);
            edgeCount++;

        }
        catch (IOException e) {
            throw new IllegalStateException("add: Failed to write to the new " +
                    "byte array");
        }
    }

    @Override
    public void remove(I targetVertexId) {
        // Note that this is very expensive (deserializes all edges).
        List<ExtendedDataOutput> outputsToRemove = new LinkedList<>();
        ReusableEdge<I, E> representativeEdge =
                getConf().createReusableEdge();
        for (int outputIdx = 0; outputIdx < dataOutputs.size(); outputIdx++) {
            ExtendedDataOutput output = dataOutputs.get(outputIdx);
            ExtendedDataInput input = getConf().createExtendedDataInput(output.toByteArray());
            ExtendedDataOutput updatedOutput = createExtendedDataOutput();
            int remainingCount = 0;
            boolean edgesRemoved = false;
            while(!input.endOfInput()) {
                try {
                    WritableUtils.readEdge(input, representativeEdge);
                    if (representativeEdge.getTargetVertexId().equals(targetVertexId)) {
                        edgesRemoved = true;
                        edgeCount--;
                    }
                    else {
                        remainingCount++;
                        WritableUtils.writeEdge(updatedOutput, representativeEdge);
                    }
                }
                catch (IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            }
            if (edgesRemoved) {
                if (remainingCount == 0) {
                    outputsToRemove.add(output);
                }
                else {
                    dataOutputs.set(outputIdx, updatedOutput);
                }
            }

        }

        if (!outputsToRemove.isEmpty()) {
            dataOutputs.removeAll(outputsToRemove);
        }

        if (dataOutputs.isEmpty()) {
            createNextDataOutput();
        }
        else {
            currentDataOutput = dataOutputs.get(dataOutputs.size() - 1);
        }
    }

    @Override
    public int size() {
        return edgeCount;
    }

    @Override
    public Iterator<Edge<I, E>> iterator() {
        return new BigDataByteArrayEdgeIterator(dataOutputs.iterator());
    }

    /**
     *  Compact data output at given data output index
     */
    private void trim(int dataOutputIdx) {
        if (dataOutputIdx >= dataOutputs.size()) {
            return;
        }
        ExtendedDataOutput output = dataOutputs.get(dataOutputIdx);
        if (output.getPos() < output.getByteArray().length) {
            byte [] trimmedBytes = output.toByteArray();
            output = getConf().createExtendedDataOutput(trimmedBytes, trimmedBytes.length);
            dataOutputs.set(dataOutputIdx, output);
            if (dataOutputIdx == dataOutputs.size() - 1) {
                currentDataOutput = output;
            }
        }
    }

    /**
     * Compact all data output buffers
     * by replacing every data output to buffer
     * whose size exceeds current position with new buffer whose size fits the data
     * and copying data into it
     */
    @Override
    public void trim() {
        if (currentDataOutput == null) {
            return;
        }

        for (int dataOutputIdx = 0; dataOutputIdx < dataOutputs.size(); dataOutputIdx++) {
            trim(dataOutputIdx);
        }
    }

    @Override
    public void write(DataOutput out) throws IOException {
        out.writeInt(dataOutputs.size());
        for (ExtendedDataOutput dataOutput : dataOutputs) {
            out.writeInt(dataOutput.getPos());
            out.write(dataOutput.getByteArray(), 0, dataOutput.getPos());
        }
        out.writeInt(edgeCount);
    }

    @Override
    public void readFields(DataInput in)
            throws IOException {
        initialize();
        int dataOutputsCount = in.readInt();
        for (int dataOutputIdx = 0; dataOutputIdx < dataOutputsCount; dataOutputIdx++) {
            byte [] bytes = new byte[in.readInt()];
            in.readFully(bytes, 0, bytes.length);
            currentDataOutput.write(bytes);
        }
        edgeCount = in.readInt();
    }

    private class BigDataByteArrayEdgeIterator
            extends UnmodifiableIterator<Edge<I, E>> {
        private final Iterator<ExtendedDataOutput> byteArrayEdgesListIterator;
        ExtendedDataInput currentInput = null;
        /** Representative edge object. */
        private ReusableEdge<I, E> representativeEdge =
                getConf().createReusableEdge();
        private BigDataByteArrayEdgeIterator(Iterator<ExtendedDataOutput> byteArrayEdgesListIterator) {
            this.byteArrayEdgesListIterator = byteArrayEdgesListIterator;
        }
        private boolean inputHasNext() {
            return currentInput != null && !currentInput.endOfInput();
        }
        @Override
        public boolean hasNext()
        {
            while (!inputHasNext() && byteArrayEdgesListIterator.hasNext()) {
                ExtendedDataOutput currentOutput = byteArrayEdgesListIterator.next();
                currentInput = getConf().createExtendedDataInput(currentOutput.toByteArray());
            }
            return inputHasNext();
        }

        @Override
        public Edge<I, E> next()
        {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            try {
                WritableUtils.readEdge(currentInput, representativeEdge);
            } catch (IOException e) {
                throw new IllegalStateException("next: Failed on pos " +
                        currentInput.getPos() + " edge " + representativeEdge);
            }
            return representativeEdge;
        }
    }
}
