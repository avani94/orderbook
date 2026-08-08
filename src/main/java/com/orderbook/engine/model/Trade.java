package com.orderbook.engine.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An immutable fact: two orders crossed and produced an execution.
 * Convention: executionPrice is always the resting order's price
 * (the incoming aggressor takes the maker's price).
 */
public record Trade(
        String tradeId,
        String symbol,
        String buyOrderId,
        String sellOrderId,
        BigDecimal executionPrice,
        long executionQuantity,
        Instant timestamp
) {
    public Trade {
        if (executionQuantity <= 0) {
            throw new IllegalArgumentException("executionQuantity must be positive: " + executionQuantity);
        }
        if (executionPrice == null || executionPrice.signum() <= 0) {
            throw new IllegalArgumentException("executionPrice must be positive: " + executionPrice);
        }
    }
}
