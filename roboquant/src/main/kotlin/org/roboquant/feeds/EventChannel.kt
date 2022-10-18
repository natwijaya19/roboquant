/*
 * Copyright 2020-2022 Neural Layer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.roboquant.feeds

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import org.roboquant.common.Logging
import org.roboquant.common.Timeframe
import org.roboquant.common.compareTo

/**
 * Wrapper around a [Channel] for communicating the [events][Event] of a [Feed]. It uses asynchronous communication
 * so the producing and receiving parts are decoupled. An EventChannel has limited capacity in order to prevent
 * memory problems when using large data feeds.
 *
 * It has built in support to limit the events that are being send to a certain [timeframe]. It is guaranteed that
 * no events outside that timeframe can be delivered to the channel.
 *
 * @param capacity The capacity of the channel in the number of events it can store before blocking the sender
 * @property timeframe Limit the events to this timeframe, default is INFINITE, so no limit
 * @constructor create a new EventChannel
 *
 */
open class EventChannel(capacity: Int = 100, val timeframe: Timeframe = Timeframe.INFINITE) {

    private val channel = Channel<Event>(capacity)
    private val logger = Logging.getLogger(EventChannel::class)

    /**
     * True if the channel is done, false otherwise
     */
    var done: Boolean = false
        private set

    /**
     * Add a new [event] to the channel. If the channel is full, it will remove older event first to make room, before
     * adding the new event. So this is a non-blocking send.
     *
     * This method is often preferable over the regular [send] in live trading scenario's since it prioritize more
     * recent data over maintaining a large backlog.
     */
    fun offer(event: Event) {
        if (event.time in timeframe) {
            while (!channel.trySend(event).isSuccess) {
                if (done) return
                val dropped = channel.tryReceive().getOrNull()
                if (dropped !== null)
                    logger.debug { "dropped event for time ${dropped.time}" }
            }
        } else {
            if (event.time > timeframe) {
                logger.debug { "Offer ${event.time} after $timeframe, closing channel" }
                close()
            }
        }
    }

    /**
     * Iterate over the events in this channel
     */
    operator fun iterator() = channel.iterator()

    /**
     * Send an [event]. If the event is before the timeframe linked to this channel it will be
     * ignored. And if the event is after the timeframe, the channel will be closed.
     */
    suspend fun send(event: Event) {
        if (event.time in timeframe) {
            channel.send(event)
        } else {
            if (event.time > timeframe) {
                logger.debug { "Send ${event.time} after $timeframe, closing channel" }
                close()
            }
        }
    }

    /**
     * Receive an event from the channel. Will throw a [ClosedReceiveChannelException] if the channel is already closed.
     */
    suspend fun receive(): Event {
        while (true) {
            return channel.receive()
        }
    }

    /**
     * Close this [EventChannel] and mark it as [done]
     */
    fun close() {
        done = true
        channel.close()
    }

}
