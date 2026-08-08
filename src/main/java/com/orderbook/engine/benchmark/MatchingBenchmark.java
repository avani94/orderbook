package com.orderbook.engine.benchmark;

import com.orderbook.engine.model.Order;
import com.orderbook.engine.model.OrderType;
import com.orderbook.engine.strategy.OrderBook;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cost of draining orderCount/2 resting orders via matching: each incoming
 * order crosses immediately against the oldest resting order at the same
 * price. Isolates the "dequeue + trade + index removal" cost, independent
 * of price-level lookup (which InsertionBenchmark/SearchBenchmark cover).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
public class MatchingBenchmark {

    private static final BigDecimal PRICE = new BigDecimal("150.00");

    @Param({"LINKED_LIST", "BST"})
    public OrderBookImplementation implementation;

    @Param({"10000", "100000", "1000000"})
    public int orderCount;

    private OrderBook book;
    private List<Order> crossingSells;

    @Setup(Level.Invocation)
    public void setup() {
        book = implementation.create();
        OrderFixtures.restingOrdersAtPrice(orderCount / 2, OrderType.BUY, PRICE).forEach(book::addOrder);
        crossingSells = OrderFixtures.restingOrdersAtPrice(orderCount / 2, OrderType.SELL, PRICE);
    }

    @Benchmark
    public void matchAllOrders(Blackhole blackhole) {
        for (Order order : crossingSells) {
            blackhole.consume(book.addOrder(order));
        }
    }
}
