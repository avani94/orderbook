package com.orderbook.engine.benchmark;

import com.orderbook.engine.model.Order;
import com.orderbook.engine.model.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic (fixed-seed) order generators so every implementation and
 * every benchmark run is measured against identical input.
 */
final class OrderFixtures {

    private static final String SYMBOL = "AAPL";
    private static final long SEED = 42L;

    private OrderFixtures() {
    }

    /** Orders at random prices in [minPrice, maxPrice], for insertion/search fixtures. */
    static List<Order> randomOrders(int count, OrderType type, double minPrice, double maxPrice) {
        Random random = new Random(SEED + type.ordinal());
        Instant base = Instant.now();
        List<Order> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double raw = minPrice + random.nextDouble() * (maxPrice - minPrice);
            BigDecimal price = BigDecimal.valueOf(Math.round(raw * 100), 2);
            long quantity = 1 + random.nextInt(100);
            orders.add(new Order(type.name() + "-" + i, "user-1", SYMBOL, type, price, quantity, base.plusNanos(i)));
        }
        return orders;
    }

    /** All orders at the same price, quantity 1 - for matching-path benchmarks. */
    static List<Order> restingOrdersAtPrice(int count, OrderType type, BigDecimal price) {
        Instant base = Instant.now();
        List<Order> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            orders.add(new Order(type.name() + "-fixed-" + i, "user-1", SYMBOL, type, price, 1, base.plusNanos(i)));
        }
        return orders;
    }
}
