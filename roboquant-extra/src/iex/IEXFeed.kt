package org.roboquant.iex

import iex.IEXConnection
import org.roboquant.common.Asset
import org.roboquant.common.Logging
import org.roboquant.feeds.*
import pl.zankowski.iextrading4j.api.stocks.Chart
import pl.zankowski.iextrading4j.api.stocks.ChartRange
import pl.zankowski.iextrading4j.api.stocks.v1.Intraday
import pl.zankowski.iextrading4j.client.IEXCloudClient
import pl.zankowski.iextrading4j.client.rest.request.stocks.ChartRequestBuilder
import pl.zankowski.iextrading4j.client.rest.request.stocks.v1.IntradayRequestBuilder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.*


typealias Range = ChartRange

/**
 * Feed of historic price data using IEX Cloud as the data source.
 *
 * @constructor
 *
 * @param publicKey
 * @param secretKey
 */
class IEXFeed(
    publicKey: String? = null,
    secretKey: String? = null,
    sandbox: Boolean = true,
    private val template: Asset = Asset("TEMPLATE")
) : HistoricFeed {

    private val events = TreeMap<Instant, MutableList<PriceAction>>()
    private val logger = Logging.getLogger("IEXFeed")
    private val client: IEXCloudClient


    override val timeline: List<Instant>
        get() = events.keys.toList()

    override val assets
        get() = events.values.map { priceBars -> priceBars.map { it.asset }.distinct() }.flatten().distinct().toSortedSet()


    init {
        client = IEXConnection.getClient(publicKey, secretKey, sandbox)
    }

    /**
     * (Re)play the events of the feed using the provided [EventChannel]
     *
     * @param channel
     * @return
     */
    override suspend fun play(channel: EventChannel) {
        events.forEach {
            val event = Event(it.value, it.key)
            channel.send(event)
        }
    }

    /**
     * Retrieve historic intraday price bars for one or more assets
     *
     * @param assets
     */
    fun retrieveIntraday(vararg assets: Asset) {
        assets.forEach {
            val quote = client.executeRequest(
                IntradayRequestBuilder()
                    .withSymbol(it.symbol)
                    .build()
            )
            handleIntraday(it, quote)
        }
    }

    /**
     * Retrieve historic end of day price bars for one or more symbols
     *
     * @param symbols
     */
    fun retrieveIntraday(vararg symbols: String)  {
        val assets = symbols.map { template.copy(symbol = it.uppercase()) }
        retrieveIntraday(*assets.toTypedArray())
    }

    /**
     * Retrieve historic end of day price bars for one or more symbols
     *
     * @param symbols
     */
    fun retrievePriceBar(vararg symbols: String, range: Range = Range.FIVE_YEARS)  {
        val assets = symbols.map { template.copy(symbol = it.uppercase()) }
        retrievePriceBar(*assets.toTypedArray(), range = range)
    }

    /**
     * Retrieve historic end of day price bars for one or more assets
     *
     * @param assets
     */
    fun retrievePriceBar(vararg assets: Asset, range:Range = Range.FIVE_YEARS) {

        assets.forEach {
            val chart = client.executeRequest(
                ChartRequestBuilder()
                    .withChartRange(range)
                    .withSymbol(it.symbol)
                    .build()
            )
            handlePriceBar(it, chart)
        }
    }


    private fun getInstant(asset: Asset, date: String, minute: String?): Instant {
        return if (minute !== null) {
            val dt = LocalDateTime.parse("${date}T$minute")
            asset.exchange.getInstant(dt)
        } else {
            val d = LocalDate.parse(date)
            asset.exchange.getClosingTime(d)
        }
    }

    private fun handlePriceBar(asset: Asset, chart: List<Chart>) {
        chart.filter { it.open !== null }.forEach {
            val action = PriceBar(asset, it.open, it.high, it.low, it.close, it.volume)
            val now = getInstant(asset, it.date, it.minute)
            val list = events.getOrPut(now) { mutableListOf() }
            list.add(action)
        }
        logger.info { "Received data for $asset" }
        logger.info { "Total ${events.size} steps from ${timeline.first()} to ${timeline.last()}" }
    }


    private fun handleIntraday(asset: Asset, quotes: List<Intraday>) {
        quotes.filter { it.open !== null }.forEach {
            val action = PriceBar(asset, it.open, it.high, it.low, it.close, it.volume)
            val now = getInstant(asset, it.date, it.minute)
            val list = events.getOrPut(now) { mutableListOf() }
            list.add(action)
        }
        logger.info { "Received data for $asset" }
        logger.info { "Total ${events.size} steps from ${timeline.first()} to ${timeline.last()}" }
    }

}
