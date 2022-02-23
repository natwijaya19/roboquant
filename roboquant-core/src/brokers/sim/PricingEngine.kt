package org.roboquant.brokers.sim

import org.roboquant.feeds.PriceAction
import java.time.Instant

/**
 * Interface for any pricing engine to implement. Ideally implementations should be able to support any type of price
 * actions, although they can specialize for certain types of price actions, like a PriceBar.
 */
fun interface PricingEngine {

    /**
     * Return a pricing (calculator) for the provided price [action]. Although most often not used, advanced pricing
     * calculators can be dependent on the [time]. For example certain FOREX exchanges might be more volatile during
     * certain timeframes and this can be reflected in the Pricing.
     */
    fun getPricing(action: PriceAction, time: Instant): Pricing

    /**
     * Clear any state of the pricing engine. General speaking a PricingEngine is stateless, but advanced engines might
     * implement some type of ripple-effect pricing strategy. Default is to do nothing.
     */
    fun clear() {}
}

/**
 * Pricing calclulator provided to OrderCommand to execute an order and disciver the price to be used.
 */
interface Pricing {


    /**
     * Get the low price for the provided [volume]. Default is the [marketPrice]
     */
    fun lowPrice(volume: Double): Double = marketPrice(volume)

    /**
     * Get the high price for the provided [volume]. Default is the [marketPrice]
     */
    fun highPrice(volume: Double): Double = marketPrice(volume)

    /**
     * Get the market price for the provided [volume]. There is no default.
     */
    fun marketPrice(volume: Double): Double

}


/**
 * Pricing model that uses a constant [slippage] in BIPS to determine final trading price. It calculates the same price for
 * high, low and market prices. It works with any type of PriceAction.
 */
class SlippagePricing(private val slippage: Int = 10, private val priceType: String = "DEFAULT") : PricingEngine {

    private class SlippagePricing(val price: Double, val slippage: Int) : Pricing {

        override fun marketPrice(volume: Double): Double {
            val correction = if (volume > 0) 1.0 + slippage / 10_000.0 else 1.0 - slippage / 10_000.0
            return price * correction
        }
    }

    override fun getPricing(action: PriceAction, time: Instant): Pricing {
        return SlippagePricing(action.getPrice(priceType), slippage)
    }
}


/**
 * Pricing model that uses a no slippage. It calculates the same price for high, low and market prices. It works
 * with any type of PriceAction.
 */
class NoSlippagePricing(private val priceType: String = "DEFAULT") : PricingEngine {

    private class SlippagePricing(val price: Double) : Pricing {

        override fun marketPrice(volume: Double) = price
    }

    override fun getPricing(action: PriceAction, time: Instant): Pricing {
        return SlippagePricing(action.getPrice(priceType))
    }
}
