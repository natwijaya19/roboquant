/*
 * Copyright 2020-2023 Neural Layer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.roboquant.feeds

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import org.roboquant.common.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.collections.set
import kotlin.math.max
import kotlin.math.min

/**
 * Aggregate prices in a [feed] to a [PriceBar]. The [aggregationPeriod] is configurable. Right now there is support for
 * aggregating the following types of price actions:
 *
 * 1. PriceBar
 * 2. TradePrice
 * 3. PriceQuote (midpoint)
 * 4. OrderBook (midpoint)
 *
 * If an action is not recognized, it is ignored.
 *
 * @property feed the feed to use that has the prices that need to be aggregated
 * @property aggregationPeriod the aggregation period, for example `15.minutes`
 * @property remaining should any remaining actions be sent, default is true
 *
 */
class AggregatorFeed2(
    private val feed: Feed,
    private val aggregationPeriod: TimeSpan,
    private val remaining: Boolean = true
) : Feed {

    private fun Instant.expirationTime(): Instant {
        val intervalMillis = until(this + aggregationPeriod, ChronoUnit.MILLIS)
        val adjustedInstantMillis = toEpochMilli() / intervalMillis * intervalMillis
        return Instant.ofEpochMilli(adjustedInstantMillis) + aggregationPeriod
    }

    /**
     * Provide the timeframe, this can be slightly off since upfront it is not known what the actions are that are
     * in the underlying feed.
     */
    override val timeframe: Timeframe
        get() = feed.timeframe.extend(aggregationPeriod)

    private operator fun PriceBar.plus(other: PriceBar): PriceBar {
        val high = max(other.high, high)
        val low = min(other.low, low)
        return PriceBar(asset, open, high, low, other.close, volume + other.volume, aggregationPeriod)
    }


    /**
     * @suppress
     */
    @Suppress("CyclomaticComplexMethod")
    override suspend fun play(channel: EventChannel) {
        val c = EventChannel(channel.capacity, channel.timeframe)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val job = scope.launch {
            c.use {
                feed.play(it)
            }
        }

        val history = mutableMapOf<Asset, PriceBar>()
        var expiration: Instant? = null
        try {
            while (true) {
                val event = c.receive()
                val time = event.time

                if (expiration == null) {
                    expiration = time.expirationTime()
                } else if (time >= expiration) {
                    val newEvent = Event(history.values.toList(), expiration)
                    if (newEvent.actions.isNotEmpty()) channel.send(newEvent)
                    history.clear()
                    expiration += aggregationPeriod
                }

                for (action in event.actions) {
                    val pb = getPriceBar(action, aggregationPeriod) ?: continue
                    val asset = pb.asset
                    val entry = history[asset]
                    if (entry == null) {
                        history[asset] = pb
                    } else {
                        history[asset] = entry + pb
                    }
                }

            }

        } catch (_: ClosedReceiveChannelException) {
            // NOP
        } finally {

            // Send remaining
            if (remaining && history.isNotEmpty() && expiration != null) {
                val newEvent = Event(history.values.toList(), expiration)
                channel.send(newEvent)
            }
            if (job.isActive) job.cancel()
        }
    }

}


fun main() {
    val forex = AvroFeed.forex()
    val feed = AggregatorFeed2(forex, 15.minutes)
    feed.apply<PriceAction> { _, time ->
        println(time)
    }
}