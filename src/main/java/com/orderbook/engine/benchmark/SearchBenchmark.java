package com.orderbook.engine.benchmark;

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

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Cost of a single getBestBid/getBestAsk lookup once the book is already
 * populated. Non-mutating, so steady-state per-call AverageTime applies.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class SearchBenchmark {

    @Param({"LINKED_LIST", "BST"})
    public OrderBookImplementation implementation;

    @Param({"10000", "100000", "1000000"})
    public int orderCount;

    private OrderBook book;

    @Setup(Level.Trial)
    public void setup() {
        book = implementation.create();
        OrderFixtures.randomOrders(orderCount, OrderType.BUY, 100.00, 149.99).forEach(book::addOrder);
        OrderFixtures.randomOrders(orderCount, OrderType.SELL, 150.01, 200.00).forEach(book::addOrder);
    }

    @Benchmark
    public Optional<BigDecimal> bestBid() {
        return book.getBestBid();
    }

    @Benchmark
    public Optional<BigDecimal> bestAsk() {
        return book.getBestAsk();
    }
}
