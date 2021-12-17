/*
 * Copyright 2021 Neural Layer
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

package org.roboquant.oanda

import com.oanda.v20.Context
import com.oanda.v20.instrument.CandlestickGranularity
import com.oanda.v20.instrument.InstrumentCandlesRequest
import com.oanda.v20.primitives.InstrumentName
import org.roboquant.common.Logging
import org.roboquant.common.TimeFrame
import org.roboquant.common.days
import org.roboquant.feeds.HistoricPriceFeed
import org.roboquant.feeds.PriceBar
import java.time.Instant

/**
 * Retrieve historic FOREX data from OANDA.
 */
class OANDAHistoricFeed(token: String? = null, demoAccount: Boolean = true) : HistoricPriceFeed() {

    private val ctx: Context = OANDA.getContext(token, demoAccount)
    private val accountID = OANDA.getAccountID(null, ctx)

    val availableAssets by lazy {
        OANDA.getAvailableAssets(ctx,accountID)
    }
    private val logger = Logging.getLogger("OANDAHistoricFeed")

    fun retrieveCandles(
        vararg symbols: String,
        timeFrame: TimeFrame = TimeFrame.past(1.days),
        granularity: String = "M1",
        priceType: String = "M",
    ) {
        for (symbol in symbols) {
            val request = InstrumentCandlesRequest(InstrumentName(symbol))
                .setPrice(priceType)
                .setFrom(timeFrame.start.toString())
                .setTo(timeFrame.end.toString())
                .setGranularity(CandlestickGranularity.valueOf(granularity))
            val resp = ctx.instrument.candles(request)
            val asset = availableAssets[resp.instrument.toString()]!!
            if (resp.candles.isEmpty()) logger.warning("No candles retrieved for $symbol for period $timeFrame")
            resp.candles.forEach {
                with(it.mid) {
                    val action =
                        PriceBar(asset, o.doubleValue(), h.doubleValue(), l.doubleValue(), c.doubleValue(), it.volume)
                    val now = Instant.parse(it.time)
                    add(now, action)
                }
            }
        }
    }


}