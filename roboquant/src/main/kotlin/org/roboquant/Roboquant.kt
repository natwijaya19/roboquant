/*
 * Copyright 2020-2024 Neural Layer
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

package org.roboquant

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import org.roboquant.brokers.Account
import org.roboquant.brokers.Broker
import org.roboquant.brokers.closeSizes
import org.roboquant.brokers.sim.SimBroker
import org.roboquant.common.Logging
import org.roboquant.common.Timeframe
import org.roboquant.feeds.Event
import org.roboquant.feeds.EventChannel
import org.roboquant.feeds.Feed
import org.roboquant.feeds.TradePrice
import org.roboquant.journals.Journal
import org.roboquant.loggers.MemoryLogger
import org.roboquant.loggers.MetricsLogger
import org.roboquant.metrics.Metric
import org.roboquant.orders.MarketOrder
import org.roboquant.orders.createCancelOrders
import org.roboquant.policies.FlexPolicy
import org.roboquant.policies.Policy
import org.roboquant.strategies.Strategy
import java.time.Instant
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Roboquant is the engine of the platform that ties [strategy], [policy] and [broker] together and caters to a wide
 * variety of testing and live trading scenarios. Through [metrics] and a [logger] it provides insights into the
 * performance of a [run]. Only a strategy is required when instantiating a Roboquant, the other parameters are
 * optional.
 *
 * @property strategy The strategy to use, there is no default
 * @property metrics the various metrics to calculate during the runs, default is none
 * @property policy The policy to use, default is [FlexPolicy]
 * @property broker the broker to use, default is [SimBroker]
 * @property logger the metrics logger to use, default is [MemoryLogger]
 * @property channelCapacity the max capacity of the event channel, more capacity means more buffering. Default is 10.
 * @property onChannelFull the behavior when a channel is full, default is [BufferOverflow.SUSPEND].
 * For live trading, it might be appropriate to set this to [BufferOverflow.DROP_OLDEST]
 */
data class Roboquant(
    val strategy: Strategy,
    val metrics: List<Metric>,
    val policy: Policy = FlexPolicy(),
    val broker: Broker = SimBroker(),
    val logger: MetricsLogger = MemoryLogger(),
    private val channelCapacity: Int = 10,
    private val onChannelFull: BufferOverflow = BufferOverflow.SUSPEND
) {

    /**
     * Convenience constructor that instead on a list of Metrics, accept a vararg of metrics.
     *
     * @see Roboquant
     */
    constructor(
        strategy: Strategy,
        vararg metrics: Metric,
        policy: Policy = FlexPolicy(),
        broker: Broker = SimBroker(),
        logger: MetricsLogger = MemoryLogger(),
        channelCapacity: Int = 10
    ) : this(strategy, metrics.toList(), policy, broker, logger, channelCapacity)

    private val kotlinLogger = Logging.getLogger(Roboquant::class)


    init {
        kotlinLogger.debug { "Created new roboquant instance=$this" }
    }

    /**
     * Reset the state including that of the used underlying components. This allows starting a fresh run with the same
     * configuration as the original instance.
     *
     * By default, also the [logger] will be reset. If you don't want this, set [includeLogger] to false.
     */
    fun reset(includeLogger: Boolean = true) {
        strategy.reset()
        policy.reset()
        broker.reset()
        for (metric in metrics) metric.reset()
        if (includeLogger) logger.reset()
    }

    /**
     * Start a new run using the provided [feed] as data. If no [timeframe] is provided all the events in the feed
     * will be processed. You can provide a custom [name] that will help to later identify this run.
     * If none is provided, the default [name] "run-${timeframe}" will be used that can help to identify the run later.
     *
     * By default, at the beginning of a run, all components (besides the logger) will be [reset] and as a result
     * discard their state. If you don't want this behavior set [reset] to false.
     *
     * This is the synchronous (blocking) method of run that is convenient to use. However, if you want to execute runs
     * in parallel, use the [runAsync] method.
     */
    fun run(
        feed: Feed,
        timeframe: Timeframe = feed.timeframe,
        name: String = "run-${timeframe.toPrettyString()}",
        reset: Boolean = true
    ): Account = runBlocking {
        return@runBlocking runAsync(feed, timeframe, name, reset)
    }

    /**
     * This is the same method as the [run] method but as the name already suggests, asynchronously. This makes it
     * suited for running back-test in parallel. Other than that, it behaves the same as the regular blocking run
     * method.
     *
     * @see [run]
     */
    suspend fun runAsync(
        feed: Feed,
        timeframe: Timeframe = feed.timeframe,
        name: String = "run-${timeframe.toPrettyString()}",
        reset: Boolean = true
    ): Account {

        val channel = EventChannel(timeframe, channelCapacity, onChannelFull)
        val job = feed.playBackground(channel)

        if (reset) reset(false)

        try {
            while (true) {
                val event = channel.receive()
                val time = event.time

                // Sync with broker and run metrics
                val account = broker.sync(event)

                val metricResult = getMetrics(account, event)
                logger.log(metricResult, time, name)

                // Generate signals and place orders
                val signals = strategy.generate(event)
                val orders = policy.act(signals, account, event)
                broker.place(orders)

                kotlinLogger.trace {
                    "time=$time actions=${event.items.size} signals=${signals.size} orders=${orders.size}"
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // intentionally empty
        } finally {
            if (job.isActive) job.cancel()
        }
        return broker.sync()
    }

    /**
     * Calculate the configured [metrics] and metrics generated by components and return them
     */
    private fun getMetrics(account: Account, event: Event) = buildMap {
        for (metric in metrics) putAll(metric.calculate(account, event))
        putAll(strategy.getMetrics())
        putAll(policy.getMetrics())
        putAll(broker.getMetrics())
        kotlinLogger.trace { "captured metrics=$size" }
    }

    /**
     * Provide a string representation of this roboquant.
     */
    override fun toString(): String {
        val s = strategy::class.simpleName
        val p = policy::class.simpleName
        val l = logger::class.simpleName
        val b = broker::class.simpleName
        val m = metrics.map { it::class.simpleName }.toString()
        return "strategy=$s policy=$p logger=$l metrics=$m broker=$b"
    }

    /**
     * Close the open positions of the underlying broker account. This comes in handy at the end of a run if you
     * don't want to have open positions left in the portfolio. You can optionally provide the [time] and
     * [runName] to use to close the positions and log the metrics.
     *
     * This method performs the following steps:
     * 1. Cancel existing open orders
     * 2. Close open positions by placing [MarketOrder] for the required opposite sizes.
     * 3. Run and log the metrics
     */
    fun closePositions(time: Instant? = null, runName: String = "close"): Account {
        val account = broker.sync()
        val eventTime = time ?: account.lastUpdate
        val cancelOrders = account.openOrders.createCancelOrders()
        val change = account.positions.closeSizes
        val changeOrders = change.map { MarketOrder(it.key, it.value) }
        val orders = cancelOrders + changeOrders
        val actions = account.positions.map { TradePrice(it.asset, it.mktPrice) }
        val event = Event(eventTime, actions)
        broker.place(orders)
        val newAccount = broker.sync(event)
        val metricResult = getMetrics(newAccount, event)
        logger.log(metricResult, event.time, runName)
        return newAccount
    }


}

/**
 * Blocking version of runAsync
 */
fun run(
    feed: Feed,
    strategy: Strategy,
    journal: Journal? = null,
    timeframe: Timeframe = Timeframe.INFINITE,
    policy: Policy = FlexPolicy(),
    broker: Broker = SimBroker(),
    channel: EventChannel = EventChannel(timeframe, 10),
    heartbeatTimeout: Long = -1,
    progressBar: Boolean = false
): Account = runBlocking {
    return@runBlocking runAsync(
        feed,
        strategy,
        journal,
        timeframe,
        policy,
        broker,
        channel,
        heartbeatTimeout,
        progressBar
    )
}

/**
 * Run async
 */
suspend fun runAsync(
    feed: Feed,
    strategy: Strategy,
    journal: Journal? = null,
    timeframe: Timeframe = Timeframe.INFINITE,
    policy: Policy = FlexPolicy(),
    broker: Broker = SimBroker(),
    channel: EventChannel = EventChannel(timeframe, 10),
    heartbeatTimeout: Long = -1,
    showProgressBar: Boolean = false
): Account {
    val job = feed.playBackground(channel)
    val progressBar = if (showProgressBar) {
        val tf = if (timeframe.isFinite()) timeframe else feed.timeframe
        val pb = ProgressBar(tf)
        pb.start()
        pb
    } else null

    try {
        while (true) {
            val event = channel.receive(heartbeatTimeout)
            progressBar?.update(event.time)
            // Sync with broker
            val account = broker.sync(event)

            // Generate signals and place orders
            val signals = strategy.generate(event)
            val orders = policy.act(signals, account, event)
            broker.place(orders)

            journal?.track(event, account, signals, orders)
        }
    } catch (_: ClosedReceiveChannelException) {
        // intentionally empty
    } finally {
        if (job.isActive) job.cancel()
        progressBar?.stop()
    }
    return broker.sync()
}


private class ProgressBar(val timeframe: Timeframe) {

    private var currentPercent = -1
    private val progressChar = getProgressChar()
    private var pre: String = ""
    private var post: String = ""
    private var nextUpdate = Instant.MIN
    private var lastOutput = ""

    init {
        currentPercent = 0
        post = ""
        pre = "${timeframe.toPrettyString()} | "
        nextUpdate = Instant.MIN
        lastOutput = ""
    }

    /**
     * Start a progress bar for the [run] and [timeframe]
     */
    fun start() {
        draw(currentPercent)
    }

    /**
     * Update the progress bar, giving the provided [time]
     */
    fun update(time: Instant) {

        // Only if percentage changes we are going to refresh
        val totalDuration = timeframe.duration
        val currentDuration = Timeframe(timeframe.start, time).duration
        var percent = (currentDuration.seconds * 100.0 / totalDuration.seconds).roundToInt()
        percent = min(percent, 100)
        if (percent == currentPercent) return

        // Avoid updating the progress meter too often
        val now = Instant.now()
        if (now < nextUpdate) return
        nextUpdate = now.plusMillis(500)
        currentPercent = percent
        draw(percent)
    }

    private fun draw(percent: Int) {
        val sb = StringBuilder(100)
        sb.append('\r').append(pre)
        sb.append(String.format(Locale.ENGLISH, "%3d", percent)).append("% |")
        val filled = percent * TOTAL_BAR_LENGTH / 100
        repeat(TOTAL_BAR_LENGTH) {
            if (it <= filled) sb.append(progressChar) else sb.append(' ')
        }

        sb.append(post)
        if (percent == 100) sb.append("\n")
        val str = sb.toString()

        // Only update if there are some changes to the progress bar
        if (str != lastOutput) {
            print(str)
            lastOutput = str
            System.out.flush()
        }
    }

    /**
     * Signal that the current task is done, so the progress bar can show it has finished.
     */
    fun stop() {
        if (currentPercent < 100) {
            draw(100)
            System.out.flush()
        }
    }

    private companion object {

        private const val TOTAL_BAR_LENGTH = 36

        private fun getProgressChar(): Char {
            return if (System.getProperty("os.name").startsWith("Win")) '=' else '█'
        }
    }
}
