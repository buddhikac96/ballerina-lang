/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.siddhi.core.query.output.callback;

import org.ballerinalang.siddhi.core.debugger.SiddhiDebugger;
import org.ballerinalang.siddhi.core.event.ComplexEventChunk;
import org.ballerinalang.siddhi.core.event.state.StateEvent;
import org.ballerinalang.siddhi.core.event.state.StateEventPool;
import org.ballerinalang.siddhi.core.event.stream.StreamEventPool;
import org.ballerinalang.siddhi.core.event.stream.converter.StreamEventConverter;
import org.ballerinalang.siddhi.core.table.CompiledUpdateSet;
import org.ballerinalang.siddhi.core.table.Table;
import org.ballerinalang.siddhi.core.util.collection.operator.CompiledCondition;

/**
 * Implementation of {@link OutputCallback} to receive processed Siddhi events from
 * Siddhi queries and update data into a {@link Table}
 * based on received events and condition.
 */
public class UpdateTableCallback extends OutputCallback {
    private final int matchingStreamIndex;
    private Table table;
    private CompiledCondition compiledCondition;
    private CompiledUpdateSet compiledUpdateSet;
    private boolean convertToStreamEvent;
    private StateEventPool stateEventPool;
    private StreamEventPool streamEventPool;
    private StreamEventConverter streamEventConverter;

    public UpdateTableCallback(Table table, CompiledCondition compiledCondition, CompiledUpdateSet compiledUpdateSet,
                               int matchingStreamIndex, boolean convertToStreamEvent, StateEventPool stateEventPool,
                               StreamEventPool streamEventPool, StreamEventConverter streamEventConverter,
                               String queryName) {
        super(queryName);
        this.table = table;
        this.compiledCondition = compiledCondition;
        this.compiledUpdateSet = compiledUpdateSet;
        this.matchingStreamIndex = matchingStreamIndex;
        this.convertToStreamEvent = convertToStreamEvent;
        this.stateEventPool = stateEventPool;
        this.streamEventPool = streamEventPool;
        this.streamEventConverter = streamEventConverter;
    }

    @Override
    public synchronized void send(ComplexEventChunk updatingEventChunk, int noOfEvents) {
        if (getSiddhiDebugger() != null) {
            getSiddhiDebugger()
                    .checkBreakPoint(getQueryName(), SiddhiDebugger.QueryTerminal.OUT, updatingEventChunk.getFirst());
        }
        updatingEventChunk.reset();
        if (updatingEventChunk.hasNext()) {
            ComplexEventChunk<StateEvent> updatingStateEventChunk = constructMatchingStateEventChunk(updatingEventChunk,
                    convertToStreamEvent, stateEventPool, matchingStreamIndex, streamEventPool, streamEventConverter);
            table.updateEvents(updatingStateEventChunk, compiledCondition, compiledUpdateSet, noOfEvents);
        }
    }

}
