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

package org.roboquant.brokers

import org.apache.commons.math3.stat.descriptive.rank.Percentile
import org.roboquant.common.*
import org.roboquant.orders.OrderState
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

/**
 * Account represents a brokerage trading account and is unified across broker implementations. This is an immutable
 * class and tt holds the following state:
 *
 * - [cash] balances in the account
 * - the [positions] with its assets
 * - The past [trades]
 * - The [openOrders] and [closedOrders] and their state
 *
 * Some methods convert a multi-currency Wallet to a single-currency Amount. For this to work, you'll need to have
 * the appropriate exchange rates defined [Config.exchangeRates].
 *
 * @property baseCurrency what is the base currency of the account
 * @property lastUpdate when was the account last updated
 * @property cash cash balances
 * @property trades List of all trades
 * @property openOrders List of [OrderState] of all open orders
 * @property closedOrders List of [OrderState] of all closed orders
 * @property positions List of all open [Position], with max one entry per asset
 * @property buyingPower amount of buying power remaining
 * @constructor Create new Account
 */
class Account(
    val baseCurrency: Currency,
    val lastUpdate: Instant,
    val cash: Wallet,
    val trades: List<Trade>,
    val openOrders: List<OrderState>,
    val closedOrders: List<OrderState>,
    val positions: List<Position>,
    val buyingPower: Amount
) {

    /**
     * Cash balances converted to a single amount denoted in the [baseCurrency] of the account
     */
    val cashAmount: Amount
        get() = convert(cash)

    /**
     * [equity] converted to a single amount denoted in the [baseCurrency] of the account
     */
    val equityAmount: Amount
        get() = convert(equity)

    /**
     * Total equity hold in the account. Equity is defined as sum of [cash] balances and the [positions] market value
     */
    val equity: Wallet
        get() = cash + positions.marketValue

    /**
     * Unique set of assets hold in the open [positions]
     */
    val assets: Set<Asset>
        get() = positions.map { it.asset }.toSet()

    /**
     * Get the associated trades for the provided [orders]. If no orders are provided all [closedOrders] linked to this
     * account instance are used.
     */
    fun getOrderTrades(orders: Collection<OrderState> = this.closedOrders): Map<OrderState, List<Trade>> =
        orders.associateWith { order -> trades.filter { it.orderId == order.id } }.toMap()

    /**
     * Convert an [amount] to the account [baseCurrency] using last update of the account as a timestamp
     */
    fun convert(amount: Amount, time: Instant = lastUpdate): Amount = amount.convert(baseCurrency, time)

    /**
     * Convert a [wallet] to the account [baseCurrency] using last update of the account as a timestamp
     */
    fun convert(wallet: Wallet, time: Instant = lastUpdate): Amount = wallet.convert(baseCurrency, time)

    /**
     * Returns a summary that contains the high level account information, the available cash balances and
     * the open positions. Optionally the summary can convert wallets to a [singleCurrency], default is not.
     */
    fun summary(singleCurrency: Boolean = false): Summary {

        fun c(w: Wallet): Any {
            if (w.isEmpty()) return Amount(baseCurrency, 0.0)
            return if (singleCurrency) convert(w) else w
        }

        val s = Summary("account")
        s.add("last update", lastUpdate.truncatedTo(ChronoUnit.SECONDS))
        s.add("base currency", baseCurrency.displayName)
        s.add("cash", c(cash))
        s.add("buying power", buyingPower)
        s.add("equity", c(equity))
        s.add("open positions", positions.size)
        s.add("positions value", c(positions.marketValue))
        s.add("long value", c(positions.long.marketValue))
        s.add("short value", c(positions.short.marketValue))
        s.add("unrealized p&l", c(positions.unrealizedPNL))
        s.add("realized p&l", c(trades.realizedPNL))
        s.add("trades", trades.size)
        s.add("open orders", openOrders.size)
        s.add("closed orders", closedOrders.size)
        return s
    }

    /**
     * Provide a full summary of the account that contains all cash, orders, trades and open positions. During back
     * testing this can become a long list of items, so look at [summary] for a shorter summary.
     */
    fun fullSummary(singleCurrency: Boolean = false): Summary {
        val s = summary(singleCurrency)
        s.add(cash.summary())
        s.add(positions.summary())
        s.add(openOrders.summary("open orders"))
        s.add(closedOrders.summary("closed orders"))
        s.add(trades.summary())
        return s
    }

}

/**
 * Return the PNL outliers for a collection of trades for a given [percentage]
 */
fun Collection<Trade>.outliers(percentage: Double = 0.95): List<Trade> {
    val data = map { it.pnl.value.absoluteValue }.toDoubleArray()
    val p = Percentile()
    val boundary = p.evaluate(data, percentage * 100.0)
    return filter { it.pnl.value.absoluteValue >= boundary }
}

/**
 * Return the PNL inliers for a collection of trades for a given [percentage]
 */
fun Collection<Trade>.inliers(percentage: Double = 0.95): List<Trade> {
    val data = map { it.pnl.value.absoluteValue }.toDoubleArray()
    val p = Percentile()
    val boundary = p.evaluate(data, percentage * 100.0)
    return filter { it.pnl.value.absoluteValue < boundary }
}

/**
 * Return the total fee for a collection of trades
 */
val Collection<Trade>.fee
    get() = sumOf { it.fee }

/**
 * Return the timeline for a collection of trades
 */
val Collection<Trade>.timeline
    get() = map { it.time }.distinct().sorted()

/**
 * Return the unique assets for a collection of positions
 */
val Collection<Position>.assets
    get() = map { it.asset }.distinct()

/**
 * Return the long positions for a collection of positions
 */
val Collection<Position>.long
    get() = filter { it.long }

/**
 * Return the short positions for a collection of positions
 */
val Collection<Position>.short
    get() = filter { it.short }

/**
 * Return the position for an asset
 */
fun Collection<Position>.getPosition(asset: Asset): Position {
    return firstOrNull { it.asset == asset } ?: Position.empty(asset)
}

/**
 * Return the total unrealized PNL for a collection of positions
 */
val Collection<Position>.unrealizedPNL
    get() = sumOf { it.unrealizedPNL }

/**
 * Return the total realized PNL for a collection of trades
 */
val Collection<Trade>.realizedPNL
    get() = sumOf { it.pnl }

/**
 * Return the timeframe for a collection of trades
 */
val Collection<Trade>.timeframe
    get() = timeline.timeframe


/**
 * Get the unique assets for a collection of order states
 */
val List<OrderState>.assets
    get() = map { it.asset }.distinct().toSet()


/**
 * Return the required sizing per asset to close the positions. This method doesn't close the actual open positions,
 * just provides the information to do so.
 */
fun Collection<Position>.close(): Map<Asset, Size> = diff(emptyList())


/**
 * Return the difference between these positions and a target set of positions.
 */
fun Collection<Position>.diff(target: Collection<Position>): Map<Asset, Size> {
    val result = mutableMapOf<Asset, Size>()

    for (position in target) {
        val targetSize = position.size
        val sourceSize = getPosition(position.asset).size
        val value = targetSize - sourceSize
        if (!value.iszero) result[position.asset] = value
    }

    for (position in this) {
        if (position.asset !in result) result[position.asset] = -position.size
    }

    return result
}

/**
 * Provide a summary for the orders
 */
@JvmName("summaryOrders")
fun Collection<OrderState>.summary(name: String = "Orders"): Summary {
    val s = Summary(name)
    if (isEmpty()) {
        s.add("EMPTY")
    } else {
        val orders = sortedBy { it.id }
        val lines = mutableListOf<List<Any>>()
        lines.add(listOf("type", "symbol", "ccy", "status", "id", "opened at", "closed at", "details"))
        orders.forEach {
            with(it) {
                val t1 = openedAt.truncatedTo(ChronoUnit.SECONDS)
                val t2 = closedAt.truncatedTo(ChronoUnit.SECONDS)
                val infoString = order.info().toString().removeSuffix("}").removePrefix("{")
                lines.add(
                    listOf(
                        order.type,
                        asset.symbol,
                        asset.currencyCode,
                        status,
                        order.id,
                        t1,
                        t2,
                        infoString
                    )
                )
            }
        }
        return lines.summary(name)
    }
    return s
}

/**
 * Create a summary of the trades
 */
@JvmName("summaryTrades")
fun Collection<Trade>.summary(name: String = "trades"): Summary {
    val s = Summary(name)
    if (isEmpty()) {
        s.add("EMPTY")
    } else {
        val trades = sortedBy { it.time }
        val lines = mutableListOf<List<Any>>()
        lines.add(listOf("time", "symbol", "ccy", "size", "cost", "fee", "rlzd p&l", "price"))
        trades.forEach {
            with(it) {
                val currency = asset.currency
                val cost = totalCost.formatValue()
                val fee = fee.formatValue()
                val pnl = pnl.formatValue()
                val price = Amount(currency, price).formatValue()
                val t = time.truncatedTo(ChronoUnit.SECONDS)
                lines.add(listOf(t, asset.symbol, currency.currencyCode, size, cost, fee, pnl, price))
            }
        }
        return lines.summary(name)
    }
    return s
}


/**
 * Create a [Summary] of this portfolio that contains an overview of the open positions.
 */
@JvmName("summaryPositions")
fun Collection<Position>.summary(name: String = "positions"): Summary {
    val s = Summary(name)

    if (isEmpty()) {
        s.add("EMPTY")
    } else {
        val positions = sortedBy { it.asset.symbol }
        val lines = mutableListOf<List<Any>>()
        lines.add(
            listOf(
                "symbol",
                "ccy",
                "size",
                "entry price",
                "mkt price",
                "mkt value",
                "unrlzd p&l"
            )
        )

        for (v in positions) {
            val c = v.asset.currency
            val pos = v.size
            val avgPrice = Amount(c, v.avgPrice).formatValue()
            val price = Amount(c, v.mktPrice).formatValue()
            val value = v.marketValue.formatValue()
            val pnl = Amount(c, v.unrealizedPNL.value).formatValue()
            lines.add(listOf(v.asset.symbol, c.currencyCode, pos, avgPrice, price, value, pnl))
        }
        return lines.summary(name)
    }

    return s
}

/**
 * Create a summary for a collection of rows in which each row contains 1 or more columns
 */
fun Collection<List<Any>>.summary(name: String): Summary {
    val maxSizes = mutableMapOf<Int, Int>()
    for (line in this) {
        for (column in line.withIndex()) {
            val maxSize = maxSizes.getOrDefault(column.index, Int.MIN_VALUE)
            val len = column.value.toString().length
            if (len > maxSize) maxSizes[column.index] = len
        }
    }

    val summary = Summary(name)
    for (line in this) {
        val result = StringBuffer()
        for (column in line.withIndex()) {
            val maxSize = maxSizes.getOrDefault(column.index, 9) + 1
            val str = "%${maxSize}s│".format(column.value)
            result.append(str)
        }
        summary.add(result.toString())
    }
    return summary
}