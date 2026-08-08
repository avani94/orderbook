package com.orderbook.engine.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A resting or incoming limit order. Mutable by design: the matching engine
 * fills orders in place (adjusting remainingQuantity/status) rather than
 * replacing them, since a single order can be touched by many trades.
 */
public class Order {

    private final String orderId;
    private final String userId;
    private final String symbol;
    private final OrderType orderType;
    private final BigDecimal price;
    private final long quantity;
    private final Instant timestamp;

    private long remainingQuantity;
    private OrderStatus status;

    public Order(String orderId, String userId, String symbol, OrderType orderType,
                 BigDecimal price, long quantity, Instant timestamp) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive: " + price);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.orderType = Objects.requireNonNull(orderType, "orderType");
        this.price = price;
        this.quantity = quantity;
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.remainingQuantity = quantity;
        this.status = OrderStatus.NEW;
    }

    /** Reduces remainingQuantity by fillQuantity and advances status accordingly. */
    public void fill(long fillQuantity) {
        if (fillQuantity <= 0 || fillQuantity > remainingQuantity) {
            throw new IllegalArgumentException(
                    "invalid fill quantity " + fillQuantity + " for remaining " + remainingQuantity);
        }
        remainingQuantity -= fillQuantity;
        status = remainingQuantity == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel() {
        if (status == OrderStatus.FILLED) {
            throw new IllegalStateException("cannot cancel a fully filled order: " + orderId);
        }
        status = OrderStatus.CANCELLED;
    }

    public boolean isActive() {
        return status == OrderStatus.NEW || status == OrderStatus.PARTIALLY_FILLED;
    }

    public boolean isBuy() {
        return orderType == OrderType.BUY;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "Order{orderId='%s', symbol='%s', type=%s, price=%s, remaining=%d/%d, status=%s}"
                .formatted(orderId, symbol, orderType, price, remainingQuantity, quantity, status);
    }
}
